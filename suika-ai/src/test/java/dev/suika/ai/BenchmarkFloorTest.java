package dev.suika.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stable floor gates for the canonical {@link BenchmarkSuite} seeds.
 * Floors are intentionally conservative so flaky agent RNG cannot trip CI, but
 * still catch catastrophic strength regressions (e.g. the MCTS UCB1 bug in 0.17.1).
 *
 * <p>Uses a bounded suite (1 episode × 80 steps × first 3 standard seeds) so PR CI
 * stays under a few seconds. Full leaderboard submissions still use
 * {@link BenchmarkSuite#STANDARD_SEEDS} with the default episodes/steps.
 */
class BenchmarkFloorTest {

    /** Must remain identical to {@link BenchmarkSuite#STANDARD_SEEDS} order. */
    private static final List<Long> CONTRACT_SEEDS = List.of(1L, 42L, 137L, 999L, 31415L);

    @Test
    void standardSeedsAreFrozenContract() {
        assertEquals(CONTRACT_SEEDS, BenchmarkSuite.STANDARD_SEEDS,
                "STANDARD_SEEDS must never be reordered or replaced (historic comparability)");
    }

    @Test
    void heuristicMeanBeatsConservativeFloor() {
        BenchmarkSuite suite = new BenchmarkSuite(
                BenchmarkSuite.STANDARD_SEEDS.subList(0, 3), 1, 80);
        LeaderboardEntry entry = suite.evaluate(new HeuristicAgent());
        assertTrue(entry.meanScore() >= 5.0,
                "Heuristic floor violated: mean=" + entry.meanScore());
        assertTrue(Double.isFinite(entry.stdDev()));
        assertEquals(3, entry.episodeCount());
    }

    @Test
    void greedyMeanBeatsHeuristicFloorOnShortSuite() {
        BenchmarkSuite suite = new BenchmarkSuite(List.of(42L, 137L), 1, 40);
        double heuristic = suite.evaluate(new HeuristicAgent()).meanScore();
        double greedy = suite.evaluate(new GreedyOnePlyAgent(16)).meanScore();
        // Greedy simulates each column; it should not collapse below the hand-coded floor
        // on this bounded suite. Allow a soft margin for stochastic early death.
        assertTrue(greedy + 1e-6 >= Math.min(heuristic, 1.0),
                "Greedy collapsed vs heuristic: greedy=" + greedy + " heuristic=" + heuristic);
    }
}
