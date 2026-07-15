package dev.suika.game;

import com.badlogic.gdx.Gdx;

/**
 * Mutable, in-memory game configuration edited from the {@link SettingsScreen} and
 * read by every other screen. Covers graphics, simulation, and AI-watch knobs.
 *
 * <p>Display, graphics, simulation, AI-environment, RT Lab, and input prefs round-trip
 * via {@link SettingsPersistence} / {@link PrefsKeys}. Custom numeric overrides
 * ({@link #customFps}, {@link #customBins}, {@link #customAutosaveMinutes}) stay
 * session-only.
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
        // Instant-fail is a core-physics gameplay rule now (drives GameCore's dead-line
        // check app-wide: champion, ghosts, human play, RT lab), not just a UI delay skip.
        dev.suika.core.PhysicsConfig.instantFail = immediateDeadline;
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

    /**
     * Global "prefer GPU acceleration" preference. When on, every Python-backed technique
     * (PPO, MuZero, Decision Transformer, AlphaZero's net) trains/infers on the detected
     * CUDA device instead of the CPU — not just PPO. JVM-native techniques run dyn4j
     * physics + small hand-rolled MLPs and have no CUDA binding in this project, so the
     * UI honestly reports them as CPU-only regardless (fabricating GPU use would be a
     * lie). Persisted across launches. See {@link GpuProbe#gpuUsableFor}.
     */
    public boolean preferGpu = false;

    /**
     * Force the JVM CPU-only implementations for everything, even when GPU inference deps
     * are installed. When {@code false} (default) and the GPU stack is ready, net-based
     * techniques run inference through the GPU (Python/ONNX) path and parallelism defaults
     * to the GPU rather than a CPU thread fan-out. Turn this on to keep the fast,
     * dependency-free JVM path. Persisted.
     */
    public boolean jvmCpuOnly = false;

    public void applyGpuPreference() {
        GpuProbe.setPreferGpu(preferGpu);
        GpuProbe.setJvmCpuOnly(jvmCpuOnly);
    }

    /**
     * First-class compute mode replacing the two separate GPU toggles in the UI.
     * {@code true} = GPU mode: every AI implementation prefers the Python/CUDA path
     * when a CUDA device is detected (app-wide GPU priority). {@code false} = CPU
     * mode: the original JVM implementations (or JVM+Python where a technique is
     * Python-only). The legacy {@link #preferGpu}/{@link #jvmCpuOnly} flags are kept
     * and derived from this so every existing call site (GpuProbe, PlaygroundConfig
     * labels, ControlCenterScreen, PythonRunner…) keeps working unchanged. Persisted.
     */
    public boolean gpuMode = false;

    /** Maps {@link #gpuMode} onto the legacy {@link #preferGpu}/{@link #jvmCpuOnly}
     *  flags and pushes the result into {@link GpuProbe}. Call after changing
     *  {@code gpuMode}. Note GPU mode still falls back to CPU at runtime when no
     *  CUDA device is detected — {@link GpuProbe} gates on {@code available()}. */
    public void applyComputeMode() {
        preferGpu  = gpuMode;
        jvmCpuOnly = !gpuMode;
        applyGpuPreference();
    }

    /** True when GPU inference should actually be used: deps ready, user hasn't forced
     *  CPU-only, and (for the labels) a CUDA device is present. */
    public boolean gpuInferenceActive() {
        return !jvmCpuOnly && preferGpu && Boolean.TRUE.equals(GpuProbe.available());
    }

    // ---- Custom numeric entry (persisted toggle; overrides are session-only like
    // the array indices they shadow) ----
    /** When on, clicking a numeric settings row in {@link SettingsScreen} opens a
     *  small type-a-number overlay instead of only cycling preset options. Persisted. */
    public boolean customValueEntry = false;

    /** Stuck-run watchdog: back out of a hung AI run after 10s. Exposed here for
     *  other screens to read; not wired by the settings screen itself. Persisted. */
    public boolean watchdogEnabled = true;

    /** Prefer reduced / zero-duration motion (focus pulses, toast fades, shake).
     *  Persisted. Screens consult {@link Theme#motion(float, boolean)}. */
    public boolean reducedMotion = false;

    /** Main-menu first-run help overlay has been dismissed. Persisted. */
    public boolean firstRunHelpSeen = false;

    // Typed overrides shadow the preset arrays. Sentinel -1 = "no override, use the
    // array index"; any value >= 0 is a live custom value (so 0 = unlimited FPS / OFF
    // autosave are both reachable). Session-only, like the indices they shadow.
    /** Typed FPS-cap override in Hz (0 = unlimited), or -1 to use {@link #FPS_OPTIONS}[fpsIndex]. */
    public int customFps = -1;
    /** Typed drop-column override, or -1 to use {@link #BIN_OPTIONS}[binIndex]. */
    public int customBins = -1;
    /** Typed autosave-minutes override (0 = OFF), or -1 to use {@link #AUTOSAVE_MINUTES}[autosaveIndex]. */
    public int customAutosaveMinutes = -1;

    /** Sets {@link #aiMoveDelay} clamped to the safe 0.05–5 s range (used by the
     *  custom numeric entry; the field itself stays public for existing readers). */
    public void setAiMoveDelayClamped(double seconds) {
        aiMoveDelay = (float) Math.max(0.05, Math.min(5.0, seconds));
    }

    /** Clears every custom numeric override so the preset cyclers drive the values again. */
    public void clearCustomOverrides() {
        customFps = -1; customBins = -1; customAutosaveMinutes = -1;
    }

    // ---- Autosave ----
    /** Autosave interval options in minutes; 0 = OFF. Persisted. When on, the AI control
     *  center periodically saves the running technique's progress into slot 1 so a long
     *  unattended training run survives a crash/close without a manual SAVES click. */
    public static final int[] AUTOSAVE_MINUTES = {0, 1, 5, 10, 30};
    public int autosaveIndex = 0;   // default OFF
    public int autosaveMinutes() {
        return customAutosaveMinutes >= 0 ? customAutosaveMinutes : AUTOSAVE_MINUTES[autosaveIndex];
    }
    public String autosaveLabel() {
        int m = autosaveMinutes();
        String custom = customAutosaveMinutes >= 0 ? " *" : "";
        return (m == 0 ? "Off" : "Every " + m + " min") + custom;
    }

    // -------------------------------------------------------------------------

    public int targetFps() { return customFps >= 0 ? customFps : FPS_OPTIONS[fpsIndex]; }

    public String fpsLabel() {
        int f = targetFps();
        String custom = customFps >= 0 ? " *" : "";
        return (f == 0 ? "Unlimited" : (f + " FPS")) + custom;
    }

    public int actionBins() { return customBins >= 0 ? customBins : BIN_OPTIONS[binIndex]; }
    public String binsLabel() { return actionBins() + (customBins >= 0 ? " *" : ""); }

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

    /**
     * Restores in-memory fields to factory defaults (does not touch Preferences —
     * call {@link SettingsPersistence#resetToDefaults} for a durable reset).
     */
    public void applyFactoryDefaults() {
        fpsIndex = 1;
        vsync = true;
        smoothShading = true;
        particles = true;
        showGuide = true;
        tierLabels = true;
        screenShake = true;
        binIndex = 1;
        randomSeed = true;
        fixedSeed = 42L;
        immediateDeadline = false;
        bounceEnabled = false;
        resHeightIndex = 3;
        fullscreen = false;
        uiScaleIndex = 1;
        rt3dPhysics = false;
        agentIndex = WatchAgents.DEFAULT_INDEX;
        aiMoveDelay = 0.6f;
        showThinking = true;
        gpuUtilPercent = 100;
        preferGpu = false;
        jvmCpuOnly = false;
        gpuMode = false;
        applyComputeMode();
        customValueEntry = false;
        watchdogEnabled = true;
        reducedMotion = false;
        firstRunHelpSeen = false;
        clearCustomOverrides();
        autosaveIndex = 0;
        applyPhysics();
    }
}
