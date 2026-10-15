package net.sculklink.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Finds the real skin of a Bedrock player who joined through Geyser and Floodgate.
 *
 * <p>Head services such as mc-heads.net look skins up by Java account, and a Bedrock player has
 * none, so every one of them showed up as Steve. Floodgate gives them a UUID whose low 64 bits
 * are their Xbox user id (XUID), and GeyserMC's global API maps an XUID to the skin it uploaded
 * to Mojang's texture server — the same lookup Geyser itself relies on to show Bedrock skins to
 * Java players.
 *
 * <p>Called from the sender thread, which keeps every Discord post in order, so a slow lookup
 * must never hold it for long. Each lookup runs asynchronously under a hard deadline, and a post
 * waits for one only briefly: past that it goes out with the head already known (or the ordinary
 * avatar), and the lookup carries on into the cache for the next post.
 */
final class BedrockSkins {
    /** Floodgate's UUIDs: the high 64 bits are zero, the low 64 bits are the XUID. */
    private static final Pattern FLOODGATE = Pattern.compile("00000000-0000-0000-[0-9a-f]{4}-[0-9a-f]{12}");

    private static final long HIT_TTL_MILLIS = Duration.ofHours(1).toMillis();
    /** Shorter, so a player whose skin GeyserMC has not seen yet gets it soon after. */
    private static final long MISS_TTL_MILLIS = Duration.ofMinutes(10).toMillis();
    /** Longest a post waits for a lookup that is under way. */
    private static final long WAIT_MILLIS = 2_000;
    /**
     * Hard limit on a whole lookup. {@code HttpRequest.timeout} alone stops counting once the
     * headers arrive, so a server that sent headers and then stalled would hang it forever.
     */
    private static final long DEADLINE_MILLIS = 5_000;

    /** A known texture, or null for a player GeyserMC has no skin for. */
    private record Cached(String textureId, long expiresAt) {
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .build();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();
    /** Set while lookups are failing, so an outage is logged once rather than per player. */
    private final AtomicBoolean failing = new AtomicBoolean();

    static boolean isFloodgate(String uuid) {
        return uuid != null && FLOODGATE.matcher(uuid.toLowerCase(Locale.ROOT)).matches();
    }

    static long xuid(String uuid) {
        return Long.parseUnsignedLong(uuid.substring(19).replace("-", ""), 16);
    }

    /**
     * The Mojang texture id of this Bedrock player's skin, or null if it is unknown — the caller
     * then uses the ordinary avatar. Waits at most {@link #WAIT_MILLIS}.
     */
    String textureId(String uuid) {
        Cached cached = cache.get(uuid);
        if (cached != null && cached.expiresAt() > System.currentTimeMillis()) {
            return cached.textureId();
        }
        String stale = cached == null ? null : cached.textureId();

        CompletableFuture<String> lookup = inFlight.get(uuid);
        if (lookup == null) {
            CompletableFuture<String> fresh = new CompletableFuture<>();
            lookup = inFlight.putIfAbsent(uuid, fresh);
            if (lookup == null) {
                lookup = fresh;
                start(uuid, fresh);
            }
        }
        try {
            String texture = lookup.get(WAIT_MILLIS, TimeUnit.MILLISECONDS);
            return texture != null ? texture : stale;
        } catch (TimeoutException | ExecutionException | CancellationException e) {
            return stale; // still running or failed: this post keeps the head it had, if any
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return stale;
        }
    }

    private void start(String uuid, CompletableFuture<String> result) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.geysermc.org/v2/skin/" + Long.toUnsignedString(xuid(uuid))))
                .timeout(Duration.ofMillis(DEADLINE_MILLIS))
                .header("User-Agent", "Sculklink (Minecraft server mod)")
                .GET()
                .build();
        CompletableFuture<HttpResponse<String>> call = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        // Cancelling completes the call at the deadline whatever state the body is in, and asks
        // the client to abandon the exchange.
        CompletableFuture.delayedExecutor(DEADLINE_MILLIS, TimeUnit.MILLISECONDS).execute(() -> call.cancel(true));

        call.whenComplete((response, error) -> {
            String failure = error != null ? describe(error)
                    : response.statusCode() != 200 ? "HTTP " + response.statusCode() : null;
            String texture = null;
            if (failure == null) {
                try {
                    texture = parse(response.body());
                } catch (RuntimeException e) {
                    failure = "unreadable answer"; // not the same as "no skin": keep the head we had
                }
            }
            long now = System.currentTimeMillis();
            if (failure == null) {
                cache.put(uuid, new Cached(texture, now + (texture == null ? MISS_TTL_MILLIS : HIT_TTL_MILLIS)));
                if (failing.compareAndSet(true, false)) {
                    SculkLinkCore.LOGGER.info("Bedrock skin lookups are working again");
                }
            } else {
                // Keep a head that was already known; only try again in a while.
                Cached old = cache.get(uuid);
                cache.put(uuid, new Cached(old == null ? null : old.textureId(), now + MISS_TTL_MILLIS));
                if (failing.compareAndSet(false, true)) {
                    SculkLinkCore.LOGGER.warn("Bedrock skin lookups are failing ({}); Bedrock players "
                            + "keep their last known head or the ordinary avatar until they recover", failure);
                }
            }
            inFlight.remove(uuid, result);
            result.complete(texture);
        });
    }

    /**
     * The texture id, or null when GeyserMC has never seen this player (it answers {@code {}}).
     * Throws on an answer that is not a JSON object or has a texture id that is not a hex hash, so
     * a broken response counts as a failure rather than as "no skin".
     */
    private static String parse(String body) {
        JsonElement texture = JsonParser.parseString(body).getAsJsonObject().get("texture_id");
        if (texture == null) {
            return null;
        }
        String id = texture.getAsString();
        // Only ever a hex hash; anything else is not going into a URL.
        if (!id.matches("[0-9a-f]{32,128}")) {
            throw new IllegalStateException("unexpected texture id");
        }
        return id;
    }

    private static String describe(Throwable error) {
        if (error instanceof CancellationException) {
            return "no answer within " + DEADLINE_MILLIS / 1000 + "s";
        }
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        return cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
    }
}
