package dev.suika.ai;

/**
 * Configuration for Flow Matching policy (ROADMAP §IV.11).
 *
 * <p>Flow matching learns a velocity field that transports noise to actions
 * in fewer inference steps than DDPM diffusion, making it better suited to
 * real-time action generation inside the live game. Offer as a head-to-head
 * experiment vs. diffusion on the same BC dataset.
 */
public record FlowMatchingConfig(
        int    inferenceSteps,
        String solver,
        int    conditionDim,
        int    hiddenDim,
        int    numLayers,
        double learningRate,
        int    batchSize
) {
    public static FlowMatchingConfig defaults() {
        return new FlowMatchingConfig(
                10,           // inference steps (vs. 100 for DDPM)
                "euler",      // ODE solver
                dev.suika.env.StateObservationEncoder.TOTAL,
                256,
                4,
                1e-4,
                256
        );
    }
}
