package dev.suika.game.rtlab;

import dev.suika.core.FruitTier;

import java.util.List;

/**
 * One playable game driven by the RT Lab window — either the classic 2D engine
 * rendered in 3D ({@link Rt2DSession}) or true 3D physics inside the jar
 * ({@link Rt3DSession}). All coordinates handed out are RT WORLD units: the jar's
 * axis is x=z=0, the table top is y=0 (see {@link RtScene} for the layout).
 */
public interface RtGameSession {

    /** A fruit sphere to render, in world coordinates. */
    record Ball(float x, float y, float z, float radius, FruitTier tier) {}

    /** A merge that happened during the session's most recent {@link #step}, in RT
     *  world coordinates (already translated the same way {@link #fruits()} is) —
     *  drives {@link RtMergeFx}'s particle burst + coalesce flash. */
    record MergeInfo(float x, float y, float z, FruitTier resultTier) {}

    /** Merges since the last call, then clears them — call once per frame after
     *  {@link #step}. Empty list, never null, when nothing merged. */
    List<MergeInfo> drainMerges();

    /** Pointer position normalized to the window: x,y in 0..1, y down (GLFW cursor). */
    void setPointer(float nx, float ny);

    /** Drop the current fruit at the hover position (no-op while on cooldown,
     *  while the chute is blocked, or after game over). */
    void drop();

    /** Advance simulation by real elapsed seconds (fixed-step accumulated inside). */
    void step(float dt);

    /** Start a fresh game. */
    void reset();

    List<Ball> fruits();

    FruitTier currentTier();
    FruitTier nextTier();

    /** Hover position of the not-yet-dropped fruit, world coords. */
    float hoverX();
    float hoverZ();

    long score();
    boolean gameOver();

    /** Short label for the window title ("2D physics" / "3D physics"). */
    String modeName();
}
