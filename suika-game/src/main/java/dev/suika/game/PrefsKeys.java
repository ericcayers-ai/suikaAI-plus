package dev.suika.game;

/**
 * Frozen LibGDX {@code Preferences} key names for display / AI-environment settings.
 * {@link SettingsPersistence} is the only writer; do not rename keys without a
 * load-time migration (see docs/contracts.md).
 *
 * <p>New keys may be <em>added</em>; never rename or remove an existing constant's
 * string value. Workflow-redesign expanded durable prefs beyond the original display
 * subset — load still accepts missing keys via defaults.
 */
public final class PrefsKeys {

    private PrefsKeys() {}

    /** Preferences store name. */
    public static final String STORE = "suika-display-settings";

    public static final String RES_HEIGHT_INDEX = "resHeightIndex";
    public static final String FULLSCREEN       = "fullscreen";
    public static final String UI_SCALE_INDEX   = "uiScaleIndex";
    public static final String PREFER_GPU       = "preferGpu";
    public static final String AUTOSAVE_INDEX   = "autosaveIndex";
    public static final String JVM_CPU_ONLY     = "jvmCpuOnly";
    public static final String GPU_MODE         = "gpuMode";
    public static final String CUSTOM_ENTRY     = "customValueEntry";
    public static final String WATCHDOG         = "watchdogEnabled";
    public static final String CALIBRATED       = "presetsCalibrated";
    public static final String CALIB_SIMS       = "presetsSimsPerSec";
    /** Prefer shorter / zero-duration UI motion (toasts still show; animations skip). */
    public static final String REDUCED_MOTION   = "reducedMotion";

    // ---- Expanded durable prefs (workflow-redesign); additive only ----
    public static final String FPS_INDEX        = "fpsIndex";
    public static final String VSYNC            = "vsync";
    public static final String SMOOTH_SHADING   = "smoothShading";
    public static final String PARTICLES        = "particles";
    public static final String SHOW_GUIDE       = "showGuide";
    public static final String TIER_LABELS      = "tierLabels";
    public static final String SCREEN_SHAKE     = "screenShake";
    public static final String BIN_INDEX        = "binIndex";
    public static final String RANDOM_SEED      = "randomSeed";
    public static final String FIXED_SEED       = "fixedSeed";
    public static final String IMMEDIATE_DEADLINE = "immediateDeadline";
    public static final String BOUNCE_ENABLED   = "bounceEnabled";
    public static final String RT_3D_PHYSICS    = "rt3dPhysics";
    public static final String GPU_UTIL_PERCENT = "gpuUtilPercent";
    public static final String AGENT_INDEX      = "agentIndex";
    public static final String AI_MOVE_DELAY    = "aiMoveDelay";
    public static final String SHOW_THINKING    = "showThinking";
    /** First-run help overlay dismissed on main menu. */
    public static final String FIRST_RUN_HELP_SEEN = "firstRunHelpSeen";
}
