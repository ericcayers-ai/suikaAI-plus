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

    private static final int EPISODES_PER_SEED = 3;
    private static final int MAX_STEPS         = 500;
    private static final int ACTION_BINS        = 32;

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
            for (int ep = 0; ep < episodesPerSeed; ep++) {
                GameCore game = new GameCore(seed + ep);
                int steps = 0;
                while (!game.isGameOver() && steps < maxSteps) {
                    // FIX: Pass live GameCore directly
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