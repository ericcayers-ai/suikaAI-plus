package dev.suika.game;

import com.badlogic.gdx.Gdx;

/**
 * Mutable, in-memory game configuration edited from the {@link SettingsScreen} and
 * read by every other screen. Covers the configurability the ROADMAP calls for in
 * §VII (graphics, simulation, and AI-watch knobs) without persisting to disk —
 * settings live for the duration of the session.
 */
public final class GameSettings {

    // ---- Graphics ----
    /** Selectable frame-rate caps; 0 means "unlimited". */
    public static final int[] FPS_OPTIONS = {30, 60, 90, 120, 144, 165, 200, 240, 0};
    public int     fpsIndex      = 1;     // default 60
    public boolean vsync         = true;
    public boolean smoothShading = true;  // glossy, high-segment fruit
    public boolean particles     = true;  // merge sparkle bursts
    public boolean showGuide     = true;  // drop guide line + ghost fruit
    public boolean tierLabels    = true;  // colour-blind-safe tier numbers
    public boolean screenShake   = true;  // small camera kick on big merges

    // ---- Simulation ----
    public static final int[] BIN_OPTIONS = {16, 32, 64};
    public int     binIndex   = 1;        // default 32 drop columns
    public boolean randomSeed = true;
    public long    fixedSeed  = 42L;

    // ---- AI Watch ----
    public int   agentIndex   = WatchAgents.DEFAULT_INDEX;
    public float aiMoveDelay  = 0.6f;     // seconds the agent pauses between drops
    public boolean showThinking = true;   // MCTS visit-count overlay

    // -------------------------------------------------------------------------

    public int targetFps() { return FPS_OPTIONS[fpsIndex]; }

    public String fpsLabel() {
        int f = targetFps();
        return f == 0 ? "Unlimited" : (f + " FPS");
    }

    public int actionBins() { return BIN_OPTIONS[binIndex]; }

    /** Applies frame-rate and vsync settings to the live LWJGL3 window. */
    public void applyDisplay() {
        if (Gdx.graphics != null) {
            Gdx.graphics.setForegroundFPS(targetFps());
            Gdx.graphics.setVSync(vsync);
        }
    }

    /** Resolves the seed to use for a new game, honouring the random/fixed choice. */
    public long resolveSeed() {
        return randomSeed ? System.nanoTime() : fixedSeed;
    }
}
