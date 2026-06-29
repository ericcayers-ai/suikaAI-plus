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
            case 0 -> { if (t > 0.6f)  { shoot("01-menu.png"); game.setScreen(new SettingsScreen(game, MainMenuScreen::new)); stage++; } }
            case 1 -> { if (t > 1.3f)  { shoot("02-settings.png"); game.setScreen(new AiPlaygroundScreen(game)); stage++; } }
            case 2 -> { if (t > 2.0f)  { shoot("03-playground.png"); game.setScreen(new SuikaScreen(game, SuikaScreen.Mode.HUMAN)); stage++; } }
            case 3 -> { if (t > 2.6f)  { shoot("04-human.png"); launchControlCenter(AiTechnique.MCTS); stage++; } }
            case 4 -> { if (t > 7.0f)  { shoot("05-mcts-cc.png"); launchControlCenter(AiTechnique.NEUROEVO); stage++; } }
            case 5 -> { if (t > 14.0f) { shoot("06-neuroevo-cc.png"); launchControlCenter(AiTechnique.PPO); stage++; } }
            case 6 -> { if (t > 18.0f) { shoot("07-ppo-cc.png"); launchControlCenter(AiTechnique.BC); stage++; } }
            case 7 -> { if (t > 19.0f) { shoot("08-bc-modal.png"); stage++; Gdx.app.exit(); } }
            default -> { }
        }
    }

    private void launchControlCenter(AiTechnique tech) {
        PlaygroundConfig c = new PlaygroundConfig();
        c.selectDefaultsFor(tech);
        c.actionBins = game.settings.actionBins();
        game.setScreen(new ControlCenterScreen(game, c));
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
