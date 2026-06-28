package dev.suika.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Compact replay format: seed + ordered action sequence.
 *
 * <p>Because the core is deterministic, replaying seed+actions against a fresh
 * {@link GameCore} reconstructs the full game exactly — no per-frame state needed.
 * A checksum sampled periodically lets consumers detect divergence.
 */
public final class ReplayLog {

    private final long         seed;
    private final List<Double> actions   = new ArrayList<>();
    private final List<Long>   checksums = new ArrayList<>();

    /** Periodic checksum interval (steps between score checksums). */
    private static final int CHECKSUM_INTERVAL = 10;

    public ReplayLog(long seed) { this.seed = seed; }

    /** Record an action and, every N steps, a score checksum. */
    public void record(double dropX, long score) {
        actions.add(dropX);
        if (actions.size() % CHECKSUM_INTERVAL == 0) checksums.add(score);
    }

    public long          seed()      { return seed; }
    public List<Double>  actions()   { return Collections.unmodifiableList(actions); }
    public List<Long>    checksums() { return Collections.unmodifiableList(checksums); }
    public int           length()    { return actions.size(); }

    /**
     * Replays this log against a fresh {@link GameCore} and returns the final state.
     * Throws {@link ReplayDivergedException} if a checksum mismatch is detected.
     */
    public GameState replay() {
        GameCore core = new GameCore(seed);
        int checksumIdx = 0;
        for (int i = 0; i < actions.size(); i++) {
            if (core.isGameOver()) break;
            StepResult r = core.dropAndSettle(actions.get(i));
            if ((i + 1) % CHECKSUM_INTERVAL == 0 && checksumIdx < checksums.size()) {
                long expected = checksums.get(checksumIdx++);
                long actual   = r.observation().score();
                if (expected != actual) {
                    throw new ReplayDivergedException(i + 1, expected, actual);
                }
            }
        }
        return core.getState();
    }

    public static final class ReplayDivergedException extends RuntimeException {
        public ReplayDivergedException(int step, long expected, long actual) {
            super("Replay diverged at step %d: expected score %d, got %d"
                    .formatted(step, expected, actual));
        }
    }
}
