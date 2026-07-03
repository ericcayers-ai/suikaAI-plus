package dev.suika.game.rtlab;

import dev.suika.core.FruitTier;
import dev.suika.core.GameCore;
import dev.suika.core.MergeEvent;
import dev.suika.core.PhysicsConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Classic 2D gameplay (the real dyn4j {@link GameCore}, same rules/physics as the
 * main window) presented inside the RT jar: the 2D x∈[0,10] plane maps to world
 * x∈[-5,5] at z=0, so the familiar game plays out as a slice through the middle of
 * the glass cylinder.
 */
public final class Rt2DSession implements RtGameSession {

    private static final float DROP_COOLDOWN = 0.24f;  // matches SuikaScreen
    /** Fruits rest on the jar's glass base, a hair above the table top. */
    private static final float FLOOR_LIFT = 0.06f;

    private GameCore core;
    private double accumulator = 0;
    private float dropCooldown = 0f;
    private float hoverGameX = 5f;
    private final List<MergeInfo> pendingMerges = new ArrayList<>();

    public Rt2DSession(long seed) {
        this.core = new GameCore(seed);
    }

    @Override
    public void setPointer(float nx, float ny) {
        float gx = nx * (float) PhysicsConfig.CONTAINER_WIDTH;
        double radius = core.currentFruitRadius();
        // Two clamps: the 2D engine's own wall clamp, then the jar-mouth clamp —
        // the fruit physically enters through the jar's OPENING (narrower than the
        // body), so the chute can't aim a drop outside the glass. World x = gx - 5.
        double x = GameCore.clampDropForRadius(gx, radius);
        double mouthLimit = JarShape.dropLimit(radius);
        hoverGameX = (float) Math.clamp(x, 5.0 - mouthLimit, 5.0 + mouthLimit);
    }

    @Override
    public void drop() {
        if (core.isGameOver() || dropCooldown > 0f || !chuteClear()) return;
        core.spawnDrop(hoverGameX);
        dropCooldown = DROP_COOLDOWN;
    }

    private boolean chuteClear() {
        double thresh = PhysicsConfig.CONTAINER_HEIGHT - 1.0;
        for (var f : core.getState().fruits()) {
            if (f.y() + f.radius() > thresh) return false;
        }
        return true;
    }

    @Override
    public void step(float dt) {
        if (dropCooldown > 0f) dropCooldown -= dt;
        accumulator += Math.min(dt, 0.05f);
        int steps = 0;
        while (accumulator >= PhysicsConfig.FIXED_DT && steps < PhysicsConfig.MAX_SUB_STEPS) {
            List<MergeEvent> merges = core.tick();
            for (MergeEvent m : merges) {
                if (m.resultTier() == null) continue; // double-watermelon: no ball left to flash
                pendingMerges.add(new MergeInfo(
                        (float) m.spawnX() - 5f, (float) m.spawnY() + FLOOR_LIFT, 0f, m.resultTier()));
            }
            accumulator -= PhysicsConfig.FIXED_DT;
            steps++;
        }
    }

    @Override
    public List<MergeInfo> drainMerges() {
        if (pendingMerges.isEmpty()) return List.of();
        List<MergeInfo> out = new ArrayList<>(pendingMerges);
        pendingMerges.clear();
        return out;
    }

    @Override
    public void reset() {
        core = new GameCore(System.nanoTime());
        accumulator = 0;
        dropCooldown = 0f;
        pendingMerges.clear();
    }

    @Override
    public List<Ball> fruits() {
        List<Ball> out = new ArrayList<>();
        for (var f : core.getState().fruits()) {
            out.add(new Ball((float) f.x() - 5f, (float) f.y() + FLOOR_LIFT, 0f, f.tier().radius, f.tier()));
        }
        return out;
    }

    @Override public FruitTier currentTier() { return core.getState().currentFruitTier(); }
    @Override public FruitTier nextTier()    { return core.getState().nextFruitTier(); }
    @Override public float hoverX()          { return hoverGameX - 5f; }
    @Override public float hoverZ()          { return 0f; }
    @Override public long score()            { return core.getScore(); }
    @Override public boolean gameOver()      { return core.isGameOver(); }
    @Override public String modeName()       { return "2D physics"; }
}
