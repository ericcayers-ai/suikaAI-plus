package dev.suika.env;

import dev.suika.core.Fruit;
import dev.suika.core.FruitTier;
import dev.suika.core.GameState;
import dev.suika.core.PhysicsConfig;

/**
 * Rasterised multi-channel heatmap encoder.
 *
 * <p>Produces a {@code [CHANNELS × GRID_H × GRID_W]} tensor:
 * <ul>
 *   <li>Channels 0–10: one per fruit tier — Gaussian "blob" of the fruit at that cell.</li>
 *   <li>Channel 11: velocity-magnitude heatmap (all tiers combined).</li>
 *   <li>Channel 12: dead-line indicator row.</li>
 *   <li>Channel 13: current-drop-tier indicator (uniform channel).</li>
 * </ul>
 * This is CNN-friendly with a fixed shape, but carries exact tier information —
 * the "best of both worlds" hybrid described in ROADMAP §III.1.
 */
public final class HybridObservationEncoder implements ObservationEncoder {

    public static final int GRID_W   = 16;
    public static final int GRID_H   = 24;
    public static final int CHANNELS = 14;   // 11 tiers + vel + deadline + current-tier

    private static final double SIGMA = 0.6; // Gaussian sigma in grid cells

    @Override
    public int[] shape() { return new int[]{CHANNELS, GRID_H, GRID_W}; }

    @Override
    public void encode(GameState s, float[] out, int off) {
        // out is pre-zeroed by the caller via new float[size()]

        double cellW = PhysicsConfig.CONTAINER_WIDTH  / GRID_W;
        double cellH = PhysicsConfig.CONTAINER_HEIGHT / GRID_H;

        for (Fruit f : s.fruits()) {
            int tierIdx = f.tier().tier - 1; // 0-based
            double velMag = Math.sqrt(f.vx() * f.vx() + f.vy() * f.vy());

            for (int row = 0; row < GRID_H; row++) {
                for (int col = 0; col < GRID_W; col++) {
                    double cx = (col + 0.5) * cellW;
                    double cy = (row + 0.5) * cellH;
                    double dx = (f.x() - cx) / cellW;
                    double dy = (f.y() - cy) / cellH;
                    double g  = gaussian(dx * dx + dy * dy);

                    // Tier channel
                    int tierOff = off + tierIdx * GRID_H * GRID_W + row * GRID_W + col;
                    out[tierOff] = Math.max(out[tierOff], (float) g);

                    // Velocity channel
                    int velOff = off + 11 * GRID_H * GRID_W + row * GRID_W + col;
                    out[velOff] = Math.max(out[velOff], (float) (g * Math.min(1.0, velMag / 10.0)));
                }
            }
        }

        // Dead-line row — highlight the row nearest DEADLINE_Y
        int deadRow = Math.min(GRID_H - 1, (int) (PhysicsConfig.DEADLINE_Y / cellH));
        for (int col = 0; col < GRID_W; col++) {
            out[off + 12 * GRID_H * GRID_W + deadRow * GRID_W + col] = 1.0f;
        }

        // Current-drop-tier channel — uniform fill proportional to tier
        float tierVal = s.currentFruitTier().tier / 11.0f;
        int base13 = off + 13 * GRID_H * GRID_W;
        for (int i = 0; i < GRID_H * GRID_W; i++) {
            out[base13 + i] = tierVal;
        }
    }

    private static double gaussian(double distSq) {
        return Math.exp(-distSq / (2.0 * SIGMA * SIGMA));
    }
}
