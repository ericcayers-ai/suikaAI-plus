package dev.suika.game;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.viewport.FitViewport;
import dev.suika.core.GameState;
import dev.suika.core.PhysicsConfig;

/**
 * The specialised AI control center: a live board driven by a {@link TechniqueRunner}
 * plus a diagnostics panel with live charts, runtime telemetry, board-aligned
 * "thinking" bars, and runtime controls (pause, speed, restart). Imitation techniques
 * show a "Train the AI" card first and accept human drops.
 *
 * <p>Supports two layouts:
 * <ul>
 *   <li><b>Portrait</b> (default): 720×1280 virtual canvas, panel above the board.</li>
 *   <li><b>Landscape</b>: 1280×720 virtual canvas, panel on the left, board on the right.</li>
 * </ul>
 */
public final class ControlCenterScreen extends ScreenAdapter {

    private final SuikaGame game;
    private final PlaygroundConfig cfg;
    private final TechniqueRunner runner;

    private OrthographicCamera camera = new OrthographicCamera();
    private FitViewport viewport;
    private boolean landscape = false;

    private final BoardRenderer board = new BoardRenderer();
    private final Vector3 touch = new Vector3();
    private float mx, my;
    private float hoverGameX = (float) ((PhysicsConfig.DROP_X_MIN + PhysicsConfig.DROP_X_MAX) / 2.0);

    // control bar buttons (portrait) — repositioned in landscape via layoutButtons()
    private final Rectangle backBtn    = new Rectangle(24,  16, 120, 46);
    private final Rectangle pauseBtn   = new Rectangle(156, 16, 120, 46);
    private final Rectangle slowBtn    = new Rectangle(288, 16, 56,  46);
    private final Rectangle fastBtn    = new Rectangle(352, 16, 56,  46);
    private final Rectangle restartBtn = new Rectangle(576, 16, 120, 46);

    public ControlCenterScreen(SuikaGame game, PlaygroundConfig cfg) {
        this.game = game;
        this.cfg  = cfg;
        this.runner = switch (cfg.technique.family) {
            case PLANNING  -> new PlanningRunner(game, cfg);
            case EVOLUTION -> new EvolutionRunner(game, cfg);
            case IMITATION -> new ImitationRunner(game, cfg);
            case PYTHON    -> new PythonRunner(game, cfg);
        };
        applyOrientation(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
    }

    private void applyOrientation(int w, int h) {
        landscape = w > h * 1.3f;
        float vw = landscape ? Theme.VW_L : Theme.VW;
        float vh = landscape ? Theme.VH_L : Theme.VH;
        viewport = new FitViewport(vw, vh, camera);
        camera.position.set(vw / 2f, vh / 2f, 0f);
        camera.update();
        if (landscape) {
            board.useLandscape();
            layoutButtonsLandscape();
        } else {
            board.usePortrait();
            layoutButtonsPortrait();
        }
    }

    private void layoutButtonsPortrait() {
        backBtn.set(24, 16, 120, 46); pauseBtn.set(156, 16, 120, 46);
        slowBtn.set(288, 16, 56, 46); fastBtn.set(352, 16, 56, 46);
        restartBtn.set(576, 16, 120, 46);
    }

    private void layoutButtonsLandscape() {
        // Control bar across the bottom of the 1280×720 canvas
        backBtn.set(24, 16, 110, 42); pauseBtn.set(144, 16, 110, 42);
        slowBtn.set(264, 16, 52, 42); fastBtn.set(324, 16, 52, 42);
        restartBtn.set(1130, 16, 120, 42);
    }

    @Override
    public void show() {
        runner.start();
        Gdx.input.setInputProcessor(new InputAdapter() {
            @Override public boolean touchDown(int sx, int sy, int p, int b) {
                camera.unproject(touch.set(sx, sy, 0), viewport.getScreenX(), viewport.getScreenY(), viewport.getScreenWidth(), viewport.getScreenHeight());
                handleClick(touch.x, touch.y);
                return true;
            }
            @Override public boolean mouseMoved(int sx, int sy) { updateMouse(sx, sy); return false; }
            @Override public boolean touchDragged(int sx, int sy, int p) { updateMouse(sx, sy); return false; }
            @Override public boolean keyDown(int k) {
                switch (k) {
                    case Input.Keys.ESCAPE -> game.setScreen(new AiPlaygroundScreen(game, cfg));
                    case Input.Keys.SPACE  -> runner.setPaused(!runner.paused());
                    case Input.Keys.R      -> runner.restart();
                    case Input.Keys.EQUALS, Input.Keys.PLUS  -> changeSpeed(+1);
                    case Input.Keys.MINUS                     -> changeSpeed(-1);
                    default -> { return false; }
                }
                return true;
            }
        });
    }

    private void updateMouse(int sx, int sy) {
        camera.unproject(touch.set(sx, sy, 0), viewport.getScreenX(), viewport.getScreenY(), viewport.getScreenWidth(), viewport.getScreenHeight());
        mx = touch.x; my = touch.y;
        float ox = board.isLandscape() ? BoardRenderer.OX_L : BoardRenderer.OX;
        float sc = board.isLandscape() ? BoardRenderer.SCALE_L : BoardRenderer.SCALE;
        hoverGameX = MathUtils.clamp((mx - ox) / sc,
                (float) PhysicsConfig.DROP_X_MIN, (float) PhysicsConfig.DROP_X_MAX);
    }

    private void handleClick(float x, float y) {
        if (backBtn.contains(x, y))    { game.setScreen(new AiPlaygroundScreen(game, cfg)); return; }
        if (pauseBtn.contains(x, y))   { runner.setPaused(!runner.paused()); return; }
        if (slowBtn.contains(x, y))    { changeSpeed(-1); return; }
        if (fastBtn.contains(x, y))    { changeSpeed(+1); return; }
        if (restartBtn.contains(x, y)) { runner.restart(); return; }
        if (runner.acceptsHumanInput()) runner.humanDrop(hoverGameX);
    }

    private void changeSpeed(int d) {
        cfg.speedIndex = MathUtils.clamp(cfg.speedIndex + d, 0, PlaygroundConfig.SPEEDS.length - 1);
        runner.setSpeed(cfg.speed());
    }

    @Override
    public void render(float delta) {
        delta = Math.min(delta, 0.05f);
        if (runner.acceptsHumanInput()) runner.setHover(hoverGameX);
        runner.update(delta);
        game.scorePops.update(delta);

        Gdx.gl.glClearColor(0.05f, 0.06f, 0.10f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        viewport.apply();
        game.shapes.setProjectionMatrix(camera.combined);
        game.batch.setProjectionMatrix(camera.combined);

        GameState gs = runner.board();
        boolean human = runner.acceptsHumanInput();
        board.setHover(human ? hoverGameX : Float.NaN, gs.currentFruitTier());

        ShapeRenderer s = game.shapes;
        // 1) board world + overlays + control bar
        s.begin(ShapeRenderer.ShapeType.Filled);
        board.drawBackground(s);
        board.drawBoard(s, gs, game.settings, game.particles);
        // ghost boards (evolution with ghostView)
        if (cfg.ghostView && runner instanceof EvolutionRunner er) {
            GameState[] ghosts = er.ghostStates();
            if (ghosts != null) {
                for (GameState ghost : ghosts) {
                    if (ghost != null) board.drawBoard(s, ghost, game.settings, null, 0.12f);
                }
            }
        }
        drawColumnOverlay(s, gs);
        drawControlBar(s);
        s.end();
        // 2) board tier labels
        game.batch.begin();
        board.drawLabels(game.batch, game.fontSmall, gs, game.settings);
        game.batch.end();
        // 3) diagnostics panel (opaque — masks fruit spawned at the chute behind it)
        s.begin(ShapeRenderer.ShapeType.Filled);
        drawPanel(s);
        s.end();
        // 4) panel + control text + score pops
        game.batch.begin();
        drawPanelText(gs);
        drawControlBarText();
        game.scorePops.draw(game.batch, game.fontMed);
        game.batch.end();

        if (runner.modalActive()) drawModal();
    }

    /** MCTS visit bars / chosen-column marker, hung from the rim into the empty well-top. */
    private void drawColumnOverlay(ShapeRenderer s, GameState gs) {
        float baseY = board.bvpy(PhysicsConfig.CONTAINER_HEIGHT) - 6f;
        int[] bars = runner.columnBars();
        if (bars != null && bars.length > 0) {
            int max = 1;
            for (int v : bars) max = Math.max(max, v);
            float barW = (board.bvpx(PhysicsConfig.CONTAINER_WIDTH) - board.bvpx(0))
                    / bars.length * 0.7f;
            for (int i = 0; i < bars.length; i++) {
                float x = board.bColumnX(i, bars.length);
                float h = 4f + 44f * (bars[i] / (float) max);
                s.setColor(Theme.ACCENT_BLUE.r, Theme.ACCENT_BLUE.g, Theme.ACCENT_BLUE.b, 0.72f);
                Ui.fillRoundRect(s, x - barW / 2f, baseY - h, barW, h, 2f);
            }
        }
        float marker = runner.markerX();
        if (!Float.isNaN(marker)) {
            s.setColor(Theme.GOLD);
            s.rect(board.bvpx(marker) - 2f, baseY - 56f, 4f, 56f);
        }
    }

    // ---- diagnostics panel ----

    /** Returns panel bounds depending on orientation. [x,y,w,h] */
    private float[] panelBounds() {
        if (landscape) {
            // Left panel spanning full height (minus control bar) in landscape
            return new float[]{12f, 72f, 650f, Theme.VH_L - 90f};
        } else {
            return new float[]{26f, 980f, Theme.VW - 52f, 286f};
        }
    }

    private void drawPanel(ShapeRenderer s) {
        float[] p = panelBounds();
        float px = p[0], py = p[1], pw = p[2], ph = p[3];
        s.setColor(0.07f, 0.08f, 0.12f, 1f);
        Ui.fillRoundRect(s, px - 4, py - 4, pw + 8, ph + 8, 18);
        Ui.panel(s, px, py, pw, ph, 16, Theme.PANEL, Theme.PANEL_EDGE);
        // chart frames
        float cw = landscape ? 200f : 286f;
        float cx = px + pw - cw - 16;
        float chart1Y = landscape ? py + ph - 140f : py + 150f;
        float chart2Y = landscape ? py + ph - 260f : py + 26f;
        chartFrame(s, cx, chart1Y, cw, 96, runner.chart1(), Theme.ACCENT_2);
        if (runner.chart2() != null) chartFrame(s, cx, chart2Y, cw, 96, runner.chart2(), Theme.ACCENT_BLUE);
    }

    private void chartFrame(ShapeRenderer s, float x, float y, float w, float h,
                            LiveChart c, com.badlogic.gdx.graphics.Color col) {
        s.setColor(Theme.PANEL_DEEP);
        Ui.fillRoundRect(s, x, y, w, h, 8f);
        if (c != null) c.render(s, x + 8, y + 8, w - 16, h - 16, col);
    }

    private void drawPanelText(GameState gs) {
        float[] p = panelBounds();
        float px = p[0], py = p[1], pw = p[2], ph = p[3];
        float cw = landscape ? 200f : 286f;
        float cx = px + pw - cw - 16;
        float chart1Y = landscape ? py + ph - 140f : py + 150f;
        float chart2Y = landscape ? py + ph - 260f : py + 26f;

        Ui.text(game.batch, game.fontMed, runner.title(), px + 18, py + ph - 22, Theme.TEXT);
        Ui.text(game.batch, game.fontSmall, runner.subtitle(), px + 18, py + ph - 56, Theme.ACCENT_BLUE);

        String[] stats = runner.stats();
        float ly = py + ph - 86;
        for (String line : stats) {
            Ui.text(game.batch, game.fontSmall, line, px + 18, ly, Theme.TEXT_DIM);
            ly -= 24;
        }

        Ui.text(game.batch, game.fontSmall, runner.chart1Label(), cx + 4, chart1Y + 96 + 18, Theme.TEXT_DIM);
        if (runner.chart2() != null && runner.chart2Label() != null)
            Ui.text(game.batch, game.fontSmall, runner.chart2Label(), cx + 4, chart2Y + 96 + 18, Theme.TEXT_DIM);
    }

    // ---- control bar ----
    private void drawControlBar(ShapeRenderer s) {
        Ui.button(s, backBtn,    Theme.PANEL_EDGE, backBtn.contains(mx, my),    true);
        Ui.button(s, pauseBtn,   Theme.ACCENT_BLUE, pauseBtn.contains(mx, my),  true);
        Ui.button(s, slowBtn,    Theme.PANEL_EDGE, slowBtn.contains(mx, my),    true);
        Ui.button(s, fastBtn,    Theme.PANEL_EDGE, fastBtn.contains(mx, my),    true);
        Ui.button(s, restartBtn, Theme.ACCENT,     restartBtn.contains(mx, my), true);
    }

    private void drawControlBarText() {
        Ui.textCenter(game.batch, game.fontSmall, "BACK",  backBtn.x + backBtn.width/2f,    backBtn.y + 28, Theme.TEXT);
        Ui.textCenter(game.batch, game.fontSmall, runner.paused() ? "RESUME" : "PAUSE",
                pauseBtn.x + pauseBtn.width/2f, pauseBtn.y + 28, Theme.TEXT);
        Ui.textCenter(game.batch, game.fontMed, "-", slowBtn.x + 28, slowBtn.y + 26, Theme.TEXT);
        Ui.textCenter(game.batch, game.fontMed, "+", fastBtn.x + 28, fastBtn.y + 26, Theme.TEXT);
        Ui.textCenter(game.batch, game.fontSmall, cfg.speedLabel(), (slowBtn.x + fastBtn.x) / 2f + 28, slowBtn.y + 60, Theme.TEXT_DIM);
        Ui.textCenter(game.batch, game.fontSmall, "RESTART", restartBtn.x + restartBtn.width/2f, restartBtn.y + 28, Theme.TEXT);
        if (runner.acceptsHumanInput())
            Ui.textCenter(game.batch, game.fontSmall, "click the well to drop", landscape ? 900f : Theme.VW / 2f, 84, Theme.TEXT_FAINT);
    }

    private void drawModal() {
        float vw = landscape ? Theme.VW_L : Theme.VW;
        float vh = landscape ? Theme.VH_L : Theme.VH;
        ShapeRenderer s = game.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);
        s.setColor(0f, 0f, 0f, 0.66f);
        s.rect(0, 0, vw, vh);
        Ui.panel(s, vw/2f - 290, vh/2f - 70, 580, 360, 18, Theme.PANEL, Theme.GOLD);
        s.end();
        game.batch.begin();
        Ui.textCenter(game.batch, game.fontMed, runner.modalTitle(), vw/2f, vh/2f + 240, Theme.GOLD);
        float y = vh/2f + 160;
        for (String line : runner.modalBody()) {
            Ui.textCenter(game.batch, game.font, line, vw/2f, y, Theme.TEXT);
            y -= 34;
        }
        game.batch.end();
    }

    @Override
    public void resize(int width, int height) {
        boolean wasLandscape = landscape;
        applyOrientation(width, height);
        viewport.update(width, height);
        if (wasLandscape != landscape) {
            // orientation flipped — update camera center
            float vw = landscape ? Theme.VW_L : Theme.VW;
            float vh = landscape ? Theme.VH_L : Theme.VH;
            camera.position.set(vw/2f, vh/2f, 0f);
            camera.update();
        }
    }

    @Override public void hide() { Gdx.input.setInputProcessor(null); }
    @Override public void dispose() { runner.dispose(); }
}
