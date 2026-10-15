package net.sculklink.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One player's deaths, as vanilla's own statistics record them.
 *
 * <p>Read from the stats files rather than counted by this mod, so the numbers include every
 * death since the world began — not just the ones since Sculklink was installed — and agree
 * with the game's own Statistics screen.
 *
 * @param ticksSinceDeath play time since the last death, or since first joining if never
 * @param killedBy        entity type id (e.g. {@code minecraft:zombie}) to how often it killed them.
 *                        Deaths with no killer — falls, drowning, lava — are not in here.
 */
public record DeathStats(String name, String uuid, int deaths, long ticksSinceDeath,
                         Map<String, Integer> killedBy) {

    private static final String CUSTOM = "minecraft:custom";
    private static final String KILLED_BY = "minecraft:killed_by";

    /**
     * Reads every stats file in the folder. A stats file is only written when the player is
     * saved, so platforms save online players' stats first, or their latest deaths are missing.
     *
     * <p>A file that cannot be read is skipped rather than failing the whole leaderboard.
     *
     * @param statsDir   the world's per-player stats folder
     * @param userCache  the server's {@code usercache.json}, for names of players who are offline
     * @param knownNames uuid to name for players the server knows right now; wins over the cache
     */
    public static List<DeathStats> readAll(Path statsDir, Path userCache, Map<String, String> knownNames) {
        Map<String, String> names = new HashMap<>(readUserCache(userCache));
        names.putAll(knownNames);

        List<DeathStats> out = new ArrayList<>();
        if (!Files.isDirectory(statsDir)) {
            return out;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(statsDir, "*.json")) {
            for (Path file : files) {
                String fileName = file.getFileName().toString();
                String uuid = fileName.substring(0, fileName.length() - ".json".length());
                if (!isUuid(uuid)) {
                    continue;
                }
                DeathStats stats = readOne(file, uuid, names.getOrDefault(uuid, unknownName(uuid)));
                if (stats != null) {
                    out.add(stats);
                }
            }
        } catch (IOException e) {
            SculkLinkCore.LOGGER.warn("Could not list player stats in {}: {}", statsDir, e.getMessage());
        }
        return out;
    }

    private static DeathStats readOne(Path file, String uuid, String name) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject stats = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("stats");
            if (stats == null) {
                return null;
            }
            JsonObject custom = object(stats, CUSTOM);
            int deaths = intValue(custom, "minecraft:deaths");
            long sinceDeath = intValue(custom, "minecraft:time_since_death");

            Map<String, Integer> killedBy = new HashMap<>();
            JsonObject killers = object(stats, KILLED_BY);
            if (killers != null) {
                for (Map.Entry<String, JsonElement> entry : killers.entrySet()) {
                    int count = intValue(killers, entry.getKey());
                    if (count > 0) {
                        killedBy.put(entry.getKey(), count);
                    }
                }
            }
            return new DeathStats(name, uuid, deaths, sinceDeath, Map.copyOf(killedBy));
        } catch (IOException | RuntimeException e) {
            SculkLinkCore.LOGGER.warn("Skipping unreadable stats file {}: {}", file.getFileName(), e.getMessage());
            return null;
        }
    }

    /** uuid to name from {@code usercache.json}, a JSON array of {@code {name, uuid, expiresOn}}. */
    private static Map<String, String> readUserCache(Path userCache) {
        Map<String, String> names = new HashMap<>();
        if (userCache == null || !Files.isRegularFile(userCache)) {
            return names;
        }
        try (Reader reader = Files.newBufferedReader(userCache, StandardCharsets.UTF_8)) {
            for (JsonElement element : JsonParser.parseReader(reader).getAsJsonArray()) {
                if (element.isJsonObject()) {
                    JsonObject entry = element.getAsJsonObject();
                    if (entry.has("uuid") && entry.has("name")) {
                        names.put(entry.get("uuid").getAsString(), entry.get("name").getAsString());
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            SculkLinkCore.LOGGER.warn("Could not read {}; offline players show by id: {}",
                    userCache.getFileName(), e.getMessage());
        }
        return names;
    }

    /**
     * A stand-in for a player missing from the cache. Every Floodgate UUID starts with zeros, so
     * those are told apart by their end instead.
     */
    private static String unknownName(String uuid) {
        return BedrockSkins.isFloodgate(uuid) ? "Bedrock-" + uuid.substring(uuid.length() - 8) : uuid.substring(0, 8);
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent == null ? null : parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static int intValue(JsonObject parent, String key) {
        JsonElement value = parent == null ? null : parent.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return 0;
        }
        return value.getAsInt();
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return value.length() == 36;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
