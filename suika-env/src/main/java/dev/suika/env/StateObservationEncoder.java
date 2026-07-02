package dev.suika.env;

import dev.suika.core.Fruit;
import dev.suika.core.GameState;
import dev.suika.core.PhysicsConfig;

/**
 * Encodes the full symbolic game state into a fixed-size float vector.
 *
 * <p>Fruits are sorted by ID for a stable ordering. The vector has two sections:
 * <ol>
 *   <li>Global scalars (8 floats): current tier, next tier, score (normalised),
 *       deadline timer, fruit count, container bounds (normalised).</li>
 *   <li>Per-fruit features (9 × {@link #MAX_FRUITS} floats):
 *       x, y, vx, vy, angle, angular_vel, tier (normalised), radius (normalised), asleep.</li>
 * </ol>
 * Slots for fruits that don't exist are zero-padded.
 */
public final class StateObservationEncoder implements ObservationEncoder {

    public static final int MAX_FRUITS   = 64;
    public static final int PER_FRUIT    = 9;
    public static final int GLOBAL_DIMS  = 8;
    public static final int TOTAL        = GLOBAL_DIMS + MAX_FRUITS * PER_FRUIT;

    private static final double SCORE_SCALE    = 5000.0;
    private static final double VEL_SCALE      = 20.0;
    private static final double ANGLE_SCALE    = Math.PI;

    @Override public int[] shape() { return new int[]{TOTAL}; }

    @Override
    public void encode(GameState s, float[] out, int off) {
        // --- Globals ---
        out[off + 0] = s.currentFruitTier().tier / 11.0f;
        out[off + 1] = s.nextFruitTier().tier    / 11.0f;
        out[off + 2] = (float) Math.min(1.0, s.score() / SCORE_SCALE);
        out[off + 3] = (float) Math.min(1.0, s.timeAboveDeadline() / PhysicsConfig.DEADLINE_GRACE_SECONDS);
        out[off + 4] = s.fruits().size() / (float) MAX_FRUITS;
        out[off + 5] = s.gameOver() ? 1.0f : 0.0f;
        out[off + 6] = (float) (PhysicsConfig.DEADLINE_Y / PhysicsConfig.CONTAINER_HEIGHT);
        out[off + 7] = (float) (s.stepCount() / 1000.0);

        // --- Per-fruit ---
        var sorted = s.fruits().stream()
                .sorted((a, b) -> Integer.compare(a.id(), b.id()))
                .limit(MAX_FRUITS)
                .toList();

        int base = off + GLOBAL_DIMS;
        for (int i = 0; i < MAX_FRUITS; i++) {
            int fi = base + i * PER_FRUIT;
            if (i < sorted.size()) {
                Fruit f = sorted.get(i);
                out[fi + 0] = (float) (f.x() / PhysicsConfig.CONTAINER_WIDTH);
                out[fi + 1] = (float) (f.y() / PhysicsConfig.CONTAINER_HEIGHT);
                out[fi + 2] = (float) (f.vx() / VEL_SCALE);
                out[fi + 3] = (float) (f.vy() / VEL_SCALE);
                out[fi + 4] = (float) (f.angle() / ANGLE_SCALE);
                out[fi + 5] = (float) (f.angularVelocity() / VEL_SCALE);
                out[fi + 6] = f.tier().tier / 11.0f;
                out[fi + 7] = (float) (f.radius() / 4.0);   // headroom above WATERMELON's 3.52 radius
                out[fi + 8] = f.asleep() ? 1.0f : 0.0f;
            }
            // else: zero-padded (array is already zeroed from construction)
        }
    }
}
