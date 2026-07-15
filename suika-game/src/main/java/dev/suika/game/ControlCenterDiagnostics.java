package dev.suika.game;

/**
 * Diagnostics panel geometry helpers for {@link ControlCenterScreen}.
 * Drawing stays on the screen; this owns portrait/landscape panel bounds.
 */
public final class ControlCenterDiagnostics {

    private ControlCenterDiagnostics() {}

    /** Portrait / landscape side panel {@code {x, y, w, h}}. */
    public static float[] panelBounds(boolean landscape) {
        if (landscape) return new float[]{12f, 72f, 560f, Theme.VH_L - 90f};
        return new float[]{26f, 980f, Theme.VW - 52f, 286f};
    }
}
