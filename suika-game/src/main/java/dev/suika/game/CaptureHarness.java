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
    private AiPlaygroundScreen playground;
    private ControlCenterScreen cc;

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
            case 1 -> { if (t > 1.3f)  { shoot("02-settings.png"); openPlayground(); stage++; } }
            case 2 -> { if (t > 1.9f)  { shoot("03-playground.png"); playground.openInfocardForCapture(AiTechnique.PPO); stage++; } }
            case 3 -> { if (t > 2.3f)  { shoot("04-infocard-modal.png"); launchControlCenter(AiTechnique.MCTS, false); stage++; } }
            case 4 -> { if (t > 6.0f)  { shoot("05-mcts-cc.png"); cc.openHotswapForCapture(); stage++; } }
            case 5 -> { if (t > 6.5f)  { shoot("06-hotswap-modal.png"); launchControlCenter(AiTechnique.NEUROEVO, false); stage++; } }
            case 6 -> { if (t > 16.0f) { shoot("07-neuroevo-4grid.png"); launchControlCenter(AiTechnique.SELF_PLAY, false); stage++; } }
            case 7 -> { if (t > 22.0f) { shoot("08-selfplay-2view.png"); launchControlCenter(AiTechnique.NEUROEVO, true); stage++; } }
            case 8 -> { if (t > 30.0f) { shoot("09-neuroevo-ghost.png"); launchControlCenter(AiTechnique.BC, false); stage++; } }
            case 9 -> { if (t > 31.0f) { cc.forceHumanDropForCapture(); stage++; } }   // dismiss modal -> show YOU|AI dual board
            case 10-> { if (t > 33.5f) { shoot("10-bc-dual.png"); game.setScreen(new SuikaScreen(game, SuikaScreen.Mode.HUMAN)); stage++; } }
            case 11-> { if (t > 35.0f) { shoot("11-human-play.png"); openGameOver(); stage++; } }
            case 12-> { if (t > 36.0f) { shoot("12-game-over.png"); stage++; Gdx.app.exit(); } }
            default -> { }
        }
    }

    /** Build a short game and hand its final state to the game-over summary. */
    private void openGameOver() {
        dev.suika.core.GameCore core = new dev.suika.core.GameCore(7L);
        double[] xs = {2.0, 2.0, 5.0, 5.0, 8.0, 3.5, 6.5};
        for (double x : xs) core.dropAndSettle(x);
        game.setScreen(new GameOverScreen(game, core.getScore(), core.getState(),
                SuikaScreen.Mode.HUMAN, 7L));
    }

    private void openPlayground() {
        playground = new AiPlaygroundScreen(game);
        game.setScreen(playground);
    }

    private void launchControlCenter(AiTechnique tech, boolean ghost) {
        PlaygroundConfig c = new PlaygroundConfig();
        c.selectDefaultsFor(tech);
        c.ghostView = ghost;
        c.actionBins = game.settings.actionBins();
        cc = new ControlCenterScreen(game, c);
        game.setScreen(cc);
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
