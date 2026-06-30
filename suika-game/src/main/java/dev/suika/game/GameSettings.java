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
    public int     binIndex        = 1;     // default 32 drop columns
    public boolean randomSeed      = true;
    public long    fixedSeed       = 42L;
    /** When true the game ends the instant any fruit touches the deadline. */
    public boolean immediateDeadline = false;

    // ---- AI Watch ----
    public int   agentIndex   = WatchAgents.DEFAULT_INDEX;
    public float aiMoveDelay  = 0.6f;     // seconds the agent pauses between drops
    public boolean showThinking = true;   // MCTS visit-count overlay

    // ---- AI Training (evolution / population learners) ----
    /**
     * Evaluation parallelism. Index 0 = "Auto" (all cores — the fastest, GPU-like
     * fan-out). Other entries pin training to a fixed worker-thread count so heavy
     * populations can't exhaust memory.
     */
    public static final int[] EVAL_THREAD_OPTIONS = {0, 1, 2, 4, 6, 8, 12, 16, 24, 32};
    public int evalThreadsIndex = 0;      // default Auto

    /**
     * Simulations (independent game-overs) averaged per genome each generation. More
     * sims = less noisy fitness, but more compute. They run <em>simultaneously</em>,
     * not one after another.
     */
    public static final int[] SIMS_PER_GEN_OPTIONS = {1, 2, 3, 5, 8};
    public int simsPerGenIndex = 0;       // default 1

    /**
     * How many generations of elites are kept alive as on-screen "ghost" boards before
     * the oldest are culled. Higher = watch more of the population's lineage diverge.
     */
    public static final int[] GHOST_CULL_OPTIONS = {1, 2, 3, 5, 8, 12};
    public int ghostCullIndex = 1;        // default 2 generations

    public int evalThreadsSetting() { return EVAL_THREAD_OPTIONS[evalThreadsIndex]; }
    public String evalThreadsLabel() {
        int t = evalThreadsSetting();
        return t == 0 ? "Auto (all cores)" : t + " threads";
    }
    public int simsPerGen()    { return SIMS_PER_GEN_OPTIONS[simsPerGenIndex]; }
    public int ghostCullGens() { return GHOST_CULL_OPTIONS[ghostCullIndex]; }

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
