package dev.suika.app;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import dev.suika.game.SuikaGame;

/**
 * Launches the LibGDX windowed application via the LWJGL3 backend.
 *
 * <p>Called by {@link SuikaApplication#main(String[])} when not running in headless mode.
 * The window uses a {@code FitViewport} virtual resolution of 720×1060 px and is resizable.
 */
public final class DesktopLauncher {

    private DesktopLauncher() {}

    public static void launch() {
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("Suika AI Sandbox — v0.2.0-SNAPSHOT");
        config.setWindowedMode(720, 1060);
        config.setResizable(true);
        config.setForegroundFPS(60);
        config.useVsync(true);
        config.setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL20, 2, 0);
        new Lwjgl3Application(new SuikaGame(), config);
    }
}
