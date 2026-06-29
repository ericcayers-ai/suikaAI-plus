package dev.suika.game;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.utils.ScreenUtils;

import java.nio.ByteBuffer;

/**
 * Headless-ish QA harness: drives the real screens through a scripted timeline and
 * writes PNG screenshots of the live framebuffer to a directory. Used to verify
 * rendering without manual interaction. Activated by {@code DesktopLauncher} when the
 * {@code suika.capture.dir} system property is set; never part of normal play.
 */
public final class CaptureHarness implements ApplicationListener {

    private final String outDir;
    private SuikaGame game;
    private float t = 0f;
    private int stage = 0;

    public CaptureHarness(String outDir) { this.outDir = outDir; }

    @Override public void create() {
        game = new SuikaGame();
        game.create();
    }

    @Override public void resize(int width, int height) { game.resize(width, height); }

    @Override
    public void render() {
        t += Gdx.graphics.getDeltaTime();
        game.render();

        switch (stage) {
            case 0 -> { if (t > 0.6f) { shoot("01-menu.png"); game.setScreen(new SettingsScreen(game, MainMenuScreen::new)); stage++; } }
            case 1 -> { if (t > 1.3f) { shoot("02-settings.png"); game.setScreen(new SuikaScreen(game, SuikaScreen.Mode.HUMAN)); stage++; } }
            case 2 -> { if (t > 2.0f) { shoot("03-human-empty.png"); game.setScreen(new SuikaScreen(game, SuikaScreen.Mode.AI_WATCH)); stage++; } }
            case 3 -> { if (t > 5.0f) { shoot("04-ai-early.png"); stage++; } }
            case 4 -> { if (t > 10.0f) { shoot("05-ai-mid.png"); stage++; } }
            case 5 -> { if (t > 16.0f) { shoot("06-ai-late.png"); stage++; Gdx.app.exit(); } }
            default -> { }
        }
    }

    private void shoot(String name) {
        int w = Gdx.graphics.getBackBufferWidth();
        int h = Gdx.graphics.getBackBufferHeight();
        byte[] pixels = ScreenUtils.getFrameBufferPixels(0, 0, w, h, true);
        Pixmap pm = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        ByteBuffer buf = pm.getPixels();
        buf.clear();
        buf.put(pixels);
        buf.position(0);
        PixmapIO.writePNG(Gdx.files.absolute(outDir + "/" + name), pm);
        pm.dispose();
        Gdx.app.log("capture", "wrote " + outDir + "/" + name);
    }

    @Override public void pause() {}
    @Override public void resume() {}
    @Override public void dispose() { if (game != null) game.dispose(); }
}
