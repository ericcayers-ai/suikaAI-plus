package dev.suika.game.rtlab;

import dev.suika.core.FruitTier;
import dev.suika.core.PhysicsConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * True 3D Suika physics: rigid spheres under gravity inside an open cylindrical
 * jar. Deliberately self-contained (no dyn4j — it is 2D-only) and deliberately
 * simple: impulse response on contacts plus iterative positional correction is
 * plenty for spheres in a convex container, and the merge rule does not need
 * contact manifolds — the same distance test the 2D engine uses works unchanged.
 *
 * <p>Rules mirror {@link dev.suika.core.GameCore} exactly where they apply:
 * gravity, container height, contact tolerance, droppable-tier distribution,
 * merge scoring (including the double-watermelon bonus), the resting-above-the-
 * deadline game-over rule with its grace period, and the mutable
 * {@link PhysicsConfig#restitution} so the "Bouncy fruit" setting carries over.
 */
public final class Jar3DPhysics {

    /** Interior radius fruits may occupy in the jar's straight body section —
     *  slightly under {@link JarShape#BODY_RADIUS} so resting fruit never visually
     *  intersects the glass. Above the body the containment follows the jar's
     *  shoulder/neck profile (see {@link JarShape#radiusAt}). */
    public static final double PLAY_RADIUS = 5.2;
    /** Clearance kept between fruit surface and the curved glass profile. */
    private static final double GLASS_MARGIN = 0.15;
    public static final double JAR_HEIGHT = PhysicsConfig.CONTAINER_HEIGHT;
    /** 3D drops release from the chute above the jar's open mouth and fall in
     *  through the neck — higher than the 2D engine's DROP_Y, which spawns inside
     *  the (strictly cylindrical) 2D container. */
    public static final double DROP_Y = 19.4;

    private static final double CONTACT_TOLERANCE = 1.08;  // same slack as GameCore
    private static final int    SOLVER_ITERATIONS = 4;
    private static final double SLEEP_SPEED = 0.15;
    private static final double FRICTION_DAMP = 2.2;       // tangential damping on contact, per second

    static final class Ball {
        double x, y, z, vx, vy, vz;
        FruitTier tier;
        double radius, mass, restTime;
        double prevX, prevY, prevZ;   // position before this tick, for rest detection

        Ball(FruitTier tier, double x, double y, double z) {
            this.tier = tier;
            this.x = x; this.y = y; this.z = z;
            this.radius = tier.radius;
            this.mass = tier.radius * tier.radius * tier.radius;
        }
    }

    private final List<Ball> balls = new ArrayList<>();
    private final Random rng;

    private long score = 0;
    private boolean gameOver = false;
    private double timeAboveDeadline = 0;

    private FruitTier currentTier;
    private FruitTier nextTier;

    public Jar3DPhysics(long seed) {
        this.rng = new Random(seed);
        this.currentTier = drawDroppableTier();
        this.nextTier = drawDroppableTier();
    }

    /** Same 4/3/2/1 cherry/strawberry/grape/dekopon distribution as the 2D engine. */
    private FruitTier drawDroppableTier() {
        int roll = rng.nextInt(10);
        if (roll <= 3) return FruitTier.CHERRY;
        if (roll <= 6) return FruitTier.STRAWBERRY;
        if (roll <= 8) return FruitTier.GRAPE;
        return FruitTier.DEKOPON;
    }

    /** Clamp a drop point so the fruit passes cleanly through the jar's OPEN MOUTH
     *  (the narrowest part of its path — see {@link JarShape#dropLimit}); nothing
     *  can be aimed at the glass shoulder or outside the jar entirely. */
    public double[] clampDrop(double x, double z, double radius) {
        double limit = Math.min(PLAY_RADIUS - radius, JarShape.dropLimit(radius));
        double d = Math.sqrt(x * x + z * z);
        if (d > limit && d > 1e-9) {
            x *= limit / d;
            z *= limit / d;
        }
        return new double[]{x, z};
    }

    public void spawnDrop(double x, double z) {
        if (gameOver) return;
        double[] p = clampDrop(x, z, currentTier.radius);
        balls.add(new Ball(currentTier, p[0], DROP_Y, p[1]));
        currentTier = nextTier;
        nextTier = drawDroppableTier();
    }

    /** One fixed 1/60s step: integrate, resolve contacts, merge, check the deadline. */
    public void tick() {
        if (gameOver) return;
        double dt = PhysicsConfig.FIXED_DT;

        for (Ball b : balls) {
            b.prevX = b.x; b.prevY = b.y; b.prevZ = b.z;
            b.vy += PhysicsConfig.GRAVITY_Y * dt;
            b.x += b.vx * dt;
            b.y += b.vy * dt;
            b.z += b.vz * dt;
        }

        // Bottom-up contact order: a Gauss-Seidel sweep then propagates the floor
        // constraint up a whole stack in ONE iteration instead of one level per
        // iteration (unordered, a 6-ball tower never converged within the budget).
        balls.sort((a, b) -> Double.compare(a.y, b.y));

        double restitution = PhysicsConfig.restitution;
        for (int iter = 0; iter < SOLVER_ITERATIONS; iter++) {
            resolveContainer(restitution, dt);
            resolveSpheres(restitution);
        }
        resolveContainer(restitution, dt);

        applyMerges();
        updateRestAndDeadline(dt);
    }

    private void resolveSpheres(double restitution) {
        int n = balls.size();
        for (int i = 0; i < n; i++) {
            Ball a = balls.get(i);
            for (int j = i + 1; j < n; j++) {
                Ball b = balls.get(j);
                double dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
                double dist2 = dx * dx + dy * dy + dz * dz;
                double minDist = a.radius + b.radius;
                if (dist2 >= minDist * minDist || dist2 < 1e-12) continue;
                double dist = Math.sqrt(dist2);
                double nx = dx / dist, ny = dy / dist, nz = dz / dist;

                // positional correction split by inverse mass
                double pen = minDist - dist;
                double invA = 1.0 / a.mass, invB = 1.0 / b.mass, invSum = invA + invB;
                double corr = pen / invSum * 0.8;
                a.x -= nx * corr * invA; a.y -= ny * corr * invA; a.z -= nz * corr * invA;
                b.x += nx * corr * invB; b.y += ny * corr * invB; b.z += nz * corr * invB;

                // impulse on approaching velocity
                double rvx = b.vx - a.vx, rvy = b.vy - a.vy, rvz = b.vz - a.vz;
                double vn = rvx * nx + rvy * ny + rvz * nz;
                if (vn < 0) {
                    double jimp = -(1.0 + restitution) * vn / invSum;
                    a.vx -= nx * jimp * invA; a.vy -= ny * jimp * invA; a.vz -= nz * jimp * invA;
                    b.vx += nx * jimp * invB; b.vy += ny * jimp * invB; b.vz += nz * jimp * invB;
                }
            }
        }
    }

    private void resolveContainer(double restitution, double dt) {
        double damp = Math.max(0.0, 1.0 - FRICTION_DAMP * dt);
        for (Ball b : balls) {
            // floor
            if (b.y < b.radius) {
                b.y = b.radius;
                if (b.vy < 0) b.vy = -b.vy * restitution;
                b.vx *= damp;
                b.vz *= damp;
            }
            // jar wall — follows the glass profile, so fruit piled above the body
            // section gets funnelled by the shoulder instead of escaping through it
            double d = Math.sqrt(b.x * b.x + b.z * b.z);
            double surfaceBound = Math.min(PLAY_RADIUS, JarShape.radiusAt(b.y) - GLASS_MARGIN);
            double limit = surfaceBound - b.radius;
            if (d > limit && d > 1e-9) {
                double nx = b.x / d, nz = b.z / d;   // outward radial
                b.x = nx * limit;
                b.z = nz * limit;
                double vrad = b.vx * nx + b.vz * nz;
                if (vrad > 0) {
                    b.vx -= (1.0 + restitution) * vrad * nx;
                    b.vz -= (1.0 + restitution) * vrad * nz;
                }
                b.vy *= damp;
            }
        }
    }

    private void applyMerges() {
        for (int i = 0; i < balls.size(); i++) {
            Ball a = balls.get(i);
            for (int j = i + 1; j < balls.size(); j++) {
                Ball b = balls.get(j);
                if (a.tier != b.tier) continue;
                double dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (dist > (a.radius + b.radius) * CONTACT_TOLERANCE) continue;

                FruitTier result = a.tier.next();
                score += (result != null) ? result.mergeScore : PhysicsConfig.DOUBLE_WATERMELON_BONUS;

                if (result != null) {
                    Ball merged = new Ball(result,
                            (a.x + b.x) / 2, (a.y + b.y) / 2, (a.z + b.z) / 2);
                    // conserve momentum so a merge mid-fall doesn't freeze in the air
                    double m = a.mass + b.mass;
                    merged.vx = (a.vx * a.mass + b.vx * b.mass) / m;
                    merged.vy = (a.vy * a.mass + b.vy * b.mass) / m;
                    merged.vz = (a.vz * a.mass + b.vz * b.mass) / m;
                    balls.remove(j);
                    balls.set(i, merged);
                } else {
                    balls.remove(j);
                    balls.remove(i);
                    i--;
                }
                break; // ball i consumed/replaced — restart pairing from the next i
            }
        }
    }

    private void updateRestAndDeadline(double dt) {
        boolean anyRestingAbove = false;
        for (Ball b : balls) {
            // Rest detection uses ACTUAL displacement this tick, not the solver's
            // velocity: with positional correction, a stably stacked ball can carry a
            // large not-yet-converged velocity while its position never moves — the
            // honest "is it moving" signal is where it actually went.
            double dx = b.x - b.prevX, dy = b.y - b.prevY, dz = b.z - b.prevZ;
            double speed = Math.sqrt(dx * dx + dy * dy + dz * dz) / dt;
            b.restTime = speed < SLEEP_SPEED ? b.restTime + dt : 0;
            boolean resting = b.restTime >= PhysicsConfig.SLEEP_TIME;
            if (resting && b.y + b.radius > PhysicsConfig.DEADLINE_Y) anyRestingAbove = true;
        }
        if (anyRestingAbove) {
            timeAboveDeadline += dt;
            if (timeAboveDeadline >= PhysicsConfig.DEADLINE_GRACE_SECONDS) gameOver = true;
        } else {
            timeAboveDeadline = Math.max(0, timeAboveDeadline - dt);
        }
    }

    /** True when nothing is still falling through the drop zone above the rim. */
    public boolean chuteClear() {
        double thresh = JAR_HEIGHT - 0.5;
        for (Ball b : balls) {
            if (b.y > thresh) return false;
        }
        return true;
    }

    public List<Ball> balls()          { return balls; }
    public long score()                { return score; }
    public boolean isGameOver()        { return gameOver; }
    public FruitTier currentTier()     { return currentTier; }
    public FruitTier nextTier()        { return nextTier; }
}
