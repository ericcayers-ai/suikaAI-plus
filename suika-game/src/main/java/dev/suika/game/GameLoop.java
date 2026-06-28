package dev.suika.game;

import dev.suika.core.GameCore;
import dev.suika.core.PhysicsConfig;

/**
 * Fixed-timestep game loop (decoupled from rendering).
 *
 * <p>The accumulator pattern ensures physics always advances in exact {@code dt}-sized
 * chunks regardless of wall-clock frame rate, preserving determinism.
 * Rendering receives an interpolation alpha for smooth motion.
 *
 * <p>Usage: call {@link #tick(long)} each frame with the wall-clock nanosecond timestamp.
 */
public final class GameLoop {

    private final GameCore     core;
    private final GameRenderer renderer;
    private final InputHandler input;

    private long   lastNanos  = -1;
    private double accumulator = 0.0;

    private static final double MAX_FRAME_TIME = 0.25; // spiral-of-death cap

    public GameLoop(GameCore core, GameRenderer renderer, InputHandler input) {
        this.core     = core;
        this.renderer = renderer;
        this.input    = input;
    }

    /**
     * Process one wall-clock frame.
     *
     * @param nowNanos  {@code System.nanoTime()} of the current frame
     */
    public void tick(long nowNanos) {
        if (lastNanos < 0) { lastNanos = nowNanos; return; }

        double frameTime = (nowNanos - lastNanos) / 1_000_000_000.0;
        lastNanos = nowNanos;

        frameTime = Math.min(frameTime, MAX_FRAME_TIME);
        accumulator += frameTime;

        // Poll input — human's drop decision is handled per-physics-step
        Double dropX = input.pollDropAction();
        if (dropX != null && !core.isGameOver()) {
            core.dropAndSettle(dropX);
            accumulator = 0; // reset accumulator after a decision-step
        }

        // Sub-step physics for visual smoothness (if we ever decouple physics from drop)
        while (accumulator >= PhysicsConfig.FIXED_DT) {
            accumulator -= PhysicsConfig.FIXED_DT;
        }

        double alpha = accumulator / PhysicsConfig.FIXED_DT;
        renderer.render(core.getState(), alpha);
    }

    public boolean isGameOver() { return core.isGameOver(); }
}
