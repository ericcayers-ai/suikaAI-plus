package dev.suika.env;

import dev.suika.core.Fruit;
import dev.suika.core.GameState;
import dev.suika.core.PhysicsConfig;

/**
 * Factory that maps {@link ObservationMode} to a concrete encoder.
 *
 * <p>PIXELS mode renders the game state as a grayscale raster entirely from the
 * symbolic {@link GameState} — no LibGDX framebuffer required. Each fruit is drawn
 * as a filled circle onto an H×W grid; tier intensity encodes the fruit type.
 * Four consecutive frames are stacked (frame-stack = 4) for velocity cues when
 * used in a vectorised env; headlessly only one distinct frame is available, so
 * the same frame is replicated across all four stack positions.
 */
public final class ObservationEncoderFactory {

    private ObservationEncoderFactory() {}

    public static ObservationEncoder create(ObservationMode mode) {
        return switch (mode) {
            case STATE  -> new StateObservationEncoder();
            case HYBRID -> new HybridObservationEncoder();
            case PIXELS -> new SoftwarePixelEncoder();
        };
    }

    /**
     * Headless software rasteriser for pixel observations.
     *
     * <p>Renders into a {@code FRAMES × H × W} float buffer:
     * <ul>
     *   <li>Background = 0.0 (black)</li>
     *   <li>Container walls = 0.15</li>
     *   <li>Deadline line = 0.5</li>
     *   <li>Fruit pixel = {@code tier / 11.0} (bright = large fruit)</li>
     * </ul>
     * The raster is row-major: {@code out[offset + frame * H * W + row * W + col]}.
     */
    static final class SoftwarePixelEncoder implements ObservationEncoder {

        static final int FRAMES = 4;
        static final int H      = 84;
        static final int W      = 84;

        @Override
        public int[] shape() { return new int[]{FRAMES, H, W}; }

        @Override
        public void encode(GameState state, float[] out, int offset) {
            // Rasterise into a single H×W frame
            float[] frame = new float[H * W];
            renderFrame(state, frame);

            // Stack the same frame FRAMES times (velocity cues need multi-step env wrapper)
            for (int f = 0; f < FRAMES; f++) {
                System.arraycopy(frame, 0, out, offset + f * H * W, H * W);
            }
        }

        private void renderFrame(GameState state, float[] frame) {
            double xMin = PhysicsConfig.LEFT_WALL_X;
            double xMax = PhysicsConfig.RIGHT_WALL_X;
            double yMax = PhysicsConfig.CONTAINER_HEIGHT;

            // Draw walls (left=0, right=W-1 columns)
            for (int row = 0; row < H; row++) {
                frame[row * W]         = 0.15f;
                frame[row * W + W - 1] = 0.15f;
            }

            // Draw deadline line
            int deadlineRow = worldYToRow(PhysicsConfig.DEADLINE_Y, yMax);
            if (deadlineRow >= 0 && deadlineRow < H) {
                for (int col = 1; col < W - 1; col++) {
                    frame[deadlineRow * W + col] = 0.5f;
                }
            }

            // Draw each fruit as a filled circle
            for (Fruit f : state.fruits()) {
                float intensity = (float) (f.tier().tier / 11.0);
                drawCircle(frame, f.x(), f.y(), f.radius(), intensity,
                        xMin, xMax, yMax);
            }
        }

        private void drawCircle(float[] frame, double cx, double cy, double r,
                                float intensity,
                                double xMin, double xMax, double yMax) {
            int col0 = worldXToCol(cx - r, xMin, xMax);
            int col1 = worldXToCol(cx + r, xMin, xMax);
            int row0 = worldYToRow(cy + r, yMax);
            int row1 = worldYToRow(cy - r, yMax);

            col0 = Math.max(0, Math.min(W - 1, col0));
            col1 = Math.max(0, Math.min(W - 1, col1));
            row0 = Math.max(0, Math.min(H - 1, row0));
            row1 = Math.max(0, Math.min(H - 1, row1));

            double rPxX = (r / (xMax - xMin)) * W;
            double rPxY = (r / yMax)           * H;

            for (int row = row0; row <= row1; row++) {
                for (int col = col0; col <= col1; col++) {
                    double normDx = (col - worldXToCol(cx, xMin, xMax)) / (rPxX + 1e-9);
                    double normDy = (row - worldYToRow(cy, yMax))        / (rPxY + 1e-9);
                    if (normDx * normDx + normDy * normDy <= 1.0) {
                        frame[row * W + col] = intensity;
                    }
                }
            }
        }

        private int worldXToCol(double x, double xMin, double xMax) {
            return (int) Math.round(((x - xMin) / (xMax - xMin)) * (W - 1));
        }

        private int worldYToRow(double y, double yMax) {
            // Y=0 is bottom → row H-1; Y=yMax → row 0
            return (int) Math.round((1.0 - y / yMax) * (H - 1));
        }
    }
}
