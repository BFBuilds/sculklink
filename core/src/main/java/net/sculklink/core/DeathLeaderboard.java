package net.sculklink.core;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders {@link DeathStats} as a ranked leaderboard: a Discord embed for {@code !deaths} and
 * chat lines for {@code /deaths}. Pure formatting, so both answers always agree.
 */
public final class DeathLeaderboard {
    /** Players ranked by name; everyone past this is summed up in one line. */
    static final int MAX_RANKED = 10;
    /** Deathless players named before the rest are counted instead. */
    static final int MAX_CLEAN_NAMED = 15;

    static final int COLOR = 0x992D22;

    private static final String[] MEDALS = {"🥇", "🥈", "🥉"};
    private static final String[] RANK_COLOURS = {"§6", "§f", "§c"};

    private DeathLeaderboard() {
    }

    /** Players who have died, most deaths first, then by name. */
    static List<DeathStats> ranked(List<DeathStats> all) {
        return all.stream()
                .filter(s -> s.deaths() > 0)
                .sorted(Comparator.comparingInt(DeathStats::deaths).reversed()
                        .thenComparing(DeathStats::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** Players who have never died, by name. */
    static List<DeathStats> clean(List<DeathStats> all) {
        return all.stream()
                .filter(s -> s.deaths() == 0)
                .sorted(Comparator.comparing(DeathStats::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** The player at the top of the board, whose head the Discord card shows, or null. */
    public static DeathStats leader(List<DeathStats> all) {
        List<DeathStats> ranked = ranked(all);
        return ranked.isEmpty() ? null : ranked.get(0);
    }

    /**
     * Standard competition ranking: two players on 3 deaths are both 1st, and the next is 3rd.
     * Index {@code i} of the result is the rank of {@code ranked.get(i)}.
     */
    static int[] ranks(List<DeathStats> ranked) {
        int[] ranks = new int[ranked.size()];
        for (int i = 0; i < ranked.size(); i++) {
            ranks[i] = i > 0 && ranked.get(i).deaths() == ranked.get(i - 1).deaths() ? ranks[i - 1] : i + 1;
        }
        return ranks;
    }

    // ---- Discord ----------------------------------------------------------------------------

    public static JsonObject embed(String serverName, List<DeathStats> all) {
        List<DeathStats> ranked = ranked(all);
        int[] ranks = ranks(ranked);
        StringBuilder text = new StringBuilder();

        if (ranked.isEmpty()) {
            text.append("Nobody has died yet. 😇\n");
        }
        for (int i = 0; i < Math.min(ranked.size(), MAX_RANKED); i++) {
            DeathStats s = ranked.get(i);
            int rank = ranks[i];
            text.append(rank <= MEDALS.length ? MEDALS[rank - 1] : "`#" + rank + "`")
                    .append(" **").append(DiscordSender.escapeMarkdown(s.name())).append("** — **")
                    .append(s.deaths()).append("** ").append(s.deaths() == 1 ? "death" : "deaths");
            String nemesis = nemesis(s);
            if (nemesis != null) {
                text.append(" · *nemesis: ").append(DiscordSender.escapeMarkdown(nemesis)).append('*');
            }
            text.append('\n');
        }
        if (ranked.size() > MAX_RANKED) {
            int rest = ranked.subList(MAX_RANKED, ranked.size()).stream().mapToInt(DeathStats::deaths).sum();
            text.append("…and ").append(ranked.size() - MAX_RANKED).append(" more with ")
                    .append(rest).append(rest == 1 ? " death" : " deaths").append('\n');
        }

        List<DeathStats> clean = clean(all);
        if (!clean.isEmpty() && !ranked.isEmpty()) {
            text.append("\n😇 **Still clean:** ")
                    .append(DiscordSender.escapeMarkdown(nameList(clean))).append('\n');
        }

        JsonObject embed = new JsonObject();
        embed.addProperty("title", "💀 " + serverName + " — Wall of Shame");
        embed.addProperty("description", DiscordSender.truncate(text.toString().strip(), 4000));
        embed.addProperty("color", COLOR);

        JsonObject footer = new JsonObject();
        footer.addProperty("text", footerText(all));
        embed.add("footer", footer);
        return embed;
    }

    // ---- In-game ----------------------------------------------------------------------------

    /** The board as chat lines, using section-sign colour codes. Kept to the BMP: the game's font has no emoji. */
    public static List<String> chatLines(String serverName, List<DeathStats> all) {
        List<DeathStats> ranked = ranked(all);
        int[] ranks = ranks(ranked);
        List<String> lines = new ArrayList<>();

        lines.add("§4§l☠ Wall of Shame §r§7— " + serverName);
        if (ranked.isEmpty()) {
            lines.add("§aNobody has died yet.");
        }
        for (int i = 0; i < Math.min(ranked.size(), MAX_RANKED); i++) {
            DeathStats s = ranked.get(i);
            int rank = ranks[i];
            String colour = rank <= RANK_COLOURS.length ? RANK_COLOURS[rank - 1] : "§8";
            StringBuilder line = new StringBuilder()
                    .append(colour).append('#').append(rank).append(" §f").append(s.name())
                    .append(" §7— §c").append(s.deaths()).append(s.deaths() == 1 ? " death" : " deaths");
            String nemesis = nemesis(s);
            if (nemesis != null) {
                line.append(" §8(nemesis: ").append(nemesis).append(')');
            }
            lines.add(line.toString());
        }
        if (ranked.size() > MAX_RANKED) {
            lines.add("§8…and " + (ranked.size() - MAX_RANKED) + " more");
        }
        List<DeathStats> clean = clean(all);
        if (!clean.isEmpty() && !ranked.isEmpty()) {
            lines.add("§aStill clean: §f" + nameList(clean));
        }
        lines.add("§8" + footerText(all));
        return lines;
    }

    // ---- Pieces -------------------------------------------------------------------------------

    /**
     * Whatever killed them most, e.g. "Zombie ×2" or "Skeleton & Zombie ×2" on a tie. Null when
     * no mob ever has: falls, lava and drowning have no killer in the stats.
     */
    static String nemesis(DeathStats s) {
        int top = s.killedBy().values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (top == 0) {
            return null;
        }
        List<String> killers = s.killedBy().entrySet().stream()
                .filter(e -> e.getValue() == top)
                .map(Map.Entry::getKey)
                .map(DeathLeaderboard::mobName)
                .sorted()
                .toList();
        String who = killers.size() > 2 ? killers.get(0) + " & " + (killers.size() - 1) + " others"
                : String.join(" & ", killers);
        return top > 1 ? who + " ×" + top : who;
    }

    /** {@code minecraft:cave_spider} to "Cave Spider". Modded ids lose their namespace the same way. */
    static String mobName(String id) {
        String path = id.substring(id.indexOf(':') + 1);
        StringBuilder out = new StringBuilder();
        for (String word : path.split("[_/]")) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.length() == 0 ? id : out.toString();
    }

    private static String nameList(List<DeathStats> players) {
        List<String> names = players.stream().map(DeathStats::name).toList();
        if (names.size() <= MAX_CLEAN_NAMED) {
            return String.join(", ", names);
        }
        return String.join(", ", names.subList(0, MAX_CLEAN_NAMED)) + " +" + (names.size() - MAX_CLEAN_NAMED) + " more";
    }

    private static String footerText(List<DeathStats> all) {
        int total = all.stream().mapToInt(DeathStats::deaths).sum();
        StringBuilder footer = new StringBuilder().append(total).append(total == 1 ? " death" : " deaths").append(" total");
        all.stream().max(Comparator.comparingLong(DeathStats::ticksSinceDeath)).ifPresent(s -> {
            if (s.ticksSinceDeath() >= 20 * 60) { // "0m" would read as a bug
                footer.append(" · longest alive: ").append(s.name())
                        .append(" (").append(playTime(s.ticksSinceDeath())).append(')');
            }
        });
        return footer.toString();
    }

    /** Ticks of play as "2d 4h", "3h 12m" or "45m". */
    static String playTime(long ticks) {
        long minutes = ticks / 20 / 60;
        long days = minutes / 1440;
        long hours = (minutes % 1440) / 60;
        long mins = minutes % 60;
        if (days > 0) {
            return String.format(Locale.ROOT, "%dd %dh", days, hours);
        }
        if (hours > 0) {
            return String.format(Locale.ROOT, "%dh %dm", hours, mins);
        }
        return mins + "m";
    }
}
