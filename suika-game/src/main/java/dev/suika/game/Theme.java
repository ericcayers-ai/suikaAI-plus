package dev.suika.game;

import com.badlogic.gdx.graphics.Color;

/**
 * Centralised colour palette and layout constants for the windowed game.
 *
 * <p>Colours are chosen for a calm dark "lab" aesthetic with a warm watermelon
 * accent, and to remain legible alongside the colour-blind-safe fruit labels.
 */
public final class Theme {

    private Theme() {}

    // --- Virtual canvas (all rendering happens in this fixed pixel space) ---
    public static final float VW = 720f;
    public static final float VH = 1280f;

    // --- Backdrop ---
    public static final Color BG_TOP    = new Color(0.09f, 0.10f, 0.16f, 1f);
    public static final Color BG_BOTTOM = new Color(0.05f, 0.06f, 0.10f, 1f);
    public static final Color VIGNETTE  = new Color(0f, 0f, 0f, 0.35f);

    // --- Panels ---
    public static final Color PANEL      = new Color(0.14f, 0.16f, 0.23f, 0.92f);
    public static final Color PANEL_EDGE = new Color(0.30f, 0.34f, 0.46f, 1f);
    public static final Color PANEL_DEEP = new Color(0.10f, 0.11f, 0.17f, 0.95f);

    // --- Container ---
    public static final Color WALL       = new Color(0.22f, 0.25f, 0.34f, 1f);
    public static final Color WALL_HI    = new Color(0.34f, 0.38f, 0.50f, 1f);
    public static final Color WELL       = new Color(0.11f, 0.12f, 0.18f, 1f);

    // --- Accents ---
    public static final Color ACCENT      = new Color(0.96f, 0.30f, 0.34f, 1f); // watermelon red
    public static final Color ACCENT_2    = new Color(0.20f, 0.78f, 0.46f, 1f); // melon green
    public static final Color ACCENT_BLUE = new Color(0.30f, 0.62f, 0.96f, 1f);
    public static final Color GOLD        = new Color(0.98f, 0.78f, 0.22f, 1f);

    // --- Text ---
    public static final Color TEXT        = new Color(0.93f, 0.95f, 0.99f, 1f);
    public static final Color TEXT_DIM    = new Color(0.62f, 0.66f, 0.78f, 1f);
    public static final Color TEXT_FAINT  = new Color(0.40f, 0.44f, 0.56f, 1f);

    // --- Dead-line ---
    public static final Color DEADLINE      = new Color(0.95f, 0.36f, 0.40f, 0.55f);
    public static final Color DEADLINE_WARN = new Color(1.00f, 0.30f, 0.28f, 0.95f);

    /** Linear interpolate two colours into {@code out} (not allocating). */
    public static Color lerp(Color a, Color b, float t, Color out) {
        out.r = a.r + (b.r - a.r) * t;
        out.g = a.g + (b.g - a.g) * t;
        out.b = a.b + (b.b - a.b) * t;
        out.a = a.a + (b.a - a.a) * t;
        return out;
    }
}
