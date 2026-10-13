package net.sculklink.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.sculklink.core.BridgePlatform.InstalledMod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Spots what changed between two server starts — the game version and the installed mods — so
 * an update reaches Discord without anyone remembering to announce it.
 *
 * <p>What the server looked like last time is kept beside the config in
 * {@code sculklink-installed.json}. With no such file there is nothing to compare against, so a
 * first start only records the baseline: a card claiming every mod was just "added" would be noise.
 */
final class UpdateTracker {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static final String FILE_NAME = "sculklink-installed.json";

    /** The server as it was at one start. Sorted, so the file diffs cleanly by eye. */
    record Snapshot(String gameVersion, TreeMap<String, InstalledMod> mods) {
    }

    private UpdateTracker() {
    }

    /**
     * Compares this start with the last one and records this one.
     *
     * @return the card text, or {@code null} on a first start or when nothing changed
     */
    static String check(Path configDir, String gameVersion, Map<String, InstalledMod> mods) {
        Path file = configDir.resolve(FILE_NAME);
        Snapshot now = new Snapshot(gameVersion, new TreeMap<>(mods));
        Snapshot before = read(file);

        String changes = before == null ? null : describe(before, now);
        if (before == null || changes != null) {
            write(file, now);
        }
        if (before == null) {
            SculkLinkCore.LOGGER.info("Recorded {} mods as the baseline for update cards", mods.size());
        }
        return changes;
    }

    /** Card text for the difference, or {@code null} when there is none. */
    static String describe(Snapshot before, Snapshot now) {
        List<String> added = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<String> removed = new ArrayList<>();

        for (Map.Entry<String, InstalledMod> e : sortedByName(now.mods())) {
            InstalledMod was = before.mods().get(e.getKey());
            InstalledMod is = e.getValue();
            if (was == null) {
                added.add(name(is) + " " + version(is.version()));
            } else if (!Objects.equals(was.version(), is.version())) {
                updated.add(name(is) + " " + version(was.version()) + " → " + version(is.version()));
            }
        }
        for (Map.Entry<String, InstalledMod> e : sortedByName(before.mods())) {
            if (!now.mods().containsKey(e.getKey())) {
                removed.add(name(e.getValue()));
            }
        }

        boolean gameChanged = now.gameVersion() != null && before.gameVersion() != null
                && !now.gameVersion().equals(before.gameVersion());
        if (!gameChanged && added.isEmpty() && updated.isEmpty() && removed.isEmpty()) {
            return null;
        }

        StringBuilder out = new StringBuilder("🔧 **Server updated**");
        if (gameChanged) {
            out.append("\n**Minecraft** ").append(version(before.gameVersion()))
                    .append(" → ").append(version(now.gameVersion()));
        }
        section(out, "Added", added);
        section(out, "Updated", updated);
        section(out, "Removed", removed);
        return out.toString();
    }

    private static void section(StringBuilder out, String title, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        out.append("\n\n**").append(title).append("**");
        for (String line : lines) {
            out.append("\n• ").append(line);
        }
    }

    private static List<Map.Entry<String, InstalledMod>> sortedByName(Map<String, InstalledMod> mods) {
        List<Map.Entry<String, InstalledMod>> entries = new ArrayList<>(mods.entrySet());
        entries.sort(Comparator.comparing(e -> name(e.getValue()).toLowerCase(Locale.ROOT)));
        return entries;
    }

    private static String name(InstalledMod mod) {
        return DiscordSender.escapeMarkdown(mod.name() == null ? "?" : mod.name());
    }

    private static String version(String version) {
        return "`" + (version == null ? "?" : version.replace("`", "'")) + "`";
    }

    // ---- Load / save -----------------------------------------------------------------------

    /** The last start, or {@code null} if there is none to compare against. */
    private static Snapshot read(Path file) {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            Snapshot snapshot = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Snapshot.class);
            if (snapshot == null || snapshot.mods() == null) {
                return null;
            }
            return snapshot;
        } catch (IOException | JsonParseException e) {
            // Only a record of the last start: starting over from a fresh baseline loses one card at most.
            SculkLinkCore.LOGGER.warn("Could not read {} ({}); recording a fresh baseline", file, e.getMessage());
            return null;
        }
    }

    private static void write(Path file, Snapshot snapshot) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(snapshot) + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            SculkLinkCore.LOGGER.error("Could not write {}: {}", file, e.getMessage());
        }
    }
}
