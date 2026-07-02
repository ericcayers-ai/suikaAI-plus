package dev.suika.game.rtlab;

import dev.suika.core.FruitTier;
import dev.suika.core.PhysicsConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Jar3DPhysics is pure math (no GPU/Vulkan/GLFW dependency), so like Icosphere it
 *  runs in CI — pins down the gameplay rules the 3D mode must share with the 2D
 *  engine: settling, containment, merging with scoring, and the deadline rule. */
class Jar3DPhysicsTest {

    private static void settle(Jar3DPhysics p, double seconds) {
        int steps = (int) Math.ceil(seconds / PhysicsConfig.FIXED_DT);
        for (int i = 0; i < steps; i++) p.tick();
    }

    @Test
    void droppedFruitFallsAndSettlesOnTheFloor() {
        Jar3DPhysics p = new Jar3DPhysics(1);
        p.spawnDrop(0, 0);
        settle(p, 4.0);
        assertEquals(1, p.balls().size());
        Jar3DPhysics.Ball b = p.balls().get(0);
        assertEquals(b.radius, b.y, 0.05, "should rest on the floor (y == radius)");
        double speed = Math.sqrt(b.vx * b.vx + b.vy * b.vy + b.vz * b.vz);
        assertTrue(speed < 0.2, "should be nearly at rest, speed was " + speed);
    }

    @Test
    void fruitsStayInsideTheJarRadius() {
        Jar3DPhysics p = new Jar3DPhysics(2);
        // Pile up a bunch of drops around the rim so the pile pushes outward.
        for (int i = 0; i < 14; i++) {
            double a = i * 0.9;
            p.spawnDrop(Math.cos(a) * 4.5, Math.sin(a) * 4.5);
            settle(p, 0.7);
        }
        settle(p, 3.0);
        for (Jar3DPhysics.Ball b : p.balls()) {
            double d = Math.sqrt(b.x * b.x + b.z * b.z);
            assertTrue(d + b.radius <= Jar3DPhysics.PLAY_RADIUS + 0.05,
                    "ball at radial distance " + d + " with r=" + b.radius + " escaped the jar");
            assertTrue(b.y >= b.radius - 0.05, "ball sank through the floor: y=" + b.y);
        }
    }

    @Test
    void sameTierContactMergesAndScores() {
        Jar3DPhysics p = new Jar3DPhysics(3);
        // Same package: inject a touching same-tier pair directly (the droppable
        // queue is random, so driving this through spawnDrop would be flaky).
        p.balls().add(new Jar3DPhysics.Ball(FruitTier.CHERRY, -0.4, 0.5, 0));
        p.balls().add(new Jar3DPhysics.Ball(FruitTier.CHERRY,  0.4, 0.5, 0));
        p.tick();
        assertEquals(1, p.balls().size(), "the pair should merge into one fruit");
        assertEquals(FruitTier.STRAWBERRY, p.balls().get(0).tier);
        assertEquals(FruitTier.STRAWBERRY.mergeScore, p.score());
    }

    @Test
    void twoWatermelonsVanishForTheBonus() {
        Jar3DPhysics p = new Jar3DPhysics(6);
        double r = FruitTier.WATERMELON.radius;
        p.balls().add(new Jar3DPhysics.Ball(FruitTier.WATERMELON, -3.0, r, 0));
        p.balls().add(new Jar3DPhysics.Ball(FruitTier.WATERMELON,  3.0, r, 0));
        settle(p, 3.0);   // let them roll to the centre and touch
        assertEquals(0, p.balls().size(), "double watermelon should vanish");
        assertEquals(PhysicsConfig.DOUBLE_WATERMELON_BONUS, p.score());
    }

    @Test
    void restingPileAboveTheDeadlineEndsTheGame() {
        Jar3DPhysics p = new Jar3DPhysics(4);
        // Over-fill directly with a stacked column of big fruit (alternating tiers so
        // no pair merges away) — the pile top ends far above DEADLINE_Y.
        FruitTier[] alternating = {FruitTier.WATERMELON, FruitTier.MELON};
        for (int i = 0; i < 6; i++) {
            p.balls().add(new Jar3DPhysics.Ball(alternating[i % 2], 0, 3.5 + i * 6.5, 0));
        }
        settle(p, 6.0 + PhysicsConfig.DEADLINE_GRACE_SECONDS);
        assertTrue(p.isGameOver(), "an over-filled jar must end the game via the deadline rule");
    }

    @Test
    void clampDropKeepsTheFruitInsideTheJarMouth() {
        Jar3DPhysics p = new Jar3DPhysics(5);
        // Drops enter through the jar's OPEN MOUTH — the narrowest point of the
        // fruit's path — so the clamp radius is the mouth limit, not the body's.
        double[] c = p.clampDrop(99, 0, 1.0);
        double d = Math.sqrt(c[0] * c[0] + c[1] * c[1]);
        assertEquals(JarShape.dropLimit(1.0), d, 1e-6);
        assertTrue(d + 1.0 < JarShape.MOUTH_INNER_RADIUS,
                "clamped fruit surface must clear the mouth rim");
        double[] center = p.clampDrop(0, 0, 1.0);
        assertEquals(0, center[0], 1e-9);
        assertEquals(0, center[1], 1e-9);
    }

    @Test
    void mergedFruitNeverEndsUpOutsideTheJarProfile() {
        // A same-tier pair sitting right at the edge the SMALLER tier is allowed to
        // occupy — each parent alone satisfies containment, but the merge result's
        // bigger radius would not, at that same position, without the post-merge
        // resolveContainer() pass in Jar3DPhysics.tick().
        Jar3DPhysics p = new Jar3DPhysics(9);
        double y = 8.0; // straight body section, so the limit is a fixed PLAY_RADIUS - r
        double limit = Jar3DPhysics.PLAY_RADIUS - FruitTier.DEKOPON.radius;
        Jar3DPhysics.Ball a = new Jar3DPhysics.Ball(FruitTier.GRAPE, limit - 0.02, y, 0);
        Jar3DPhysics.Ball b = new Jar3DPhysics.Ball(FruitTier.GRAPE, limit + 0.02, y, 0);
        a.vy = 0; b.vy = 0;
        p.balls().add(a);
        p.balls().add(b);
        p.tick();
        assertEquals(1, p.balls().size(), "the touching pair should merge");
        Jar3DPhysics.Ball merged = p.balls().get(0);
        assertEquals(FruitTier.DEKOPON, merged.tier);
        double d = Math.sqrt(merged.x * merged.x + merged.z * merged.z);
        double surfaceBound = Math.min(Jar3DPhysics.PLAY_RADIUS, JarShape.radiusAt(merged.y));
        assertTrue(d + merged.radius <= surfaceBound + 0.05,
                "merged fruit at d=" + d + " r=" + merged.radius + " must stay inside the jar");
    }

    @Test
    void jarProfileFunnelsFruitAboveTheShoulder() {
        // The glass profile: full body radius below the shoulder, neck radius above,
        // strictly monotonic in between.
        assertEquals(JarShape.BODY_RADIUS, JarShape.radiusAt(5.0), 1e-9);
        assertEquals(JarShape.BODY_RADIUS, JarShape.radiusAt(JarShape.BODY_TOP), 1e-9);
        assertEquals(JarShape.NECK_RADIUS, JarShape.radiusAt(JarShape.SHOULDER_TOP), 1e-9);
        assertEquals(JarShape.NECK_RADIUS, JarShape.radiusAt(25.0), 1e-9);
        double prev = JarShape.radiusAt(JarShape.BODY_TOP);
        for (double y = JarShape.BODY_TOP; y <= JarShape.SHOULDER_TOP; y += 0.05) {
            double r = JarShape.radiusAt(y);
            assertTrue(r <= prev + 1e-9, "profile must taper monotonically at y=" + y);
            prev = r;
        }

        // A ball resting in the shoulder band gets pushed inside the local profile.
        Jar3DPhysics p = new Jar3DPhysics(6);
        Jar3DPhysics.Ball b = new Jar3DPhysics.Ball(dev.suika.core.FruitTier.GRAPE, 4.8, 16.5, 0);
        b.vy = 0.1; // held up: cancel most of gravity so it stays in the band this tick
        p.balls().add(b);
        p.tick();
        double dist = Math.sqrt(b.x * b.x + b.z * b.z);
        assertTrue(dist + b.radius <= JarShape.radiusAt(b.y) + 1e-6,
                "ball at y=" + b.y + " must sit inside the glass profile");
    }
}
