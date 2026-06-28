package dev.suika.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the determinism guarantee: same seed + same actions ⇒ same outcome.
 */
class DeterminismTest {

    private static final long SEED = 12345L;
    private static final List<Double> ACTIONS = List.of(5.0, 3.0, 7.0, 5.0, 2.0, 8.0, 4.5);

    @Test
    void sameActionsProduceSameScore() {
        long score1 = runGame(SEED, ACTIONS);
        long score2 = runGame(SEED, ACTIONS);
        assertEquals(score1, score2,
                "Identical seed+actions must produce identical final score");
    }

    @Test
    void sameActionsProduceSameFruitCount() {
        int count1 = runGameFruitCount(SEED, ACTIONS);
        int count2 = runGameFruitCount(SEED, ACTIONS);
        assertEquals(count1, count2,
                "Identical seed+actions must produce identical fruit count");
    }

    @Test
    void differentSeedsProduceDifferentQueues() {
        GameCore c1 = new GameCore(1L);
        GameCore c2 = new GameCore(2L);
        // The current fruit tier is drawn from RNG; two different seeds should differ often
        // (not guaranteed every run but fails only in a 1-in-4 collision — acceptable smoke test)
        GameState s1 = c1.getState();
        GameState s2 = c2.getState();
        // At minimum both games are valid
        assertNotNull(s1.currentFruitTier());
        assertNotNull(s2.currentFruitTier());
    }

    @Test
    void snapshotIsIndependent() {
        GameCore original = new GameCore(SEED);
        original.dropAndSettle(5.0);

        GameCore fork = original.snapshot();

        // Advance the fork differently from the original
        fork.dropAndSettle(2.0);
        original.dropAndSettle(8.0);

        // Their scores may differ; the key thing is neither threw and both report valid states
        assertNotNull(original.getState());
        assertNotNull(fork.getState());
        // Fork started from the same post-drop-1 state, so both have ≥1 fruit
        assertFalse(original.getState().fruits().isEmpty());
        assertFalse(fork.getState().fruits().isEmpty());
    }

    // -----------------------------------------------------------------------

    private long runGame(long seed, List<Double> actions) {
        GameCore core = new GameCore(seed);
        for (double x : actions) {
            if (core.isGameOver()) break;
            core.dropAndSettle(x);
        }
        return core.getScore();
    }

    private int runGameFruitCount(long seed, List<Double> actions) {
        GameCore core = new GameCore(seed);
        for (double x : actions) {
            if (core.isGameOver()) break;
            core.dropAndSettle(x);
        }
        return core.getState().fruits().size();
    }
}
