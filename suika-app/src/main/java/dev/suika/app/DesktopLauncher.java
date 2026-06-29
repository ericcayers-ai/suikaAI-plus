package dev.suika.app;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import dev.suika.game.CaptureHarness;
import dev.suika.game.SuikaGame;

/**
 * Launches the LibGDX windowed application via the LWJGL3 backend.
 *
 * <p>Called by {@link SuikaApplication#main(String[])} when not running in headless mode.
 * The window renders a {@code FitViewport} virtual canvas (720×1280 px) and is resizable.
 *
 * <p>If the {@code suika.capture.dir} system property is set, a {@link CaptureHarness}
 * runs instead — it scripts the screens and writes screenshots for QA, then exits.
 */
public final class DesktopLauncher {

    private DesktopLauncher() {}

    public static void launch() {
        String captureDir = System.getProperty("suika.capture.dir");

        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("Suika AI Sandbox — v0.5.0");
        config.setResizable(true);
        config.setForegroundFPS(60);
        config.useVsync(true);
        // 4× MSAA for smooth circle / panel edges (depth/stencil unused).
        config.setBackBufferConfig(8, 8, 8, 8, 16, 0, 4);
        config.setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL20, 2, 0);

        if (captureDir != null) {
            // Match the virtual canvas aspect exactly so captures have no letterbox bars.
            config.setWindowedMode(720, 1280);
            new Lwjgl3Application(new CaptureHarness(captureDir), config);
        } else {
            config.setWindowedMode(720, 1080);
            new Lwjgl3Application(new SuikaGame(), config);
        }
    }
}
