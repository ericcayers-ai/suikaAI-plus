package dev.suika.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Physics golden-fixture verification / rebless tooling.
 * Never silently mutates {@link PhysicsConfig}; operators rebless fixtures in tests.
 */
public final class PhysicsGoldenTool {

    public record Snapshot(long seed, long score, int fruits, long steps, boolean over, String fingerprint) {}

    private PhysicsGoldenTool() {}

    public static Snapshot run(long seed, double[] drops) {
        GameCore g = new GameCore(seed);
        for (double x : drops) {
            if (g.isGameOver()) break;
            g.dropAndSettle(x);
        }
        GameState s = g.getState();
        StringBuilder fp = new StringBuilder();
        fp.append("score=").append(g.getScore())
                .append("|fruits=").append(s.fruits().size())
                .append("|steps=").append(s.stepCount())
                .append("|over=").append(s.gameOver());
        for (Fruit f : s.fruits()) {
            fp.append('|').append(String.format(Locale.ROOT, "%d:%.4f:%.4f:%.4f",
                    f.tier().tier, f.x(), f.y(), f.radius()));
        }
        return new Snapshot(seed, g.getScore(), s.fruits().size(), s.stepCount(),
                s.gameOver(), fp.toString());
    }

    /** Canonical short trajectory matching {@code GoldenPhysicsTest}. */
    public static final long CANONICAL_SEED = 42L;
    public static final double[] CANONICAL_DROPS = {
            5.0, 3.0, 7.0, 5.0, 2.0, 8.0, 4.5, 6.0, 1.5, 9.0, 4.0, 7.5, 3.5, 6.5, 2.5
    };

    public static Snapshot canonical() { return run(CANONICAL_SEED, CANONICAL_DROPS); }

    /**
     * Writes a rebless snippet for GoldenPhysicsTest expected constants.
     * Operators must paste intentionally — this never edits sources by itself.
     */
    public static String reblessSnippet(Snapshot snap) {
        return "// REBLESS — paste into GoldenPhysicsTest after intentional physics change\n"
                + "private static final long EXPECTED_SCORE = " + snap.score() + "L;\n"
                + "private static final int EXPECTED_FRUITS = " + snap.fruits() + ";\n"
                + "private static final long EXPECTED_STEPS = " + snap.steps() + "L;\n"
                + "private static final boolean EXPECTED_OVER = " + snap.over() + ";\n";
    }

    public static void writeReblessFile(Path out) throws Exception {
        Files.createDirectories(out.getParent());
        Files.writeString(out, reblessSnippet(canonical()), StandardCharsets.UTF_8);
    }
}
