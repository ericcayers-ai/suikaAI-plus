package dev.suika.game;

import com.badlogic.gdx.Input;

/**
 * Shared keyboard action map so classic play, Control Center, and RT Lab agree
 * on the cheap bindings:
 * <ul>
 *   <li>{@code P} — pause / resume everywhere</li>
 *   <li>{@code Esc} — pause (classic) / dismiss modal / back</li>
 *   <li>{@code Space} — drop when the surface accepts human drops; pause otherwise</li>
 *   <li>{@code R} — restart</li>
 *   <li>Arrows — aim (Left/Right) and drop (Down) on human-input boards</li>
 * </ul>
 */
public final class UiKeys {

    private UiKeys() {}

    public static boolean isPause(int keycode) {
        return keycode == Input.Keys.P;
    }

    public static boolean isRestart(int keycode) {
        return keycode == Input.Keys.R;
    }

    public static boolean isDrop(int keycode) {
        return keycode == Input.Keys.SPACE || keycode == Input.Keys.DOWN;
    }

    public static boolean isBackOrDismiss(int keycode) {
        return keycode == Input.Keys.ESCAPE;
    }

    public static boolean isSpeedUp(int keycode) {
        return keycode == Input.Keys.EQUALS || keycode == Input.Keys.PLUS;
    }

    public static boolean isSpeedDown(int keycode) {
        return keycode == Input.Keys.MINUS;
    }

    /**
     * On surfaces that already bind Space to pause (Control Center), Space stays
     * pause and Down remains the keyboard drop. Classic SuikaScreen keeps Space as
     * drop because Esc is pause there.
     */
    public static boolean dropKeyForHumanBoard(int keycode, boolean spaceIsPause) {
        if (keycode == Input.Keys.DOWN) return true;
        if (keycode == Input.Keys.SPACE) return !spaceIsPause;
        return false;
    }
}
