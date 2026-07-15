package dev.suika.game;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.viewport.FitViewport;

/**
 * Shared viewport / unproject helpers for LibGDX immediate-mode screens.
 * Screens keep owning their camera; this consolidates the boilerplate that used
 * to be copy-pasted into every ScreenAdapter.
 */
public final class UiViewport {

    private UiViewport() {}

    /** Portrait FitViewport sized to {@link Theme#VW}×{@link Theme#VH}. */
    public static FitViewport portrait(OrthographicCamera camera) {
        FitViewport vp = new FitViewport(Theme.VW, Theme.VH, camera);
        camera.position.set(Theme.VW / 2f, Theme.VH / 2f, 0f);
        camera.update();
        return vp;
    }

    /** Orientation-aware FitViewport (portrait or landscape virtual canvas). */
    public static FitViewport forWindow(OrthographicCamera camera, int width, int height) {
        boolean land = Theme.isLandscapeWindow(width, height);
        float vw = Theme.virtualW(land), vh = Theme.virtualH(land);
        FitViewport vp = new FitViewport(vw, vh, camera);
        camera.position.set(vw / 2f, vh / 2f, 0f);
        camera.update();
        return vp;
    }

    /** Unprojects screen pixels into virtual coordinates via a FitViewport. */
    public static void unproject(OrthographicCamera camera, FitViewport viewport,
                                 Vector3 touch, int sx, int sy) {
        camera.unproject(touch.set(sx, sy, 0),
                viewport.getScreenX(), viewport.getScreenY(),
                viewport.getScreenWidth(), viewport.getScreenHeight());
    }

    /**
     * Orientation round-trip for Playground ↔ Control Center: launching an AI run
     * from a portrait window forces a landscape window so the control center has
     * room; backing out restores a portrait window of roughly the user's chosen
     * height when this flag was what put us into landscape.
     */
    public static final class OrientationSession {
        private static boolean forcedLandscape;

        private OrientationSession() {}

        public static boolean isForcedLandscape() { return forcedLandscape; }

        /** Called when Playground launches into landscape for the control center. */
        public static void goLandscapeForRun() {
            if (Gdx.graphics == null) return;
            if (Theme.isLandscapeWindow(Gdx.graphics.getWidth(), Gdx.graphics.getHeight())) {
                forcedLandscape = false;
                return;
            }
            var dm = com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration.getDisplayMode();
            int winW = Math.min(1600, (int) (dm.width * 0.88f));
            int winH = (int) (winW * Theme.VH_L / Theme.VW_L);
            Gdx.graphics.setWindowedMode(winW, winH);
            forcedLandscape = true;
        }

        /**
         * Restores a portrait window after leaving the control center if this session
         * forced landscape. Uses the persisted window height when available.
         */
        public static void restorePortraitAfterRun(GameSettings settings) {
            if (!forcedLandscape || Gdx.graphics == null) return;
            forcedLandscape = false;
            if (settings != null && settings.fullscreen) return;
            int h = settings != null ? settings.windowHeight() : 1080;
            int w = (int) Math.round(h * Theme.VW / Theme.VH);
            Gdx.graphics.setWindowedMode(w, h);
        }
    }
}
