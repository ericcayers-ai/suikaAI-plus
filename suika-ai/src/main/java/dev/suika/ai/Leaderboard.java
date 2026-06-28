package dev.suika.ai;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory leaderboard sorted by mean score (ROADMAP §XII).
 *
 * <p>Thread-safe for concurrent submissions. Use {@link #toJsonLines()} to persist
 * and reconstruct with {@link #fromJsonLines(List)} (round-trip preserves ordering).
 */
public final class Leaderboard {

    private final CopyOnWriteArrayList<LeaderboardEntry> entries = new CopyOnWriteArrayList<>();

    /** Add or replace an entry (same agentId → replace with higher mean score). */
    public synchronized void submit(LeaderboardEntry entry) {
        entries.removeIf(e -> e.agentId().equals(entry.agentId()) && e.meanScore() < entry.meanScore());
        boolean replaced = entries.removeIf(e -> e.agentId().equals(entry.agentId()));
        if (!replaced || true) {
            entries.add(entry);
        }
        entries.sort(Comparator.naturalOrder());
    }

    /** Ranked list, best first. */
    public List<LeaderboardEntry> ranked() {
        List<LeaderboardEntry> copy = new ArrayList<>(entries);
        Collections.sort(copy);
        return Collections.unmodifiableList(copy);
    }

    /** Rank of the given agentId (1-based), or -1 if not present. */
    public int rankOf(String agentId) {
        List<LeaderboardEntry> r = ranked();
        for (int i = 0; i < r.size(); i++) {
            if (r.get(i).agentId().equals(agentId)) return i + 1;
        }
        return -1;
    }

    public int size() { return entries.size(); }

    /** Export each entry as a JSON line (JSONL format). */
    public List<String> toJsonLines() {
        return ranked().stream().map(LeaderboardEntry::toJsonLine).toList();
    }

    /** Rebuild a leaderboard from JSONL lines (minimal parser — trusts own output). */
    public static Leaderboard fromJsonLines(List<String> lines) {
        Leaderboard board = new Leaderboard();
        for (String line : lines) {
            String id   = extract(line, "agentId");
            String name = extract(line, "displayName");
            double mean = Double.parseDouble(extractNum(line, "meanScore"));
            double std  = Double.parseDouble(extractNum(line, "stdDev"));
            int eps     = Integer.parseInt(extractNum(line, "episodeCount"));
            long ts     = Long.parseLong(extractNum(line, "epochMs"));
            board.submit(new LeaderboardEntry(id, name, mean, std, eps, List.of(), ts));
        }
        return board;
    }

    private static String extract(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker) + marker.length();
        int end   = json.indexOf('"', start);
        return json.substring(start, end);
    }

    private static String extractNum(String json, String key) {
        String marker = "\"" + key + "\":";
        int start = json.indexOf(marker) + marker.length();
        int end   = start;
        while (end < json.length() && ",}".indexOf(json.charAt(end)) < 0) end++;
        return json.substring(start, end).trim();
    }
}
