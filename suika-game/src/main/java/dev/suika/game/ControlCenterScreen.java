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
 */
public final class ControlCenterScreen extends ScreenAdapter {

    private final SuikaGame game;
    private final PlaygroundConfig cfg;
    private final TechniqueRunner runner;

    private final OrthographicCamera camera = new OrthographicCamera();
    private final FitViewport viewport;
    private final BoardRenderer board = new BoardRenderer();
    private final Vector3 touch = new Vector3();
    private float mx, my;
    private float hoverGameX = (float) ((PhysicsConfig.DROP_X_MIN + PhysicsConfig.DROP_X_MAX) / 2.0);

    // control bar buttons
    private final Rectangle backBtn    = new Rectangle(24,  16, 120, 46);
    private final Rectangle pauseBtn   = new Rectangle(156, 16, 120, 46);
    private final Rectangle slowBtn    = new Rectangle(288, 16, 56,  46);
    private final Rectangle fastBtn    = new Rectangle(352, 16, 56,  46);
    private final Rectangle restartBtn = new Rectangle(576, 16, 120, 46);

    public ControlCenterScreen(SuikaGame game, PlaygroundConfig cfg) {
        this.game = game;
        this.cfg  = cfg;
        viewport = new FitViewport(Theme.VW, Theme.VH, camera);
        camera.position.set(Theme.VW / 2f, Theme.VH / 2f, 0f);
        camera.update();
        this.runner = switch (cfg.technique.family) {
            case PLANNING  -> new PlanningRunner(game, cfg);
            case EVOLUTION -> new EvolutionRunner(game, cfg);
            case IMITATION -> new ImitationRunner(game, cfg);
            case PYTHON    -> new PythonRunner(game, cfg);
        };
    }

    @Override
    public void show() {
        runner.start();
        Gdx.input.setInputProcessor(new InputAdapter() {
            @Override public boolean touchDown(int sx, int sy, int p, int b) {
                camera.unproject(touch.set(sx, sy, 0));
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
        camera.unproject(touch.set(sx, sy, 0));
        mx = touch.x; my = touch.y;
        hoverGameX = MathUtils.clamp((mx - BoardRenderer.OX) / BoardRenderer.SCALE,
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
        float baseY = BoardRenderer.vpy(PhysicsConfig.CONTAINER_HEIGHT) - 6f; // just inside the rim
        int[] bars = runner.columnBars();
        if (bars != null && bars.length > 0) {
            int max = 1;
            for (int v : bars) max = Math.max(max, v);
            float barW = (BoardRenderer.vpx(PhysicsConfig.CONTAINER_WIDTH) - BoardRenderer.vpx(0))
                    / bars.length * 0.7f;
            for (int i = 0; i < bars.length; i++) {
                float x = BoardRenderer.columnX(i, bars.length);
                float h = 4f + 44f * (bars[i] / (float) max);
                s.setColor(Theme.ACCENT_BLUE.r, Theme.ACCENT_BLUE.g, Theme.ACCENT_BLUE.b, 0.72f);
                Ui.fillRoundRect(s, x - barW / 2f, baseY - h, barW, h, 2f);
            }
        }
        float marker = runner.markerX();
        if (!Float.isNaN(marker)) {
            s.setColor(Theme.GOLD);
            s.rect(BoardRenderer.vpx(marker) - 2f, baseY - 56f, 4f, 56f);
        }
    }

    // ---- diagnostics panel (top band) ----
    private static final float PX = 26, PY = 980, PW = Theme.VW - 52, PH = 286;

    private void drawPanel(ShapeRenderer s) {
        // opaque backing so fruit/labels spawned in the chute behind the panel never bleed through
        s.setColor(0.07f, 0.08f, 0.12f, 1f);
        Ui.fillRoundRect(s, PX - 4, PY - 4, PW + 8, PH + 8, 18);
        Ui.panel(s, PX, PY, PW, PH, 16, Theme.PANEL, Theme.PANEL_EDGE);
        // chart frames (right column)
        float cw = 286, cx = PX + PW - cw - 16;
        chartFrame(s, cx, PY + 150, cw, 96, runner.chart1(), Theme.ACCENT_2);
        if (runner.chart2() != null) chartFrame(s, cx, PY + 26, cw, 96, runner.chart2(), Theme.ACCENT_BLUE);
    }

    private void chartFrame(ShapeRenderer s, float x, float y, float w, float h, LiveChart c, com.badlogic.gdx.graphics.Color col) {
        s.setColor(Theme.PANEL_DEEP);
        Ui.fillRoundRect(s, x, y, w, h, 8f);
        if (c != null) c.render(s, x + 8, y + 8, w - 16, h - 16, col);
    }

    private void drawPanelText(GameState gs) {
        Ui.text(game.batch, game.fontMed, runner.title(), PX + 18, PY + PH - 22, Theme.TEXT);
        Ui.text(game.batch, game.fontSmall, runner.subtitle(), PX + 18, PY + PH - 56, Theme.ACCENT_BLUE);

        String[] stats = runner.stats();
        float ly = PY + PH - 86;
        for (String line : stats) {
            Ui.text(game.batch, game.fontSmall, line, PX + 18, ly, Theme.TEXT_DIM);
            ly -= 24;
        }

        float cw = 286, cx = PX + PW - cw - 16;
        Ui.text(game.batch, game.fontSmall, runner.chart1Label(), cx + 4, PY + 150 + 96 + 18, Theme.TEXT_DIM);
        if (runner.chart2() != null && runner.chart2Label() != null)
            Ui.text(game.batch, game.fontSmall, runner.chart2Label(), cx + 4, PY + 26 + 96 + 18, Theme.TEXT_DIM);
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
        Ui.textCenter(game.batch, game.fontSmall, "BACK",  backBtn.x + 60,  backBtn.y + 28,  Theme.TEXT);
        Ui.textCenter(game.batch, game.fontSmall, runner.paused() ? "RESUME" : "PAUSE",
                pauseBtn.x + 60, pauseBtn.y + 28, Theme.TEXT);
        Ui.textCenter(game.batch, game.fontMed, "-", slowBtn.x + 28, slowBtn.y + 26, Theme.TEXT);
        Ui.textCenter(game.batch, game.fontMed, "+", fastBtn.x + 28, fastBtn.y + 26, Theme.TEXT);
        Ui.textCenter(game.batch, game.fontSmall, cfg.speedLabel(), (slowBtn.x + fastBtn.x) / 2f + 28, slowBtn.y + 60, Theme.TEXT_DIM);
        Ui.textCenter(game.batch, game.fontSmall, "RESTART", restartBtn.x + 60, restartBtn.y + 28, Theme.TEXT);
        if (runner.acceptsHumanInput())
            Ui.textCenter(game.batch, game.fontSmall, "click the well to drop", Theme.VW / 2f, 84, Theme.TEXT_FAINT);
    }

    private void drawModal() {
        ShapeRenderer s = game.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);
        s.setColor(0f, 0f, 0f, 0.66f);
        s.rect(0, 0, Theme.VW, Theme.VH);
        Ui.panel(s, Theme.VW/2f - 290, 470, 580, 360, 18, Theme.PANEL, Theme.GOLD);
        s.end();
        game.batch.begin();
        Ui.textCenter(game.batch, game.fontMed, runner.modalTitle(), Theme.VW/2f, 776, Theme.GOLD);
        float y = 690;
        for (String line : runner.modalBody()) {
            Ui.textCenter(game.batch, game.font, line, Theme.VW/2f, y, Theme.TEXT);
            y -= 34;
        }
        game.batch.end();
    }

    @Override public void resize(int width, int height) { viewport.update(width, height); }
    @Override public void hide() { Gdx.input.setInputProcessor(null); }
    @Override public void dispose() { runner.dispose(); }
}
