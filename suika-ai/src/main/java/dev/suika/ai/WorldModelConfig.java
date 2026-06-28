package dev.suika.ai;

/**
 * Configuration for learned world models (MuZero / Dreamer) — ROADMAP §IV.3.
 *
 * <p>World models learn the latent dynamics of Suika from observations,
 * enabling "imagination" rollouts without the true simulator. The interesting
 * ablation here: compare the world model's prediction accuracy against the
 * true simulator — a built-in honesty check available because we own the sim.
 */
public record WorldModelConfig(
        String type,           // "muzero" | "dreamer"
        int    latentDim,
        int    recurrentDim,
        int    imagineHorizon,
        double learningRate,
        int    batchSize,
        int    burnInLength
) {
    public static WorldModelConfig muZeroDefaults() {
        return new WorldModelConfig("muzero", 256, 512, 5, 1e-4, 128, 5);
    }

    public static WorldModelConfig dreamerDefaults() {
        return new WorldModelConfig("dreamer", 230, 600, 15, 6e-4, 50, 5);
    }
}
