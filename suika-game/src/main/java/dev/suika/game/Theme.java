package dev.suika.game;

import com.badlogic.gdx.graphics.Color;

/**
 * Centralised colour palette and layout constants for the windowed game.
 *
 * <p>The look is a deliberate "watermelon laboratory" identity rather than a generic dark
 * dashboard: a warm plum-ink backdrop (not the usual blue-grey), panels lit with a faint
 * violet edge, and a fruit-derived accent trio — flesh pink-red, rind green, warm seed
 * gold — pulled straight from the thing you're actually stacking. Tuned to stay legible
 * against the colour-blind-safe fruit labels and to read the same in the 2D game, the
 * control center, and the RT Lab HUD.
 */
public final class Theme {

    private Theme() {}

    /** Single source of truth for the displayed app version (keep in sync with build.gradle.kts). */
    public static final String VERSION = "0.17.1";

    // --- Virtual canvas (all rendering happens in this fixed pixel space) ---
    public static final float VW   = 720f;
    public static final float VH   = 1280f;
    public static final float VW_L = 1280f;  // landscape virtual width
    public static final float VH_L = 720f;   // landscape virtual height

    // --- Backdrop (warm plum-ink, faintly purple — the signature away from generic navy) ---
    public static final Color BG_TOP    = new Color(0.12f, 0.10f, 0.17f, 1f);
    public static final Color BG_BOTTOM = new Color(0.06f, 0.05f, 0.09f, 1f);
    public static final Color VIGNETTE  = new Color(0f, 0f, 0f, 0.38f);

    // --- Panels (warm slate; a violet-lit edge gives them a distinctive rim) ---
    public static final Color PANEL      = new Color(0.16f, 0.15f, 0.22f, 0.94f);
    public static final Color PANEL_EDGE = new Color(0.42f, 0.35f, 0.54f, 1f);
    public static final Color PANEL_DEEP = new Color(0.11f, 0.10f, 0.16f, 0.96f);

    // --- Container ---
    public static final Color WALL       = new Color(0.25f, 0.23f, 0.33f, 1f);
    public static final Color WALL_HI    = new Color(0.42f, 0.38f, 0.54f, 1f);
    public static final Color WELL       = new Color(0.10f, 0.09f, 0.15f, 1f);

    // --- Accents (drawn from the fruit: flesh, rind, seed) ---
    public static final Color ACCENT      = new Color(0.98f, 0.26f, 0.42f, 1f); // watermelon flesh
    public static final Color ACCENT_2    = new Color(0.30f, 0.82f, 0.46f, 1f); // rind green
    public static final Color ACCENT_BLUE = new Color(0.36f, 0.66f, 0.99f, 1f); // cool signal
    public static final Color GOLD        = new Color(0.99f, 0.80f, 0.30f, 1f); // seed gold

    /** Soft, low-alpha flesh tint for glows / hover fills / title underlines — the accent
     *  used as a wash rather than a line, so highlights feel lit instead of outlined. */
    public static final Color ACCENT_SOFT = new Color(0.98f, 0.26f, 0.42f, 0.16f);
    /** Drop-shadow wash for lifting panels/cards off the backdrop. */
    public static final Color SHADOW      = new Color(0f, 0f, 0f, 0.34f);

    // --- Text (crisp near-white; dim shifts cool-lavender for calm hierarchy) ---
    public static final Color TEXT        = new Color(0.95f, 0.96f, 0.99f, 1f);
    public static final Color TEXT_DIM    = new Color(0.66f, 0.67f, 0.80f, 1f);
    public static final Color TEXT_FAINT  = new Color(0.44f, 0.45f, 0.58f, 1f);

    // --- Dead-line ---
    public static final Color DEADLINE      = new Color(0.98f, 0.34f, 0.44f, 0.55f);
    public static final Color DEADLINE_WARN = new Color(1.00f, 0.28f, 0.30f, 0.95f);

    /** Linear interpolate two colours into {@code out} (not allocating). */
    public static Color lerp(Color a, Color b, float t, Color out) {
        out.r = a.r + (b.r - a.r) * t;
        out.g = a.g + (b.g - a.g) * t;
        out.b = a.b + (b.b - a.b) * t;
        out.a = a.a + (b.a - a.a) * t;
        return out;
    }
}
