package dev.suika.game;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.Color;
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
 * AI control center: live board, diagnostics panel, runtime controls.
 * Supports portrait (720×1280) and landscape (1280×720) layouts.
 *
 * <p>Evolution techniques with ghostView OFF show a 2×2 grid of the top
 * 4 performing games instead of a single board.
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

    // Control bar buttons (portrait) — repositioned for landscape via layoutButtons()
    private final Rectangle backBtn    = new Rectangle();
    private final Rectangle pauseBtn   = new Rectangle();
    private final Rectangle slowBtn    = new Rectangle();
    private final Rectangle fastBtn    = new Rectangle();
    private final Rectangle swapBtn    = new Rectangle(); // ⚙ quick-settings
    private final Rectangle restartBtn = new Rectangle();

    // Hotswap modal state
    private boolean hotswapOpen = false;
    private final Rectangle swapSpeedCtrl = new Rectangle();
    private final Rectangle swapParamCtrl = new Rectangle();
    private final Rectangle swapCloseBtn  = new Rectangle();

    // 4-grid constants (portrait only)
    private static final float GRID_SCALE  = 28f;
    private static final float[] GRID_OX   = {70f, 370f, 70f, 370f};
    private static final float[] GRID_OY   = {514f, 514f, 80f, 80f};
    private static final String[] GRID_TAG = {"BEST", "2ND", "3RD", "4TH"};

    // Hotswap param arrays (mirrors AiPlaygroundScreen)
    private static final int[]    ROLLOUTS = {40, 80, 150, 300};
    private static final int[]    POP      = {16, 24, 40, 64, 128, 256, 512, 1000};
    private static final int[]    RETURNS  = {1000, 2000, 4000};
    private static final double[] LRS      = {1e-3, 3e-3, 1e-2};

    public ControlCenterScreen(SuikaGame game, PlaygroundConfig cfg) {
        this.game   = game;
        this.cfg    = cfg;
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
        if (landscape) { board.useLandscape(); layoutButtonsLandscape(); }
        else           { board.usePortrait();  layoutButtonsPortrait();  }
    }

    private void layoutButtonsPortrait() {
        backBtn.set(20, 16, 100, 46);
        pauseBtn.set(128, 16, 100, 46);
        slowBtn.set(240, 16, 44, 46);
        fastBtn.set(344, 16, 44, 46);
        swapBtn.set(400, 16, 78, 46);
        restartBtn.set(572, 16, 120, 46);
    }

    private void layoutButtonsLandscape() {
        backBtn.set(24, 16, 100, 42);
        pauseBtn.set(132, 16, 100, 42);
        slowBtn.set(244, 16, 44, 42);
        fastBtn.set(346, 16, 44, 42);
        swapBtn.set(400, 16, 72, 42);
        restartBtn.set(1130, 16, 120, 42);
    }

    @Override
    public void show() {
        runner.start();
        Gdx.input.setInputProcessor(new InputAdapter() {
            @Override public boolean touchDown(int sx, int sy, int p, int b) {
                camera.unproject(touch.set(sx, sy, 0),
                        viewport.getScreenX(), viewport.getScreenY(),
                        viewport.getScreenWidth(), viewport.getScreenHeight());
                handleClick(touch.x, touch.y);
                return true;
            }
            @Override public boolean mouseMoved(int sx, int sy) { updateMouse(sx, sy); return false; }
            @Override public boolean touchDragged(int sx, int sy, int p) { updateMouse(sx, sy); return false; }
            @Override public boolean keyDown(int k) {
                switch (k) {
                    case Input.Keys.ESCAPE -> {
                        if (hotswapOpen) { hotswapOpen = false; return true; }
                        game.setScreen(new AiPlaygroundScreen(game, cfg));
                    }
                    case Input.Keys.SPACE  -> runner.setPaused(!runner.paused());
                    case Input.Keys.R      -> runner.restart();
                    case Input.Keys.EQUALS, Input.Keys.PLUS -> changeSpeed(+1);
                    case Input.Keys.MINUS                   -> changeSpeed(-1);
                    default -> { return false; }
                }
                return true;
            }
        });
    }

    private void updateMouse(int sx, int sy) {
        camera.unproject(touch.set(sx, sy, 0),
                viewport.getScreenX(), viewport.getScreenY(),
                viewport.getScreenWidth(), viewport.getScreenHeight());
        mx = touch.x; my = touch.y;
        float ox = board.isLandscape() ? BoardRenderer.OX_L : BoardRenderer.OX;
        float sc = board.isLandscape() ? BoardRenderer.SCALE_L : BoardRenderer.SCALE;
        hoverGameX = MathUtils.clamp((mx - ox) / sc,
                (float) PhysicsConfig.DROP_X_MIN, (float) PhysicsConfig.DROP_X_MAX);
    }

    private void handleClick(float x, float y) {
        // Hotswap modal intercepts all clicks
        if (hotswapOpen) {
            if (swapSpeedCtrl.contains(x, y)) { changeSpeed(x < swapSpeedCtrl.x + swapSpeedCtrl.width / 2f ? -1 : +1); return; }
            if (swapParamCtrl.contains(x, y) && paramApplicable()) {
                cycleParam(x < swapParamCtrl.x + swapParamCtrl.width / 2f ? -1 : +1); return;
            }
            if (swapCloseBtn.contains(x, y)) { hotswapOpen = false; return; }
            // click outside modal closes it
            float mx2 = swapModalX(), my2 = swapModalY();
            if (x < mx2 || x > mx2 + 460f || y < my2 || y > my2 + 280f) { hotswapOpen = false; }
            return;
        }

        if (backBtn.contains(x, y))    { game.setScreen(new AiPlaygroundScreen(game, cfg)); return; }
        if (pauseBtn.contains(x, y))   { runner.setPaused(!runner.paused()); return; }
        if (slowBtn.contains(x, y))    { changeSpeed(-1); return; }
        if (fastBtn.contains(x, y))    { changeSpeed(+1); return; }
        if (swapBtn.contains(x, y))    { openHotswap(); return; }
        if (restartBtn.contains(x, y)) { runner.restart(); return; }
        if (runner.acceptsHumanInput()) runner.humanDrop(hoverGameX);
    }

    private void changeSpeed(int d) {
        cfg.speedIndex = MathUtils.clamp(cfg.speedIndex + d, 0, PlaygroundConfig.SPEEDS.length - 1);
        runner.setSpeed(cfg.speed());
    }

    // ---- hotswap modal ----
    private static final float SWAP_MW = 460f, SWAP_MH = 280f;
    private float swapModalX() { return landscape ? (Theme.VW_L - SWAP_MW) / 2f : (Theme.VW - SWAP_MW) / 2f; }
    private float swapModalY() { return landscape ? (Theme.VH_L - SWAP_MH) / 2f : (Theme.VH - SWAP_MH) / 2f; }

    private void openHotswap() {
        hotswapOpen = true;
        float mx2 = swapModalX(), my2 = swapModalY();
        swapSpeedCtrl.set(mx2 + 240, my2 + SWAP_MH - 90,  200, 34);
        swapParamCtrl.set(mx2 + 240, my2 + SWAP_MH - 142, 200, 34);
        swapCloseBtn.set( mx2 + SWAP_MW / 2f - 90, my2 + 18, 180, 40);
    }

    // ---- param helpers (mirrors AiPlaygroundScreen) ----
    private boolean paramApplicable() {
        return switch (cfg.technique) {
            case MCTS, ALPHAZERO, NEUROEVO, PBT,
                 DECISION_TRANSFORMER, OFFLINE_RL, BC, DAGGER -> true;
            default -> false;
        };
    }
    private String paramLabel() {
        return switch (cfg.technique) {
            case MCTS, ALPHAZERO               -> "Rollouts";
            case NEUROEVO, PBT                 -> "Population";
            case DECISION_TRANSFORMER, OFFLINE_RL -> "Return";
            case BC, DAGGER                    -> "LR";
            default                            -> "—";
        };
    }
    private String paramValue() {
        return switch (cfg.technique) {
            case MCTS, ALPHAZERO               -> Integer.toString(cfg.rollouts);
            case NEUROEVO, PBT                 -> Integer.toString(cfg.populationSize);
            case DECISION_TRANSFORMER, OFFLINE_RL -> Integer.toString((int) cfg.targetReturn);
            case BC, DAGGER                    -> String.format("%.0e", cfg.learningRate);
            default                            -> "—";
        };
    }
    private void cycleParam(int d) {
        switch (cfg.technique) {
            case MCTS, ALPHAZERO               -> cfg.rollouts       = cycleInt(ROLLOUTS, cfg.rollouts, d);
            case NEUROEVO, PBT                 -> cfg.populationSize = cycleInt(POP, cfg.populationSize, d);
            case DECISION_TRANSFORMER, OFFLINE_RL -> cfg.targetReturn = cycleInt(RETURNS, (int) cfg.targetReturn, d);
            case BC, DAGGER                    -> cfg.learningRate   = cycleDouble(LRS, cfg.learningRate, d);
            default -> { }
        }
    }
    private int cycleInt(int[] opts, int cur, int d) {
        int idx = 0; for (int i = 0; i < opts.length; i++) if (opts[i] == cur) idx = i;
        return opts[Math.floorMod(idx + d, opts.length)];
    }
    private double cycleDouble(double[] opts, double cur, int d) {
        int idx = 0; for (int i = 0; i < opts.length; i++) if (Math.abs(opts[i]-cur) < 1e-9) idx = i;
        return opts[Math.floorMod(idx + d, opts.length)];
    }

    // ---- 4-grid helpers ----
    private boolean isEvo4Grid() {
        return !landscape && !cfg.ghostView && runner instanceof EvolutionRunner;
    }

    // -------------------------------------------------------------------------
    // Render
    // -------------------------------------------------------------------------

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

        ShapeRenderer s = game.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);
        board.drawBackground(s);

        if (isEvo4Grid()) {
            render4GridShapes(s);
        } else {
            // single main board
            boolean human = runner.acceptsHumanInput();
            board.setHover(human ? hoverGameX : Float.NaN, runner.board().currentFruitTier());
            board.drawBoard(s, runner.board(), game.settings, game.particles);
            // ghost overlays
            if (cfg.ghostView && runner instanceof EvolutionRunner er) {
                GameState[] ghosts = er.ghostStates();
                if (ghosts != null) for (GameState g : ghosts) if (g != null) board.drawBoard(s, g, game.settings, null, 0.12f);
            }
            drawColumnOverlay(s, runner.board());
        }

        drawControlBar(s);
        s.end();

        // tier labels
        game.batch.begin();
        if (isEvo4Grid()) {
            render4GridLabels();
        } else {
            board.usePortrait();
            board.drawLabels(game.batch, game.fontSmall, runner.board(), game.settings);
        }
        game.batch.end();

        // diagnostics panel (opaque — masks fruit behind it)
        s.begin(ShapeRenderer.ShapeType.Filled);
        drawPanel(s);
        s.end();

        // text pass
        game.batch.begin();
        drawPanelText(runner.board());
        drawControlBarText();
        game.scorePops.draw(game.batch, game.fontMed);
        game.batch.end();

        if (runner.modalActive()) drawModal();
        if (hotswapOpen) drawHotswap();
    }

    // ---- 4-grid rendering ----

    private void render4GridShapes(ShapeRenderer s) {
        EvolutionRunner er = (EvolutionRunner) runner;
        GameState[] states = er.topStates();
        board.setHover(Float.NaN, null);
        for (int i = 0; i < 4; i++) {
            if (states[i] == null) continue;
            board.useCustom(GRID_OX[i], GRID_OY[i], GRID_SCALE);
            board.drawBoard(s, states[i], game.settings, null, 1f);
        }
        board.usePortrait(); // restore for panel/control rendering
    }

    private void render4GridLabels() {
        EvolutionRunner er = (EvolutionRunner) runner;
        GameState[] states = er.topStates();
        for (int i = 0; i < 4; i++) {
            if (states[i] == null) continue;
            board.useCustom(GRID_OX[i], GRID_OY[i], GRID_SCALE);
            board.drawLabels(game.batch, game.fontSmall, states[i], game.settings);
            // Tag label (BEST / 2ND / ...) inside top-left of each mini board
            float tagX = GRID_OX[i] + 4f;
            float tagY = GRID_OY[i] + 15f * GRID_SCALE - 18f;
            Ui.text(game.batch, game.fontSmall, GRID_TAG[i], tagX, tagY, Theme.TEXT_DIM);
            // Score at floor level
            Ui.text(game.batch, game.fontSmall, Long.toString(states[i].score()),
                    tagX, GRID_OY[i] + 22f, Theme.GOLD);
        }
        board.usePortrait();
    }

    /** MCTS visit bars / chosen-column marker. */
    private void drawColumnOverlay(ShapeRenderer s, GameState gs) {
        float baseY = board.bvpy(PhysicsConfig.CONTAINER_HEIGHT) - 6f;
        int[] bars = runner.columnBars();
        if (bars != null && bars.length > 0) {
            int max = 1;
            for (int v : bars) max = Math.max(max, v);
            float barW = (board.bvpx(PhysicsConfig.CONTAINER_WIDTH) - board.bvpx(0)) / bars.length * 0.7f;
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

    private float[] panelBounds() {
        if (landscape) return new float[]{12f, 72f, 650f, Theme.VH_L - 90f};
        return new float[]{26f, 980f, Theme.VW - 52f, 286f};
    }

    private void drawPanel(ShapeRenderer s) {
        float[] p = panelBounds();
        float px = p[0], py = p[1], pw = p[2], ph = p[3];
        s.setColor(0.07f, 0.08f, 0.12f, 1f);
        Ui.fillRoundRect(s, px - 4, py - 4, pw + 8, ph + 8, 18);
        Ui.panel(s, px, py, pw, ph, 16, Theme.PANEL, Theme.PANEL_EDGE);
        float cw = landscape ? 200f : 286f;
        float cx = px + pw - cw - 16;
        float chart1Y = landscape ? py + ph - 140f : py + 150f;
        float chart2Y = landscape ? py + ph - 260f : py + 26f;
        chartFrame(s, cx, chart1Y, cw, 96, runner.chart1(), Theme.ACCENT_2);
        if (runner.chart2() != null) chartFrame(s, cx, chart2Y, cw, 96, runner.chart2(), Theme.ACCENT_BLUE);
    }

    private void chartFrame(ShapeRenderer s, float x, float y, float w, float h,
                            LiveChart c, Color col) {
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

        Ui.text(game.batch, game.fontMed,   runner.title(),    px + 18, py + ph - 22, Theme.TEXT);
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
        Ui.button(s, backBtn,    Theme.PANEL_EDGE,  backBtn.contains(mx, my),    true);
        Ui.button(s, pauseBtn,   Theme.ACCENT_BLUE, pauseBtn.contains(mx, my),   true);
        Ui.button(s, slowBtn,    Theme.PANEL_EDGE,  slowBtn.contains(mx, my),    true);
        Ui.button(s, fastBtn,    Theme.PANEL_EDGE,  fastBtn.contains(mx, my),    true);
        Ui.button(s, swapBtn,    Theme.PANEL,       swapBtn.contains(mx, my),    true);
        Ui.button(s, restartBtn, Theme.ACCENT,      restartBtn.contains(mx, my), true);
    }

    private void drawControlBarText() {
        Ui.textCenter(game.batch, game.fontSmall, "BACK",
                backBtn.x + backBtn.width / 2f, backBtn.y + 28, Theme.TEXT);
        Ui.textCenter(game.batch, game.fontSmall, runner.paused() ? "RESUME" : "PAUSE",
                pauseBtn.x + pauseBtn.width / 2f, pauseBtn.y + 28, Theme.TEXT);
        Ui.textCenter(game.batch, game.fontMed, "−",
                slowBtn.x + slowBtn.width / 2f, slowBtn.y + 27, Theme.TEXT);
        Ui.textCenter(game.batch, game.fontMed, "+",
                fastBtn.x + fastBtn.width / 2f, fastBtn.y + 27, Theme.TEXT);
        // Speed label between the -/+ buttons (same Y as button text)
        float speedLabelX = (slowBtn.x + slowBtn.width + fastBtn.x) / 2f;
        Ui.textCenter(game.batch, game.fontSmall, cfg.speedLabel(),
                speedLabelX, slowBtn.y + 27, Theme.GOLD);
        // Swap button "⚙"
        Ui.textCenter(game.batch, game.fontSmall, "SETUP",
                swapBtn.x + swapBtn.width / 2f, swapBtn.y + 28, Theme.TEXT_DIM);
        Ui.textCenter(game.batch, game.fontSmall, "RESTART",
                restartBtn.x + restartBtn.width / 2f, restartBtn.y + 28, Theme.TEXT);
        if (runner.acceptsHumanInput())
            Ui.textCenter(game.batch, game.fontSmall, "click the well to drop",
                    landscape ? 900f : Theme.VW / 2f, 84, Theme.TEXT_FAINT);
    }

    // ---- hotswap modal ----

    private void drawHotswap() {
        float vw = landscape ? Theme.VW_L : Theme.VW;
        float vh = landscape ? Theme.VH_L : Theme.VH;
        float mx2 = swapModalX(), my2 = swapModalY();

        ShapeRenderer s = game.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);
        s.setColor(0f, 0f, 0f, 0.82f);
        s.rect(0, 0, vw, vh);
        s.setColor(Theme.PANEL_DEEP);
        Ui.fillRoundRect(s, mx2, my2, SWAP_MW, SWAP_MH, 16);
        s.setColor(Theme.ACCENT_BLUE);
        Ui.fillRoundRect(s, mx2, my2 + SWAP_MH - 4f, SWAP_MW, 4f, 3f);
        // speed cycler
        drawHotswapCycler(s, swapSpeedCtrl);
        // param cycler (if applicable)
        if (paramApplicable()) drawHotswapCycler(s, swapParamCtrl);
        else {
            s.setColor(0.10f, 0.11f, 0.16f, 0.6f);
            Ui.fillRoundRect(s, swapParamCtrl.x, swapParamCtrl.y,
                    swapParamCtrl.width, swapParamCtrl.height, 8);
        }
        Ui.button(s, swapCloseBtn, Theme.PANEL_EDGE, swapCloseBtn.contains(mx, my), true);
        s.end();

        game.batch.begin();
        Ui.textCenter(game.batch, game.fontMed, "QUICK SETTINGS",
                mx2 + SWAP_MW / 2f, my2 + SWAP_MH - 36, Theme.TEXT);
        // speed
        Ui.text(game.batch, game.font, "Speed",
                mx2 + 20, swapSpeedCtrl.y + 24, Theme.TEXT);
        Ui.textCenter(game.batch, game.font, cfg.speedLabel(),
                swapSpeedCtrl.x + swapSpeedCtrl.width / 2f,
                swapSpeedCtrl.y + 22, Theme.TEXT);
        Ui.textCenter(game.batch, game.fontMed, "−",
                swapSpeedCtrl.x + 18, swapSpeedCtrl.y + 22, Theme.TEXT);
        Ui.textCenter(game.batch, game.fontMed, "+",
                swapSpeedCtrl.x + swapSpeedCtrl.width - 18,
                swapSpeedCtrl.y + 22, Theme.TEXT);
        // param
        if (paramApplicable()) {
            Ui.text(game.batch, game.font, paramLabel(),
                    mx2 + 20, swapParamCtrl.y + 24, Theme.TEXT);
            Ui.textCenter(game.batch, game.font, paramValue(),
                    swapParamCtrl.x + swapParamCtrl.width / 2f,
                    swapParamCtrl.y + 22, Theme.TEXT);
            Ui.textCenter(game.batch, game.fontMed, "−",
                    swapParamCtrl.x + 18, swapParamCtrl.y + 22, Theme.TEXT);
            Ui.textCenter(game.batch, game.fontMed, "+",
                    swapParamCtrl.x + swapParamCtrl.width - 18,
                    swapParamCtrl.y + 22, Theme.TEXT);
        } else {
            Ui.text(game.batch, game.font, paramLabel(),
                    mx2 + 20, swapParamCtrl.y + 24, Theme.TEXT_FAINT);
            Ui.textCenter(game.batch, game.fontSmall, "n/a",
                    swapParamCtrl.x + swapParamCtrl.width / 2f,
                    swapParamCtrl.y + 18, Theme.TEXT_FAINT);
        }
        Ui.textCenter(game.batch, game.fontSmall, "CLOSE",
                swapCloseBtn.x + swapCloseBtn.width / 2f, swapCloseBtn.y + 25, Theme.TEXT);
        game.batch.end();
    }

    private void drawHotswapCycler(ShapeRenderer s, Rectangle r) {
        s.setColor(Theme.PANEL_DEEP);
        Ui.fillRoundRect(s, r.x, r.y, r.width, r.height, 8);
        boolean hov = r.contains(mx, my);
        s.setColor(hov && mx < r.x + r.width / 2f ? Theme.ACCENT_BLUE : Theme.PANEL_EDGE);
        Ui.fillRoundRect(s, r.x + 4, r.y + 4, 28, r.height - 8, 6);
        s.setColor(hov && mx >= r.x + r.width / 2f ? Theme.ACCENT_BLUE : Theme.PANEL_EDGE);
        Ui.fillRoundRect(s, r.x + r.width - 32, r.y + 4, 28, r.height - 8, 6);
    }

    // ---- technique-launch modal ----

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
            float vw = landscape ? Theme.VW_L : Theme.VW;
            float vh = landscape ? Theme.VH_L : Theme.VH;
            camera.position.set(vw/2f, vh/2f, 0f);
            camera.update();
        }
    }

    @Override public void hide()    { Gdx.input.setInputProcessor(null); }
    @Override public void dispose() { runner.dispose(); }
}
