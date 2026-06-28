package dev.suika.ai;

import dev.suika.core.GameState;
import dev.suika.env.StateObservationEncoder;

/**
 * JVM-side interface to the Python generative model sidecar (ROADMAP §IV.11, §II.4).
 *
 * <p>In production this sends observations over the bridge (gRPC / shared-memory)
 * and receives generated actions. The stub here returns a centre-drop for testing.
 * Real implementations live in {@code python/suika/diffusion_policy.py} and
 * {@code python/suika/flow_matching.py}.
 */
public final class GenerativeModelBridge {

    /** Supported generative policy types. */
    public enum ModelType { DIFFUSION_POLICY, FLOW_MATCHING }

    private final ModelType type;
    private final StateObservationEncoder encoder = new StateObservationEncoder();

    public GenerativeModelBridge(ModelType type) { this.type = type; }

    /**
     * Generate an action conditioned on the board state.
     *
     * @param state   current game state
     * @param numBins number of discrete action bins
     * @return sampled action index in {@code [0, numBins)}
     */
    public int sampleAction(GameState state, int numBins) {
        float[] obs = encoder.encode(state);
        // Stub: centre drop — replace with bridge call to Python process
        return numBins / 2;
    }

    /**
     * Generate a batch of candidate actions for visualisation in the dashboard.
     * Real implementation calls Python to run multiple denoising trajectories.
     */
    public int[] sampleBatch(GameState state, int numBins, int batchSize) {
        int[] actions = new int[batchSize];
        for (int i = 0; i < batchSize; i++) actions[i] = sampleAction(state, numBins);
        return actions;
    }

    public ModelType type() { return type; }
}
