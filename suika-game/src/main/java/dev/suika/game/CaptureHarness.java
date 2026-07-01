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
 *
 * <p>After a handful of curated screens (menu, settings, playground, infocard,
 * hotswap), it sweeps every {@link AiTechnique} in turn — launching its control
 * center, giving it enough time to reach a meaningful live state (a few generations
 * for evolution, an auto-played first game for imitation so the trained dual-board
 * view actually has data), and capturing one screenshot per technique.
 */
public final class CaptureHarness implements ApplicationListener {

    private final String outDir;
    private SuikaGame game;

    /** Time spent in the CURRENT stage (reset by {@link #nextStage()}), not global. */
    private float stageT = 0f;
    private int stage = 0;

    private AiPlaygroundScreen playground;
    private ControlCenterScreen cc;

    private static final AiTechnique[] TECHS = AiTechnique.values();
    private int techIndex = 0;

    // Imitation auto-play: keep dropping (at chute-clear checkpoints, like a fast real
    // player) until game 1 ends and training kicks in, so the captured dual board shows
    // real numbers instead of an all-zero "still watching" state.
    private static final double[] AUTO_DROP_XS = {2.0, 5.0, 8.0, 3.0, 7.0, 4.5, 6.0, 1.5, 8.5, 3.5, 2.5, 6.5};
    private int   autoDropCount;
    private float autoDropTimer;

    public CaptureHarness(String outDir) { this.outDir = outDir; }

    @Override public void create() {
        game = new SuikaGame();
        game.create();
    }

    @Override public void resize(int width, int height) { game.resize(width, height); }

    private void nextStage() { stage++; stageT = 0f; }

    @Override
    public void render() {
        float dt = Gdx.graphics.getDeltaTime();
        stageT += dt;
        game.render();

        switch (stage) {
            case 0 -> { if (stageT > 0.6f) { shoot("00-menu.png"); game.setScreen(new SettingsScreen(game, MainMenuScreen::new)); nextStage(); } }
            case 1 -> { if (stageT > 0.7f) { shoot("01-settings.png"); openPlayground(); nextStage(); } }
            case 2 -> { if (stageT > 0.6f) { shoot("02-playground.png"); playground.openInfocardForCapture(AiTechnique.PPO); nextStage(); } }
            case 3 -> { if (stageT > 0.4f) { shoot("03-infocard-modal.png"); playground.openInfocardForCapture(null); launchControlCenter(AiTechnique.MCTS, false); nextStage(); } }
            case 4 -> { if (stageT > 3.5f) { shoot("04-mcts-cc.png"); cc.openHotswapForCapture(); nextStage(); } }
            case 5 -> { if (stageT > 0.5f) { shoot("05-hotswap-modal.png"); launchControlCenter(AiTechnique.NEUROEVO, false); nextStage(); } }
            case 6 -> { if (stageT > 3.0f) { cc.openSlotsForCapture(); nextStage(); } }
            case 7 -> { if (stageT > 0.5f) { shoot("06-slots-modal.png"); startSixteenXGrid(); nextStage(); } }
            case 8 -> { if (stageT > 9.0f) { shoot("07-neuroevo-16x-grid.png"); techIndex = 0; startTechnique(TECHS[techIndex]); nextStage(); } }

            // ---- full technique sweep: one screenshot per AiTechnique ----
            case 9 -> {
                AiTechnique tech = TECHS[techIndex];
                if (tech.family == AiTechnique.Family.IMITATION) {
                    driveImitationAutoPlay(dt);
                    // A few live drops exercise the real human-drop -> dataset path; finishing
                    // an entire game via gravity-paced physics would take far too long for a
                    // sweep, so once a handful have landed, jump straight to TRAIN so the
                    // trained dual-board (loss/accuracy/AI-clone-playing) actually has data.
                    if (stageT > 2.0f) cc.forceImitationTrainPhaseForCapture();
                }
                if (stageT > dwellFor(tech)) {
                    shoot(String.format("tech-%02d-%s.png", techIndex + 1, tech.id));
                    techIndex++;
                    if (techIndex < TECHS.length) { startTechnique(TECHS[techIndex]); stageT = 0f; }
                    else nextStage();
                }
            }

            // ---- extra curated views the default sweep doesn't cover ----
            case 10-> { if (stageT > 0.2f) { launchControlCenter(AiTechnique.SELF_PLAY, false); nextStage(); } }
            case 11-> { if (stageT > 5.0f) { shoot("90-selfplay-2view.png"); launchControlCenter(AiTechnique.NEUROEVO, true); nextStage(); } }
            case 12-> { if (stageT > 9.0f) { shoot("91-neuroevo-ghost.png"); nextStage(); } }

            case 13-> { if (stageT > 0.2f) { game.setScreen(new SuikaScreen(game, SuikaScreen.Mode.HUMAN)); nextStage(); } }
            case 14-> { if (stageT > 1.5f) { shoot("92-human-play.png"); openGameOver(); nextStage(); } }
            case 15-> { if (stageT > 1.0f) { shoot("93-game-over.png"); nextStage(); Gdx.app.exit(); } }
            default -> { }
        }
    }

    /** Launches Neuroevolution with the max (16x) elite view count, to exercise the
     *  auto-grid layout at scale. */
    private void startSixteenXGrid() {
        PlaygroundConfig c = new PlaygroundConfig();
        c.selectDefaultsFor(AiTechnique.NEUROEVO);
        c.eliteViewIndex = PlaygroundConfig.ELITE_VIEW_OPTIONS.length - 1; // 16x
        c.actionBins = game.settings.actionBins();
        cc = new ControlCenterScreen(game, c);
        game.setScreen(cc);
    }

    /** How long to let a technique run before its screenshot — enough to reach a live state. */
    private float dwellFor(AiTechnique t) {
        return switch (t.family) {
            case EVOLUTION -> 8.0f;                 // reach a few generations
            case IMITATION -> 8.0f;                 // a few live drops, then a beat of training
            default -> 4.0f;
        };
    }

    private void startTechnique(AiTechnique tech) {
        launchControlCenter(tech, false);
        autoDropCount = 0;
        autoDropTimer = 0.3f;
    }

    /** Drops at chute-clear checkpoints (like a fast real player) until game 1 ends. */
    private void driveImitationAutoPlay(float dt) {
        if (cc == null || cc.isGameOverForCapture() || autoDropCount >= AUTO_DROP_XS.length) return;
        autoDropTimer -= dt;
        if (autoDropTimer <= 0f && cc.chuteClearForCapture()) {
            cc.forceHumanDropForCapture(AUTO_DROP_XS[autoDropCount % AUTO_DROP_XS.length]);
            autoDropCount++;
            autoDropTimer = 0.45f;
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
