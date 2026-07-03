package dev.suika.game.rtlab;

import dev.suika.core.FruitTier;
import dev.suika.core.PhysicsConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * True 3D gameplay: fruits are spheres anywhere inside the jar's cylinder, driven
 * by {@link Jar3DPhysics}. The mouse aims the drop point across the jar's cross
 * section — window x maps to world x, window y maps to world DEPTH (z), so pushing
 * the mouse up drops deeper into the scene.
 */
public final class Rt3DSession implements RtGameSession {

    private static final float DROP_COOLDOWN = 0.24f;

    private Jar3DPhysics physics;
    private double accumulator = 0;
    private float dropCooldown = 0f;
    private float hoverX = 0f, hoverZ = 0f;

    public Rt3DSession(long seed) {
        this.physics = new Jar3DPhysics(seed);
    }

    @Override
    public void setPointer(float nx, float ny) {
        // Full window span maps to the jar diameter; y is inverted so "mouse up"
        // means "farther away" (larger -z is toward the wall).
        float x = (nx - 0.5f) * 2f * (float) Jar3DPhysics.PLAY_RADIUS;
        float z = (ny - 0.5f) * 2f * (float) Jar3DPhysics.PLAY_RADIUS;
        double[] p = physics.clampDrop(x, z, physics.currentTier().radius);
        hoverX = (float) p[0];
        hoverZ = (float) p[1];
    }

    @Override
    public void drop() {
        if (physics.isGameOver() || dropCooldown > 0f || !physics.chuteClear()) return;
        physics.spawnDrop(hoverX, hoverZ);
        dropCooldown = DROP_COOLDOWN;
    }

    @Override
    public void step(float dt) {
        if (dropCooldown > 0f) dropCooldown -= dt;
        accumulator += Math.min(dt, 0.05f);
        int steps = 0;
        while (accumulator >= PhysicsConfig.FIXED_DT && steps < PhysicsConfig.MAX_SUB_STEPS) {
            physics.tick();
            accumulator -= PhysicsConfig.FIXED_DT;
            steps++;
        }
    }

    @Override
    public void reset() {
        physics = new Jar3DPhysics(System.nanoTime());
        accumulator = 0;
        dropCooldown = 0f;
    }

    @Override
    public List<Ball> fruits() {
        List<Ball> out = new ArrayList<>();
        for (Jar3DPhysics.Ball b : physics.balls()) {
            out.add(new Ball((float) b.x, (float) b.y, (float) b.z, (float) b.radius, b.tier));
        }
        return out;
    }

    @Override public List<MergeInfo> drainMerges() { return physics.drainMerges(); }

    @Override public FruitTier currentTier() { return physics.currentTier(); }
    @Override public FruitTier nextTier()    { return physics.nextTier(); }
    @Override public float hoverX()          { return hoverX; }
    @Override public float hoverZ()          { return hoverZ; }
    @Override public long score()            { return physics.score(); }
    @Override public boolean gameOver()      { return physics.isGameOver(); }
    @Override public String modeName()       { return "3D physics"; }
}
