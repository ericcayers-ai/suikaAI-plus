package dev.suika.ai;

/**
 * Configuration for the Diffusion Policy (ROADMAP §IV.11).
 *
 * <p>The diffusion policy represents the action distribution as a denoising
 * process over the action space conditioned on the board observation. It
 * excels at capturing multimodal action distributions — when two drop positions
 * are equally good, it can represent both instead of collapsing to an average.
 *
 * <p>Training and inference run in the Python sidecar (see {@code python/suika/diffusion_policy.py}).
 * This record holds the configuration that is serialised to YAML for the Python trainer.
 */
public record DiffusionPolicyConfig(
        int    noiseSteps,
        double betaStart,
        double betaEnd,
        String noiseSchedule,
        int    actionDim,
        int    conditionDim,
        int    hiddenDim,
        int    numLayers,
        double learningRate,
        int    batchSize,
        int    trainingEpochs
) {
    public static DiffusionPolicyConfig defaults() {
        return new DiffusionPolicyConfig(
                100,          // denoising steps (DDPM)
                1e-4,         // beta start
                0.02,         // beta end
                "cosine",     // noise schedule
                1,            // action dim (drop x, normalised)
                dev.suika.env.StateObservationEncoder.TOTAL,
                256,          // hidden dim
                4,            // transformer layers
                1e-4,         // learning rate
                256,          // batch size
                200           // training epochs
        );
    }
}
