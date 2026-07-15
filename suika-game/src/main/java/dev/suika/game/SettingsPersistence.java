package dev.suika.game;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Preferences;

/**
 * Persists display / AI-environment / gameplay settings via LibGDX {@link Preferences}.
 * Keys are frozen in {@link PrefsKeys} (docs/contracts.md). Custom numeric overrides
 * remain session-only.
 */
final class SettingsPersistence {

    private SettingsPersistence() {}

    /** Loads persisted settings into {@code cfg}, clamping indices and accepting
     *  missing keys (defaults stay). */
    static void load(GameSettings cfg) {
        Preferences p = Gdx.app.getPreferences(PrefsKeys.STORE);
        cfg.resHeightIndex = clamp(p.getInteger(PrefsKeys.RES_HEIGHT_INDEX, cfg.resHeightIndex),
                GameSettings.RES_HEIGHTS.length);
        cfg.fullscreen = p.getBoolean(PrefsKeys.FULLSCREEN, cfg.fullscreen);
        cfg.uiScaleIndex = clamp(p.getInteger(PrefsKeys.UI_SCALE_INDEX, cfg.uiScaleIndex),
                GameSettings.UI_SCALE_OPTIONS.length);
        cfg.preferGpu = p.getBoolean(PrefsKeys.PREFER_GPU, cfg.preferGpu);
        cfg.autosaveIndex = clamp(p.getInteger(PrefsKeys.AUTOSAVE_INDEX, cfg.autosaveIndex),
                GameSettings.AUTOSAVE_MINUTES.length);
        cfg.jvmCpuOnly = p.getBoolean(PrefsKeys.JVM_CPU_ONLY, cfg.jvmCpuOnly);
        // Compute mode: migrate from the old two-flag world on first run (GPU mode ==
        // preferGpu && !jvmCpuOnly), then let the persisted value win thereafter.
        cfg.gpuMode = p.getBoolean(PrefsKeys.GPU_MODE, cfg.preferGpu && !cfg.jvmCpuOnly);
        cfg.applyComputeMode();   // re-derive preferGpu/jvmCpuOnly so all three agree
        cfg.customValueEntry = p.getBoolean(PrefsKeys.CUSTOM_ENTRY, cfg.customValueEntry);
        cfg.watchdogEnabled  = p.getBoolean(PrefsKeys.WATCHDOG, cfg.watchdogEnabled);
        cfg.reducedMotion    = p.getBoolean(PrefsKeys.REDUCED_MOTION, cfg.reducedMotion);
        PresetCalibration.restore(p.getBoolean(PrefsKeys.CALIBRATED, false),
                (double) p.getFloat(PrefsKeys.CALIB_SIMS, 0f));

        cfg.fpsIndex = clamp(p.getInteger(PrefsKeys.FPS_INDEX, cfg.fpsIndex),
                GameSettings.FPS_OPTIONS.length);
        cfg.vsync = p.getBoolean(PrefsKeys.VSYNC, cfg.vsync);
        cfg.smoothShading = p.getBoolean(PrefsKeys.SMOOTH_SHADING, cfg.smoothShading);
        cfg.particles = p.getBoolean(PrefsKeys.PARTICLES, cfg.particles);
        cfg.showGuide = p.getBoolean(PrefsKeys.SHOW_GUIDE, cfg.showGuide);
        cfg.tierLabels = p.getBoolean(PrefsKeys.TIER_LABELS, cfg.tierLabels);
        cfg.screenShake = p.getBoolean(PrefsKeys.SCREEN_SHAKE, cfg.screenShake);
        cfg.binIndex = clamp(p.getInteger(PrefsKeys.BIN_INDEX, cfg.binIndex),
                GameSettings.BIN_OPTIONS.length);
        cfg.randomSeed = p.getBoolean(PrefsKeys.RANDOM_SEED, cfg.randomSeed);
        cfg.fixedSeed = p.getLong(PrefsKeys.FIXED_SEED, cfg.fixedSeed);
        cfg.immediateDeadline = p.getBoolean(PrefsKeys.IMMEDIATE_DEADLINE, cfg.immediateDeadline);
        cfg.bounceEnabled = p.getBoolean(PrefsKeys.BOUNCE_ENABLED, cfg.bounceEnabled);
        cfg.rt3dPhysics = p.getBoolean(PrefsKeys.RT_3D_PHYSICS, cfg.rt3dPhysics);
        cfg.gpuUtilPercent = Math.max(10, Math.min(100,
                p.getInteger(PrefsKeys.GPU_UTIL_PERCENT, cfg.gpuUtilPercent)));
        cfg.agentIndex = p.getInteger(PrefsKeys.AGENT_INDEX, cfg.agentIndex);
        cfg.aiMoveDelay = p.getFloat(PrefsKeys.AI_MOVE_DELAY, cfg.aiMoveDelay);
        cfg.showThinking = p.getBoolean(PrefsKeys.SHOW_THINKING, cfg.showThinking);
        cfg.firstRunHelpSeen = p.getBoolean(PrefsKeys.FIRST_RUN_HELP_SEEN, cfg.firstRunHelpSeen);
        cfg.applyPhysics();
    }

    /** Persists a completed preset calibration (called from the benchmark thread). */
    static void saveCalibration(boolean calibrated, double simsPerSec) {
        Preferences p = Gdx.app.getPreferences(PrefsKeys.STORE);
        p.putBoolean(PrefsKeys.CALIBRATED, calibrated);
        p.putFloat(PrefsKeys.CALIB_SIMS, (float) simsPerSec);
        p.flush();
    }

    /** Writes current durable settings to disk immediately. */
    static void save(GameSettings cfg) {
        Preferences p = Gdx.app.getPreferences(PrefsKeys.STORE);
        p.putInteger(PrefsKeys.RES_HEIGHT_INDEX, cfg.resHeightIndex);
        p.putBoolean(PrefsKeys.FULLSCREEN, cfg.fullscreen);
        p.putInteger(PrefsKeys.UI_SCALE_INDEX, cfg.uiScaleIndex);
        p.putBoolean(PrefsKeys.PREFER_GPU, cfg.preferGpu);
        p.putInteger(PrefsKeys.AUTOSAVE_INDEX, cfg.autosaveIndex);
        p.putBoolean(PrefsKeys.JVM_CPU_ONLY, cfg.jvmCpuOnly);
        p.putBoolean(PrefsKeys.GPU_MODE, cfg.gpuMode);
        p.putBoolean(PrefsKeys.CUSTOM_ENTRY, cfg.customValueEntry);
        p.putBoolean(PrefsKeys.WATCHDOG, cfg.watchdogEnabled);
        p.putBoolean(PrefsKeys.REDUCED_MOTION, cfg.reducedMotion);

        p.putInteger(PrefsKeys.FPS_INDEX, cfg.fpsIndex);
        p.putBoolean(PrefsKeys.VSYNC, cfg.vsync);
        p.putBoolean(PrefsKeys.SMOOTH_SHADING, cfg.smoothShading);
        p.putBoolean(PrefsKeys.PARTICLES, cfg.particles);
        p.putBoolean(PrefsKeys.SHOW_GUIDE, cfg.showGuide);
        p.putBoolean(PrefsKeys.TIER_LABELS, cfg.tierLabels);
        p.putBoolean(PrefsKeys.SCREEN_SHAKE, cfg.screenShake);
        p.putInteger(PrefsKeys.BIN_INDEX, cfg.binIndex);
        p.putBoolean(PrefsKeys.RANDOM_SEED, cfg.randomSeed);
        p.putLong(PrefsKeys.FIXED_SEED, cfg.fixedSeed);
        p.putBoolean(PrefsKeys.IMMEDIATE_DEADLINE, cfg.immediateDeadline);
        p.putBoolean(PrefsKeys.BOUNCE_ENABLED, cfg.bounceEnabled);
        p.putBoolean(PrefsKeys.RT_3D_PHYSICS, cfg.rt3dPhysics);
        p.putInteger(PrefsKeys.GPU_UTIL_PERCENT, cfg.gpuUtilPercent);
        p.putInteger(PrefsKeys.AGENT_INDEX, cfg.agentIndex);
        p.putFloat(PrefsKeys.AI_MOVE_DELAY, cfg.aiMoveDelay);
        p.putBoolean(PrefsKeys.SHOW_THINKING, cfg.showThinking);
        p.putBoolean(PrefsKeys.FIRST_RUN_HELP_SEEN, cfg.firstRunHelpSeen);
        p.flush();
    }

    /** Clears durable prefs and reloads factory defaults into {@code cfg}. */
    static void resetToDefaults(GameSettings cfg) {
        Preferences p = Gdx.app.getPreferences(PrefsKeys.STORE);
        p.clear();
        p.flush();
        cfg.applyFactoryDefaults();
        // Preserve calibration if it was measured this session — reset settings should
        // not force a recalibration; only wipe when never calibrated.
        if (PresetCalibration.calibrated())
            saveCalibration(true, PresetCalibration.simsPerSec());
        save(cfg);
    }

    private static int clamp(int i, int len) { return Math.max(0, Math.min(i, len - 1)); }
}
