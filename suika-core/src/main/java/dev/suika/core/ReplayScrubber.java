package dev.suika.core;

/**
 * Deterministic replay scrubber — seek to step N of a {@link ReplayLog}.
 * {@link #stateAt(int)} replays from the seed up to that action index.
 */
public final class ReplayScrubber {

    private final ReplayLog log;
    private int cursor;

    public ReplayScrubber(ReplayLog log) {
        this.log = log;
        this.cursor = 0;
    }

    public ReplayLog log() { return log; }
    public int cursor() { return cursor; }
    public int length() { return log.length(); }

    public void seek(int step) {
        cursor = Math.max(0, Math.min(step, log.length()));
    }

    public void stepBy(int delta) { seek(cursor + delta); }

    /** Game state after applying the first {@code cursor} actions. */
    public GameState stateAtCursor() { return stateAt(cursor); }

    public GameState stateAt(int step) {
        int n = Math.max(0, Math.min(step, log.length()));
        GameCore core = new GameCore(log.seed());
        for (int i = 0; i < n; i++) {
            if (core.isGameOver()) break;
            core.dropAndSettle(log.actions().get(i));
        }
        return core.getState();
    }

    /** Serialise for {@code ~/.suikai/replays/*.suika-replay}. */
    public static String exportText(ReplayLog log) {
        StringBuilder sb = new StringBuilder("# suika-ai-plus replay v1\n");
        sb.append("seed=").append(log.seed()).append('\n');
        sb.append("actions=");
        for (int i = 0; i < log.actions().size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(log.actions().get(i));
        }
        sb.append('\n');
        return sb.toString();
    }

    public static ReplayLog importText(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("empty replay");
        long seed = 0;
        java.util.List<Double> actions = new java.util.ArrayList<>();
        for (String line : text.split("\\R")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("seed=")) seed = Long.parseLong(line.substring(5).trim());
            else if (line.startsWith("actions=")) {
                String body = line.substring(8).trim();
                if (!body.isEmpty()) {
                    for (String p : body.split(",")) actions.add(Double.parseDouble(p.trim()));
                }
            }
        }
        ReplayLog log = new ReplayLog(seed);
        for (double a : actions) log.record(a, 0L);
        return log;
    }
}
