package dev.suika.dash;

import java.util.Map;

/**
 * Connects neuroevolution trainers to the {@link DashboardRegistry}.
 * Trainers call {@link #logGeneration} after each generation update.
 */
public final class EvolutionMetricsLogger {

    private final RunMetrics metrics;

    public EvolutionMetricsLogger(String algorithmName) {
        this.metrics = DashboardRegistry.get().createRun(algorithmName);
    }

    public void logGeneration(int generation,
                              double bestFitness,
                              double meanFitness,
                              double diversity) {
        metrics.record(generation, Map.of(
                "fitness/best",  bestFitness,
                "fitness/mean",  meanFitness,
                "population/diversity", diversity
        ));
    }

    public void logEpisode(int step, long score, long length) {
        metrics.record(step, Map.of(
                "episode/score",  (double) score,
                "episode/length", (double) length
        ));
    }

    public RunMetrics metrics() { return metrics; }
}
