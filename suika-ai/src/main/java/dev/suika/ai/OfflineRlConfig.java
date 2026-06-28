package dev.suika.ai;

/**
 * Configuration for offline RL algorithms (CQL, IQL, Decision Transformer) — ROADMAP §IV.8.
 *
 * <p>Offline RL learns from a fixed dataset of recorded games without further
 * environment interaction. Conservative Q-Learning (CQL) avoids over-valuing
 * unseen actions. Decision Transformer frames RL as return-conditioned
 * sequence modeling — "play to reach score X."
 */
public record OfflineRlConfig(
        String method,           // "cql" | "iql" | "decision_transformer"
        int    hiddenDim,
        int    numLayers,
        double learningRate,
        int    batchSize,
        int    trainingSteps,
        double cqlAlpha,         // CQL conservatism weight (ignored for DT/IQL)
        int    contextLength     // Decision Transformer sequence length
) {
    public static OfflineRlConfig cqlDefaults() {
        return new OfflineRlConfig("cql", 256, 3, 3e-4, 256, 100_000, 5.0, 1);
    }

    public static OfflineRlConfig iqlDefaults() {
        return new OfflineRlConfig("iql", 256, 3, 3e-4, 256, 100_000, 0.0, 1);
    }

    public static OfflineRlConfig decisionTransformerDefaults() {
        return new OfflineRlConfig("decision_transformer", 128, 3, 1e-4, 64, 10_000, 0.0, 20);
    }
}
