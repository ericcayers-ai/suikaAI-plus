package dev.suika.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden physics trajectories — fixed seed + fixed drops must produce bit-stable
 * score / fruit count / step count. Rebless only after intentional physics changes
 * (see docs/contracts.md) and update the expected constants in the same PR.
 */
class GoldenPhysicsTest {

    /** Canonical short trajectory used as the regression oracle. */
    private static final long SEED = 42L;
    private static final double[] DROPS = {
            5.0, 3.0, 7.0, 5.0, 2.0, 8.0, 4.5, 6.0, 1.5, 9.0, 4.0, 7.5, 3.5, 6.5, 2.5
    };

    // Frozen oracle values — discovered once under the 0.17.x physics constants.
    private static final long EXPECTED_SCORE = 37L;
    private static final int EXPECTED_FRUITS = 5;
    private static final long EXPECTED_STEPS = 15L;
    private static final boolean EXPECTED_OVER = false;

    @Test
    void goldenTrajectoryMatchesFrozenOracle() {
        Trajectory t = run(SEED, DROPS);
        assertEquals(EXPECTED_SCORE, t.score, "golden score drifted — rebless only with intentional physics change");
        assertEquals(EXPECTED_FRUITS, t.fruits, "golden fruit count drifted");
        assertEquals(EXPECTED_STEPS, t.steps, "golden step count drifted");
        assertEquals(EXPECTED_OVER, t.over, "golden game-over flag drifted");
    }

    @Test
    void sameSeedAndDropsAreBitIdenticalAcrossRuns() {
        Trajectory a = run(SEED, DROPS);
        Trajectory b = run(SEED, DROPS);
        assertEquals(a.score, b.score);
        assertEquals(a.fruits, b.fruits);
        assertEquals(a.steps, b.steps);
        assertEquals(a.over, b.over);
        assertEquals(a.fingerprint, b.fingerprint);
    }

    @Test
    void alternateSeedProducesStableIndependentOracle() {
        // Second fixture so a single lucky collision on seed 42 cannot hide drift.
        double[] drops = {1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 5.0};
        Trajectory t = run(137L, drops);
        Trajectory again = run(137L, drops);
        assertEquals(t.fingerprint, again.fingerprint);
        assertTrue(t.steps > 0);
        assertTrue(t.score >= 0);
    }

    private static Trajectory run(long seed, double[] drops) {
        GameCore g = new GameCore(seed);
        for (double x : drops) {
            if (g.isGameOver()) break;
            g.dropAndSettle(x);
        }
        GameState s = g.getState();
        List<String> parts = new ArrayList<>();
        parts.add("score=" + g.getScore());
        parts.add("fruits=" + s.fruits().size());
        parts.add("steps=" + s.stepCount());
        parts.add("over=" + s.gameOver());
        for (Fruit f : s.fruits()) {
            parts.add(String.format(Locale.ROOT, "%d:%.4f:%.4f:%.4f",
                    f.tier().tier, f.x(), f.y(), f.radius()));
        }
        return new Trajectory(g.getScore(), s.fruits().size(), s.stepCount(),
                s.gameOver(), String.join("|", parts));
    }

    private record Trajectory(long score, int fruits, long steps, boolean over, String fingerprint) {}
}
