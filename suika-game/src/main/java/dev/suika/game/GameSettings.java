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
    public static final int[] BIN_OPTIONS = {16, 32, 64, 128};
    public int     binIndex        = 1;     // default 32 drop columns
    public boolean randomSeed      = true;
    public long    fixedSeed       = 42L;
    // ---- Experimental gameplay variants (their own labelled group in Settings —
    // genuinely optional rule changes, not a gate on any other feature) ----
    /** When true the game ends the instant any fruit touches the deadline. */
    public boolean immediateDeadline = false;

    /**
     * When true, fruit and walls bounce a little on impact instead of settling dead —
     * flips {@link dev.suika.core.PhysicsConfig#restitution} at runtime (see its javadoc
     * for why this isn't a per-{@code GameCore} constructor parameter).
     */
    public boolean bounceEnabled = false;
    private static final double BOUNCE_RESTITUTION = 0.35;

    public void applyPhysics() {
        dev.suika.core.PhysicsConfig.restitution = bounceEnabled ? BOUNCE_RESTITUTION : 0.0;
    }

    // ---- Display (persisted — see SettingsPersistence) ----
    /** Windowed-mode heights to choose from; width is derived at the 720:1280
     *  portrait aspect, matching {@code DesktopLauncher}'s own default sizing. */
    public static final int[] RES_HEIGHTS = {800, 900, 1000, 1080, 1200, 1280};
    public int     resHeightIndex = 3;      // default ~1080-tall window
    public boolean fullscreen     = false;
    /** Text-size multiplier applied to every generated font (see
     *  {@link SuikaGame#regenerateFonts()}) — kept to a modest range since labels,
     *  buttons, and panels are laid out with fixed pixel geometry, not a scale-aware
     *  layout system; a wider range would start clipping text against its container
     *  before it stopped being legible. */
    public static final float[] UI_SCALE_OPTIONS = {0.9f, 1.0f, 1.1f, 1.2f};
    public int uiScaleIndex = 1;            // default 1.0x

    public float uiScale() { return UI_SCALE_OPTIONS[uiScaleIndex]; }
    public String uiScaleLabel() { return Math.round(uiScale() * 100) + "%"; }
    public int windowHeight() { return RES_HEIGHTS[resHeightIndex]; }

    /** Applies the resolution/fullscreen choice to the live window immediately. */
    public void applyWindowMode() {
        if (Gdx.graphics == null) return;
        if (fullscreen) {
            Gdx.graphics.setFullscreenMode(Gdx.graphics.getDisplayMode());
        } else {
            int h = windowHeight();
            int w = (int) Math.round(h * 720.0 / 1280.0);
            Gdx.graphics.setWindowedMode(w, h);
        }
    }

    // ---- RT Lab ----
    /** RT Lab gameplay physics: false = classic 2D engine shown inside the jar,
     *  true = true 3D physics. A normal setting — RT Lab itself needs no toggle to
     *  unlock any more (v0.11: the RT LAB / AI PLAYS buttons are always live; a
     *  missing/incapable GPU is reported per-launch via
     *  {@link dev.suika.game.rtlab.RtLabLauncher#lastFailure()}, not gated up front). */
    public boolean rt3dPhysics = false;

    // ---- AI Watch ----
    public int   agentIndex   = WatchAgents.DEFAULT_INDEX;
    public float aiMoveDelay  = 0.6f;     // seconds the agent pauses between drops
    public boolean showThinking = true;   // MCTS visit-count overlay

    // AI training knobs (eval parallelism, simulations/generation, ghost lineage) are
    // per-technique launch config, not global settings — see PlaygroundConfig and the
    // AI Playground drawer / control-center quick-settings modal.

    /**
     * Caps how much GPU memory the Python training command is allowed to claim, as a
     * percentage (10-100). Honest about what this actually does: PyTorch/CUDA has no
     * first-class hard compute-throughput limiter, so this maps to
     * {@code torch.cuda.set_per_process_memory_fraction} (a real, working flag added to
     * {@code train_ppo.py}) — the closest real lever to "max GPU utilization" available
     * without deeper NVIDIA MPS-level tooling. Only affects the shown/copyable training
     * command for {@link AiTechnique#gpuCapableTraining()} techniques (currently PPO);
     * has no effect on any JVM-native technique, which never touches the GPU at all.
     */
    public int gpuUtilPercent = 100;   // 10-100

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
