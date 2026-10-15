package net.sculklink.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minecraft -> Discord. Posts to a channel webhook.
 *
 * <p>Every public method here is called from the server thread, so none of them may block:
 * they hand the message to a single-threaded executor and return immediately. Keeping that
 * executor single-threaded also keeps messages in order, which a pool would not. Posts that
 * show a player's head are built on that executor too, since a Bedrock player's head needs a
 * web lookup first.
 */
public final class DiscordSender {
    /** Discord's own cap on webhook message content. */
    private static final int DISCORD_CONTENT_LIMIT = 2000;

    private static final int COLOR_JOIN = 0x43B581;
    private static final int COLOR_LEAVE = 0xF04747;
    private static final int COLOR_DEATH = 0x992D22;
    private static final int COLOR_ADVANCEMENT = 0xFAA61A;
    private static final int COLOR_STOP = 0x747F8D;
    private static final int COLOR_UPDATE = 0x5865F2;

    /** Discord's cap on an embed description. */
    private static final int EMBED_DESCRIPTION_LIMIT = 4096;

    /** {@code @name} typed in Minecraft. Discord names are alphanumeric plus _ and . */
    private static final Pattern MENTION = Pattern.compile("@([A-Za-z0-9_.]{2,32})");

    /** Looks a Minecraft-typed name up against Discord users and roles. */
    @FunctionalInterface
    public interface MentionResolver {
        String userId(String name);

        default String roleId(String name) {
            return null;
        }
    }

    private final BridgeConfig config;
    private final HttpClient http;
    private final ExecutorService queue;
    private final BedrockSkins bedrockSkins = new BedrockSkins();
    private volatile MentionResolver mentionResolver;

    public DiscordSender(BridgeConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.queue = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sculklink-sender");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Supplies the name lookup used for outbound mentions. Set only when the inbound half is
     * configured — without a gateway there is nothing to resolve names against, and mentions
     * fall back to plain text.
     */
    public void setMentionResolver(MentionResolver resolver) {
        this.mentionResolver = resolver;
    }

    // ---- Public API (server thread) -------------------------------------------------------

    /** A player's in-game chat, wearing their own name and skin in Discord. */
    public void sendPlayerChat(String playerName, String uuid, String message) {
        enqueue(() -> {
            JsonObject payload = base(playerName, avatarFor(playerName, uuid));

            List<String> users = new ArrayList<>();
            List<String> roles = new ArrayList<>();
            payload.addProperty("content", truncate(renderContent(message, users, roles),
                    DISCORD_CONTENT_LIMIT));

            // Replace the block-everything default with an explicit allow-list. Listing ids is
            // what makes a ping safe: only names the mod actually resolved can notify anyone,
            // and @everyone stays impossible because "parse" is still empty.
            if (!users.isEmpty() || !roles.isEmpty()) {
                JsonObject allowed = new JsonObject();
                allowed.add("parse", new JsonArray());
                allowed.add("users", toJsonArray(users));
                allowed.add("roles", toJsonArray(roles));
                payload.add("allowed_mentions", allowed);
            }
            return payload;
        }, config.mainWebhook());
    }

    /** A plain line from the mod itself — used for command replies such as {@code !list}. */
    public void sendPlain(String text) {
        JsonObject payload = base(config.serverName, null);
        payload.addProperty("content", truncate(text, DISCORD_CONTENT_LIMIT));
        enqueue(payload);
    }

    public void sendJoin(String playerName, String uuid) {
        sendPlayerEmbed("**" + escapeMarkdown(playerName) + "** joined the server", COLOR_JOIN, playerName, uuid,
                config.joinLeaveWebhook());
    }

    public void sendLeave(String playerName, String uuid) {
        sendPlayerEmbed("**" + escapeMarkdown(playerName) + "** left the server", COLOR_LEAVE, playerName, uuid,
                config.joinLeaveWebhook());
    }

    /**
     * Death message, already rendered by the game ("Steve was slain by a Zombie"), so it
     * carries the same wording players saw in chat.
     */
    public void sendDeath(String deathMessage, String playerName, String uuid) {
        sendPlayerEmbed("💀 " + escapeMarkdown(deathMessage), COLOR_DEATH, playerName, uuid,
                config.deathsWebhook());
    }

    public void sendAdvancement(String playerName, String uuid, String kind, String title) {
        String text = "🏆 **" + escapeMarkdown(playerName) + "** has "
                + escapeMarkdown(kind) + " **" + escapeMarkdown(title) + "**";
        sendPlayerEmbed(text, COLOR_ADVANCEMENT, playerName, uuid);
    }

    /**
     * The {@code !deaths} leaderboard, wearing the leader's head. Goes to whichever channel was
     * asked: the chat channel's webhook, or the deaths channel's.
     */
    public void sendLeaderboard(JsonObject embed, DeathStats leader, String webhook) {
        enqueue(() -> {
            JsonObject payload = base(config.serverName, null);
            JsonObject card = embed.deepCopy();
            addThumbnail(card, leader);
            JsonArray embeds = new JsonArray();
            embeds.add(card);
            payload.add("embeds", embeds);
            return payload;
        }, webhook);
    }

    /**
     * Adds the leader's head to an embed the bot will send itself (DM and management replies),
     * on the sender thread, then hands it on. The bot posts through the gateway, not a webhook.
     */
    public void withLeaderThumbnail(JsonObject embed, DeathStats leader, Consumer<JsonObject> then) {
        try {
            queue.execute(() -> {
                JsonObject card = embed.deepCopy();
                addThumbnail(card, leader);
                then.accept(card);
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Shutting down; the reply is not worth holding the server open for.
        }
    }

    private void addThumbnail(JsonObject embed, DeathStats leader) {
        String avatar = leader == null ? null : avatarFor(leader.name(), leader.uuid());
        if (avatar != null) {
            JsonObject thumbnail = new JsonObject();
            thumbnail.addProperty("url", avatar);
            embed.add("thumbnail", thumbnail);
        }
    }

    public void sendServerStarted() {
        sendServerEmbed("✅ Server is **online**", COLOR_JOIN);
    }

    public void sendServerStopping() {
        sendServerEmbed("⛔ Server is **shutting down**", COLOR_STOP);
    }

    /**
     * What changed since the last start, already rendered by {@link UpdateTracker}. A big update
     * — a version bump across a large pack — can outgrow one embed, so it is split at line breaks
     * into as many cards as it needs rather than cut off: the saved state has already moved on,
     * so anything dropped here would never be reported.
     */
    public void sendUpdate(String description) {
        for (String part : splitAtLines(description, EMBED_DESCRIPTION_LIMIT)) {
            JsonObject payload = base(config.serverName, null);

            JsonObject embed = new JsonObject();
            embed.addProperty("description", part);
            embed.addProperty("color", COLOR_UPDATE);

            JsonArray embeds = new JsonArray();
            embeds.add(embed);
            payload.add("embeds", embeds);
            enqueue(payload, config.updatesWebhook());
        }
    }

    /**
     * Chunks of at most {@code limit} characters, broken between lines wherever possible. A line
     * longer than a whole chunk is itself split, never cut, since whatever is cut is lost for good.
     */
    static List<String> splitAtLines(String text, int limit) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            for (String piece : splitLongLine(line, limit)) {
                if (current.length() > 0 && current.length() + 1 + piece.length() > limit) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
                if (current.length() > 0) {
                    current.append('\n');
                }
                current.append(piece);
            }
        }
        if (!current.toString().isBlank()) {
            parts.add(current.toString());
        }
        return parts;
    }

    /** The line in pieces of at most {@code limit} characters, never splitting a surrogate pair. */
    private static List<String> splitLongLine(String line, int limit) {
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (line.length() - start > limit) {
            int end = start + limit;
            if (Character.isLowSurrogate(line.charAt(end))) {
                end--; // keep an emoji's two halves together
            }
            pieces.add(line.substring(start, end));
            start = end;
        }
        pieces.add(line.substring(start));
        return pieces;
    }

    /**
     * Posts a crash notice on the calling thread instead of the queue.
     *
     * <p>Called from a JVM shutdown hook, where the queue's daemon worker will not be scheduled
     * again — anything enqueued at that point is silently lost. Blocking is the only way the
     * message actually leaves the machine, so the timeouts here are deliberately short.
     */
    public void sendCrashBlocking() {
        JsonObject payload = base(config.serverName, null);

        JsonObject embed = new JsonObject();
        embed.addProperty("description", "💥 Server stopped **unexpectedly** (no clean shutdown)");
        embed.addProperty("color", COLOR_DEATH);

        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        payload.add("embeds", embeds);

        String webhook = config.mainWebhook();
        if (webhook.isBlank()) {
            return;
        }

        // One short attempt, NOT the usual retry loop. This runs inside a JVM shutdown hook, and
        // post()'s three tries with backoff and 15s timeouts could hold the process open for the
        // better part of a minute — long enough for a restart script to look hung, or for a
        // supervisor to SIGKILL us anyway.
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhook))
                    .timeout(Duration.ofSeconds(4))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Sculklink (Minecraft Fabric mod, 1.1.0)")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                    .build();
            http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            // Nothing useful to do — the JVM is on its way out.
            SculkLinkCore.LOGGER.warn("Could not report the crash to Discord: {}", e.getMessage());
        }
    }

    /**
     * Drains the queue before the JVM exits. Without this the "shutting down" message is
     * built and then thrown away when the process dies mid-request.
     */
    public void shutdown() {
        queue.shutdown();
        try {
            if (!queue.awaitTermination(10, TimeUnit.SECONDS)) {
                queue.shutdownNow();
            }
        } catch (InterruptedException e) {
            queue.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ---- Payload construction -------------------------------------------------------------

    private void sendPlayerEmbed(String description, int color, String playerName, String uuid) {
        sendPlayerEmbed(description, color, playerName, uuid, config.mainWebhook());
    }

    private void sendPlayerEmbed(String description, int color, String playerName, String uuid, String webhook) {
        enqueue(() -> {
            JsonObject payload = base(config.serverName, null);

            JsonObject embed = new JsonObject();
            embed.addProperty("description", description);
            embed.addProperty("color", color);

            // Small skin head next to the line — cheap, and makes a busy channel scannable.
            String avatar = avatarFor(playerName, uuid);
            if (avatar != null) {
                JsonObject thumbnail = new JsonObject();
                thumbnail.addProperty("url", avatar);
                embed.add("thumbnail", thumbnail);
            }

            JsonArray embeds = new JsonArray();
            embeds.add(embed);
            payload.add("embeds", embeds);
            return payload;
        }, webhook);
    }

    private void sendServerEmbed(String description, int color) {
        JsonObject payload = base(config.serverName, null);

        JsonObject embed = new JsonObject();
        embed.addProperty("description", description);
        embed.addProperty("color", color);

        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        payload.add("embeds", embeds);
        enqueue(payload);
    }

    private JsonObject base(String username, String avatarUrl) {
        JsonObject payload = new JsonObject();

        // Discord rejects an empty username outright, so an operator who blanks `server-name`
        // should fall back to the webhook's own configured name rather than get a 400.
        String name = truncate(sanitiseWebhookName(username), 80);
        if (!name.isBlank()) {
            payload.addProperty("username", name);
        }
        if (avatarUrl != null) {
            payload.addProperty("avatar_url", avatarUrl);
        }

        // Suppresses @everyone/@here/role pings for everything this mod posts. Without it any
        // player could type "@everyone" in-game and notify the whole Discord server.
        JsonObject allowedMentions = new JsonObject();
        allowedMentions.add("parse", new JsonArray());
        payload.add("allowed_mentions", allowedMentions);

        return payload;
    }

    /**
     * The player's head. A Bedrock player's real skin needs a web lookup, so this may block for
     * a few seconds the first time: call it on the sender thread only.
     */
    private String avatarFor(String playerName, String uuid) {
        String name = playerName == null ? "" : playerName;
        if (!config.bedrockAvatarUrl.isBlank() && BedrockSkins.isFloodgate(uuid)) {
            String texture = bedrockSkins.textureId(uuid);
            if (texture != null) {
                return config.bedrockAvatarUrl.replace("{texture}", texture)
                        .replace("{uuid}", uuid).replace("{name}", name);
            }
        }
        if (config.avatarUrl.isBlank()) {
            return null;
        }
        return config.avatarUrl.replace("{uuid}", uuid == null ? "" : uuid).replace("{name}", name);
    }

    // ---- Delivery (sender thread) ---------------------------------------------------------

    private void enqueue(JsonObject payload) {
        enqueue(payload, config.mainWebhook());
    }

    /** Posts to a specific webhook; join/leave, death and update cards may have their own channel. */
    private void enqueue(JsonObject payload, String webhook) {
        enqueue(() -> payload, webhook);
    }

    /**
     * Like {@link #enqueue(JsonObject, String)}, but the payload is built on the sender thread,
     * in order with everything else. For posts that may wait on a Bedrock skin lookup.
     */
    private void enqueue(Supplier<JsonObject> payload, String webhook) {
        if (webhook == null || webhook.isBlank()) {
            return;
        }
        try {
            queue.execute(() -> {
                String body;
                try {
                    body = payload.get().toString();
                } catch (RuntimeException e) {
                    SculkLinkCore.LOGGER.warn("Could not build a Discord post; it is skipped", e);
                    return;
                }
                post(webhook, body);
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Server is already shutting down and the queue is closed. Nothing to do.
        }
    }

    private void post(String webhook, String body) {
        // Discord answers 429 with how long to wait; three tries covers a burst without
        // letting a wedged channel back up the queue forever.
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(webhook))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "Sculklink (Minecraft Fabric mod, 1.1.0)")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> response = sendWithDeadline(request);
                int status = response.statusCode();

                if (status >= 200 && status < 300) {
                    return;
                }

                if (status == 429) {
                    long waitMillis = retryAfterMillis(response.body());
                    SculkLinkCore.LOGGER.warn("Discord rate limited us, waiting {}ms", waitMillis);
                    Thread.sleep(waitMillis);
                    continue;
                }

                if (status >= 500) {
                    Thread.sleep(1000L * (attempt + 1));
                    continue;
                }

                // 4xx that is not a rate limit: a bad or deleted webhook URL. Retrying will
                // not fix it, so say so plainly once and drop the message.
                SculkLinkCore.LOGGER.error("Discord rejected the webhook post (HTTP {}): {}",
                        status, truncate(response.body(), 300));
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IllegalArgumentException e) {
                // An unparseable URL. Config loading screens these out, so this is a backstop:
                // retrying cannot help, and the message would print the URL and its token.
                SculkLinkCore.LOGGER.error("A webhook URL could not be used; dropping the post");
                return;
            } catch (Exception e) {
                SculkLinkCore.LOGGER.warn("Failed to reach Discord ({}), attempt {}/3", e.getMessage(), attempt + 1);
                try {
                    Thread.sleep(1000L * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        SculkLinkCore.LOGGER.warn("Gave up posting to Discord after 3 attempts; that message is lost");
    }

    /**
     * Sends with a limit on the whole exchange. The request's own timeout stops counting once the
     * headers arrive, so a response whose body stalled would otherwise hold the one sender thread,
     * and every post queued behind it, indefinitely.
     */
    private HttpResponse<String> sendWithDeadline(HttpRequest request) throws Exception {
        CompletableFuture<HttpResponse<String>> call = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            return call.get(20, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        } finally {
            call.cancel(true); // no-op once complete; abandons the exchange after a timeout
        }
    }

    private static long retryAfterMillis(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (json.has("retry_after")) {
                return Math.min((long) (json.get("retry_after").getAsDouble() * 1000) + 100, 30_000L);
            }
        } catch (Exception ignored) {
            // Fall through to the default below.
        }
        return 2000L;
    }

    // ---- Text hygiene ---------------------------------------------------------------------

    /**
     * Escapes the message for Discord while turning {@code @name} into a real mention where the
     * name resolves. Mentions are substituted on the raw text and the literal runs escaped
     * around them — escaping first would put a backslash inside names containing underscores
     * and stop them matching at all.
     */
    private String renderContent(String message, List<String> users, List<String> roles) {
        MentionResolver resolver = this.mentionResolver;
        if (resolver == null || !config.relayMentions) {
            return escapeMarkdown(message);
        }

        StringBuilder out = new StringBuilder(message.length() + 16);
        Matcher matcher = MENTION.matcher(message);
        int last = 0;

        while (matcher.find()) {
            out.append(escapeMarkdown(message.substring(last, matcher.start())));

            // Trailing dots are almost always sentence punctuation rather than part of the
            // name, so try without them and hand the remainder back as literal text.
            String raw = matcher.group(1);
            String name = raw;
            while (name.endsWith(".")) {
                name = name.substring(0, name.length() - 1);
            }
            String trailing = raw.substring(name.length());

            String userId = name.isEmpty() ? null : resolver.userId(name);
            String roleId = (userId == null && !name.isEmpty()) ? resolver.roleId(name) : null;

            if (userId != null) {
                out.append("<@").append(userId).append('>').append(escapeMarkdown(trailing));
                users.add(userId);
            } else if (roleId != null) {
                out.append("<@&").append(roleId).append('>').append(escapeMarkdown(trailing));
                roles.add(roleId);
            } else {
                out.append(escapeMarkdown(matcher.group(0)));
            }
            last = matcher.end();
        }

        out.append(escapeMarkdown(message.substring(last)));
        return out.toString();
    }

    private static JsonArray toJsonArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    /**
     * Stops in-game text from being read as Discord markdown — otherwise a player typing
     * {@code *hi*} silently turns into italics, and underscores in names mangle the line.
     */
    static String escapeMarkdown(String input) {
        StringBuilder out = new StringBuilder(input.length() + 8);
        for (char c : input.toCharArray()) {
            if (c == '*' || c == '_' || c == '~' || c == '`' || c == '|' || c == '\\' || c == '>') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * Works around Discord rejecting webhook usernames that contain "discord" or "clyde".
     *
     * <p>Minecraft names are not subject to that rule, so a player called {@code Discordant}
     * would otherwise have every chat message bounced with a 400 while everyone else's worked —
     * a failure that looks like the bridge randomly ignoring one person. A zero-width space
     * breaks the substring match while leaving the name looking untouched.
     */
    static String sanitiseWebhookName(String name) {
        if (name == null) {
            return "";
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.contains("discord") && !lower.contains("clyde")) {
            return name;
        }
        return name.replaceAll("(?i)(d)(iscord)", "$1​$2")
                .replaceAll("(?i)(c)(lyde)", "$1​$2");
    }

    static String truncate(String input, int limit) {
        if (input == null || limit <= 0) {
            return "";
        }
        return input.length() <= limit ? input : input.substring(0, limit - 1) + "…";
    }
}
