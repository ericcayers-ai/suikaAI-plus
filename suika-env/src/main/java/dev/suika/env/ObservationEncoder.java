package dev.suika.env;

import dev.suika.core.GameState;

/**
 * Converts a {@link GameState} into a flat float array for the agent.
 * Implementations: {@link StateObservationEncoder}, {@link HybridObservationEncoder}.
 */
public interface ObservationEncoder {

    /**
     * @return the shape of the encoded observation [dims...],
     *         e.g. {@code [128]} for a flat state vector
     *         or {@code [11, 16, 16]} for the multi-channel heatmap.
     */
    int[] shape();

    /** Total number of floats in the encoded observation (product of {@link #shape()}). */
    default int size() {
        int n = 1;
        for (int d : shape()) n *= d;
        return n;
    }

    /**
     * Encode {@code state} into {@code out} starting at {@code offset}.
     * Callers must ensure {@code out.length >= offset + size()}.
     */
    void encode(GameState state, float[] out, int offset);

    /** Convenience: encode into a fresh array. */
    default float[] encode(GameState state) {
        float[] out = new float[size()];
        encode(state, out, 0);
        return out;
    }
}
