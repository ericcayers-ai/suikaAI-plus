package dev.suika.core;

/**
 * Immutable snapshot of a single fruit's state at one physics instant.
 */
public record Fruit(
        int     id,
        FruitTier tier,
        double  x,
        double  y,
        double  vx,
        double  vy,
        double  angle,
        double  angularVelocity,
        boolean asleep
) {
    public double radius() {
        return tier.radius;
    }

    /** Returns a copy with updated position/velocity (used when building snapshots). */
    public Fruit withPhysics(double x, double y, double vx, double vy,
                              double angle, double angularVelocity, boolean asleep) {
        return new Fruit(id, tier, x, y, vx, vy, angle, angularVelocity, asleep);
    }
}
