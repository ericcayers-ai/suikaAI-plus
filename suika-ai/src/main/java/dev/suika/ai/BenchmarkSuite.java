package dev.suika.ai;

import dev.suika.core.GameCore;
import dev.suika.core.PhysicsConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Standardised benchmark for community leaderboard submissions (ROADMAP §XII).
 *
 * <p>Fixed seeds and episode caps make results across agents directly comparable.
 * Run with {@link #evaluate(AgentPlugin)} to produce a {@link LeaderboardEntry}.
 */
public final class BenchmarkSuite {

    /** Canonical seeds — never change these to keep historic results comparable. */
    public static final List<Long> STANDARD_SEEDS = List.of(1L, 42L, 137L, 999L, 31415L);

    /** Default episodes per seed — part of the frozen leaderboard contract. */
    public static final int DEFAULT_EPISODES_PER_SEED = 3;

    /** Default max steps per episode — part of the frozen leaderboard contract. */
    public static final int DEFAULT_MAX_STEPS = 500;

    /** Discrete bins used by the standard suite — aligned with ModelSlots.OUTPUT_BINS. */
    public static final int DEFAULT_ACTION_BINS = 32;

    private static final int EPISODES_PER_SEED = DEFAULT_EPISODES_PER_SEED;
    private static final int MAX_STEPS         = DEFAULT_MAX_STEPS;
    private static final int ACTION_BINS        = DEFAULT_ACTION_BINS;

    private final List<Long> seeds;
    private final int        episodesPerSeed;
    private final int        maxSteps;

    /** Standard suite using canonical seeds. */
    public BenchmarkSuite() {
        this(STANDARD_SEEDS, EPISODES_PER_SEED, MAX_STEPS);
    }

    public BenchmarkSuite(List<Long> seeds, int episodesPerSeed, int maxSteps) {
        this.seeds          = seeds;
        this.episodesPerSeed = episodesPerSeed;
        this.maxSteps       = maxSteps;
    }

    /**
     * Evaluate {@code agent} across all seeds × episodesPerSeed.
     * Returns a ready-to-submit {@link LeaderboardEntry}.
     */
    public LeaderboardEntry evaluate(AgentPlugin agent) {
        ActionSpec spec  = ActionSpec.discrete(ACTION_BINS);
        List<Double> all = new ArrayList<>();

        for (long seed : seeds) {
            // Re-run the SAME seed episodesPerSeed times. Agents with internal RNG
            // (MCTS rollouts) still vary across episodes; the physics/RNG seed of the
            // game itself stays fixed so averages cut agent-stochastic variance rather
            // than silently expanding the seed set.
            for (int ep = 0; ep < episodesPerSeed; ep++) {
                GameCore game = new GameCore(seed);
                int steps = 0;
                while (!game.isGameOver() && steps < maxSteps) {
                    Object action = agent.selectAction(game, spec);
                    double x = spec.toDropX(action,
                            PhysicsConfig.DROP_X_MIN, PhysicsConfig.DROP_X_MAX);
                    game.dropAndSettle(x);
                    steps++;
                }
                all.add((double) game.getScore());
            }
        }

        double mean = all.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = all.stream()
                .mapToDouble(s -> (s - mean) * (s - mean))
                .average().orElse(0);
        double std = Math.sqrt(variance);

        return new LeaderboardEntry(
                agent.id(),
                agent.displayName(),
                mean,
                std,
                all.size(),
                seeds,
                System.currentTimeMillis()
        );
    }
}