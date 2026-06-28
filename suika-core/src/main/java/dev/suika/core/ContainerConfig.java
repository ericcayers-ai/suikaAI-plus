package dev.suika.core;

/**
 * Configurable container geometry for modding and alternate benchmarks (ROADMAP §XII).
 *
 * <p>The standard Suika container is 10×15 game units. Alternate configs enable
 * different difficulty curves (wide → easy stacking; tall/narrow → higher skill ceiling).
 *
 * @param width         inner container width (game units)
 * @param height        inner container height (game units)
 * @param wallThickness wall segment thickness (game units)
 * @param deadlineY     Y above which resting fruit triggers the game-over timer
 * @param dropY         Y at which newly dropped fruit are spawned
 * @param dropXMargin   minimum clearance from walls for drop position
 */
public record ContainerConfig(
        double width,
        double height,
        double wallThickness,
        double deadlineY,
        double dropY,
        double dropXMargin
) {
    public ContainerConfig {
        if (width  <= 0) throw new IllegalArgumentException("width must be positive");
        if (height <= 0) throw new IllegalArgumentException("height must be positive");
        if (deadlineY <= 0 || deadlineY > height)
            throw new IllegalArgumentException("deadlineY must be in (0, height]");
    }

    /** Minimum x for drop centre. */
    public double dropXMin() { return dropXMargin; }

    /** Maximum x for drop centre. */
    public double dropXMax() { return width - dropXMargin; }

    /** Container configured to match {@link PhysicsConfig} constants. */
    public static ContainerConfig standard() {
        return new ContainerConfig(
                PhysicsConfig.CONTAINER_WIDTH,
                PhysicsConfig.CONTAINER_HEIGHT,
                PhysicsConfig.WALL_THICKNESS,
                PhysicsConfig.DEADLINE_Y,
                PhysicsConfig.DROP_Y,
                0.2
        );
    }

    /** A narrower container that demands more precise drops. */
    public static ContainerConfig narrow() {
        double w = 6.0, h = 15.0;
        return new ContainerConfig(w, h, 0.4, h - 1.5, h + 1.0, 0.2);
    }

    /** A wide, shallow container that favours horizontal spreading. */
    public static ContainerConfig wide() {
        double w = 16.0, h = 10.0;
        return new ContainerConfig(w, h, 0.4, h - 1.0, h + 1.0, 0.3);
    }
}
