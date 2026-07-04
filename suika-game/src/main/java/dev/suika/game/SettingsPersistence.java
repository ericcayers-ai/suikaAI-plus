package dev.suika.game;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Preferences;

/**
 * Persists just the display settings (§VI of the roadmap: resolution, fullscreen,
 * UI scale) across launches via LibGDX's cross-platform {@link Preferences} store —
 * everything else in {@link GameSettings} stays session-only by design (see its
 * class doc), so this intentionally does NOT round-trip the whole object.
 */
final class SettingsPersistence {

    private static final String PREFS_NAME = "suika-display-settings";
    private static final String KEY_RES_HEIGHT_INDEX = "resHeightIndex";
    private static final String KEY_FULLSCREEN        = "fullscreen";
    private static final String KEY_UI_SCALE_INDEX    = "uiScaleIndex";
    private static final String KEY_PREFER_GPU        = "preferGpu";
    private static final String KEY_AUTOSAVE_INDEX    = "autosaveIndex";

    private SettingsPersistence() {}

    /** Loads persisted display settings into {@code cfg}, clamping any stored index
     *  that's fallen outside the current option arrays (e.g. after a future update
     *  shrinks them) back into range instead of throwing. */
    static void load(GameSettings cfg) {
        Preferences p = Gdx.app.getPreferences(PREFS_NAME);
        cfg.resHeightIndex = clamp(p.getInteger(KEY_RES_HEIGHT_INDEX, cfg.resHeightIndex),
                GameSettings.RES_HEIGHTS.length);
        cfg.fullscreen = p.getBoolean(KEY_FULLSCREEN, cfg.fullscreen);
        cfg.uiScaleIndex = clamp(p.getInteger(KEY_UI_SCALE_INDEX, cfg.uiScaleIndex),
                GameSettings.UI_SCALE_OPTIONS.length);
        cfg.preferGpu = p.getBoolean(KEY_PREFER_GPU, cfg.preferGpu);
        cfg.autosaveIndex = clamp(p.getInteger(KEY_AUTOSAVE_INDEX, cfg.autosaveIndex),
                GameSettings.AUTOSAVE_MINUTES.length);
    }

    /** Writes the current display settings to disk immediately — cheap enough (three
     *  small values) to call on every change rather than batching or debouncing. */
    static void save(GameSettings cfg) {
        Preferences p = Gdx.app.getPreferences(PREFS_NAME);
        p.putInteger(KEY_RES_HEIGHT_INDEX, cfg.resHeightIndex);
        p.putBoolean(KEY_FULLSCREEN, cfg.fullscreen);
        p.putInteger(KEY_UI_SCALE_INDEX, cfg.uiScaleIndex);
        p.putBoolean(KEY_PREFER_GPU, cfg.preferGpu);
        p.putInteger(KEY_AUTOSAVE_INDEX, cfg.autosaveIndex);
        p.flush();
    }

    private static int clamp(int i, int len) { return Math.max(0, Math.min(i, len - 1)); }
}
