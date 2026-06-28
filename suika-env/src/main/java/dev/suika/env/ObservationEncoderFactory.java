package dev.suika.env;

import dev.suika.core.GameState;

/**
 * Factory that maps {@link ObservationMode} to a concrete encoder.
 * Pixel mode requires a rendering context and returns a no-op stub here —
 * the LibGDX layer supplies a real pixel encoder in suika-game.
 */
public final class ObservationEncoderFactory {

    private ObservationEncoderFactory() {}

    public static ObservationEncoder create(ObservationMode mode) {
        return switch (mode) {
            case STATE  -> new StateObservationEncoder();
            case HYBRID -> new HybridObservationEncoder();
            case PIXELS -> new PixelObservationEncoderStub();
        };
    }

    /** Placeholder returned for PIXELS mode in headless contexts. */
    static final class PixelObservationEncoderStub implements ObservationEncoder {
        private static final int[] SHAPE = {4, 84, 84}; // frame-stack × H × W
        @Override public int[] shape() { return SHAPE; }
        @Override public void encode(GameState state, float[] out, int offset) {
            // Headless stub: zeros. Real implementation renders via LibGDX framebuffer.
        }
    }
}
