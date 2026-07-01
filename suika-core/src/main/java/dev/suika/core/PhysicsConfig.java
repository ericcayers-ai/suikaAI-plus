package dev.suika.core;

/**
 * Physics and container constants.
 * All values are starting hypotheses to calibrate against reference recordings.
 * See ROADMAP.md §I.3 and Appendix B for the calibration methodology.
 */
public final class PhysicsConfig {

    private PhysicsConfig() {}

    // --- Integration ---
    /** Fixed physics timestep in seconds (60 Hz). */
    public static final double FIXED_DT = 1.0 / 60.0;
    /** Maximum physics sub-steps per real-time frame to prevent spiral-of-death. */
    public static final int    MAX_SUB_STEPS = 8;

    // --- Gravity ---
    // Chosen so a cherry (r=0.5) dropped from DROP_Y=16 reaches the floor in ~0.85 s at 1× speed.
    // g = 2*(DROP_Y - r) / t² = 2*15.5 / 0.7225 ≈ 42.9
    public static final double GRAVITY_Y = -43.0;

    // --- Material ---
    /**
     * Fruit/wall bounciness. Mutable (not {@code final}) so the "Bouncy fruit" setting
     * can flip it at runtime — every other value here is a fixed calibration constant,
     * but threading this through as a constructor parameter would touch the 50+
     * {@code new GameCore(seed)} call sites across every trainer, runner, and test, for
     * a pure gameplay-feel toggle. {@code volatile} since AI evaluation can run
     * {@link GameCore} instances on background threads concurrently with the setting
     * being flipped from the render thread.
     */
    public static volatile double restitution = 0.0;   // no bounce; merge spawns land cleanly
    public static final double FRICTION_STATIC  = 0.7;
    public static final double FRICTION_DYNAMIC = 0.5;

    // --- Container (all in game units) ---
    public static final double CONTAINER_WIDTH     = 10.0;
    public static final double CONTAINER_HEIGHT    = 15.0;
    public static final double WALL_THICKNESS      = 0.4;

    /** X of the left inner wall. */
    public static final double LEFT_WALL_X  = 0.0;
    /** X of the right inner wall. */
    public static final double RIGHT_WALL_X = CONTAINER_WIDTH;
    /** Y of the floor top surface. */
    public static final double FLOOR_Y      = 0.0;

    // --- Dead-line ---
    /** Y above which fruit must not rest. */
    public static final double DEADLINE_Y = CONTAINER_HEIGHT - 1.5;
    /** Seconds a fruit may be above the dead-line before game-over. */
    public static final double DEADLINE_GRACE_SECONDS = 3.0;

    // --- Drop ---
    /** Y at which a new fruit is created (top of container). */
    public static final double DROP_Y = CONTAINER_HEIGHT + 1.0;
    /** Minimum x for the drop (centre of fruit must stay inside walls). */
    public static final double DROP_X_MIN = LEFT_WALL_X  + 0.2;
    /** Maximum x for the drop. */
    public static final double DROP_X_MAX = RIGHT_WALL_X - 0.2;

    // --- Density scaling (bigger fruit shoves smaller convincingly) ---
    public static final double BASE_DENSITY = 1.0;

    // --- Settling ---
    /** A body is considered "sleeping" / settled when its linear speed falls below this. */
    public static final double SLEEP_LINEAR_VELOCITY  = 0.05;
    public static final double SLEEP_ANGULAR_VELOCITY = 0.05;
    /** Seconds below sleep threshold before a body is put to sleep. */
    public static final double SLEEP_TIME = 0.5;

    // --- 2× Watermelon bonus ---
    public static final int DOUBLE_WATERMELON_BONUS = 100;
}
