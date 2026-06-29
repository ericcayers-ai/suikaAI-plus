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
 * AI control center: live board(s), diagnostics panel, runtime controls.
 * Supports portrait (720×1280) and landscape (1280×720) layouts.
 *
 * <p>Number of boards shown ("views") depends on the technique:
 * <ul>
 *   <li>Evolution technique with ghost overlay OFF → 4 (champion + 3 elites, 2×2 grid)</li>
 *   <li>Self-play technique → 2 (agent vs rival)</li>
 *   <li>Everything else → 1 (the classic interactive board)</li>
 * </ul>
 * Multiple boards are auto-tiled into whatever screen area the side panel leaves free.
 */
public final class ControlCenterScreen extends ScreenAdapter {

    private final SuikaGame game;
    private final PlaygroundConfig cfg;
    private final TechniqueRunner runner;

    private final OrthographicCamera camera = new OrthographicCamera();
    private FitViewport viewport;
    private boolean landscape = false;

    private final BoardRenderer board = new BoardRenderer();
    private final Vector3 touch = new Vector3();
    private float mx, my;
    private float hoverGameX = (float) ((PhysicsConfig.DROP_X_MIN + PhysicsConfig.DROP_X_MAX) / 2.0);

    // Control-bar buttons
    private final Rectangle backBtn    = new Rectangle();
    private final Rectangle pauseBtn   = new Rectangle();
    private final Rectangle slowBtn    = new Rectangle();
    private final Rectangle fastBtn    = new Rectangle();
    private final Rectangle swapBtn    = new Rectangle();
    private final Rectangle restartBtn = new Rectangle();

    // Hotswap (quick-settings) modal
    private boolean hotswapOpen = false;
    private final Rectangle swapSpeedCtrl = new Rectangle();
    private final Rectangle swapParamCtrl = new Rectangle();
    private final Rectangle swapCloseBtn  = new Rectangle();
    private static final float SWAP_MW = 480f, SWAP_MH = 250f;

    // Hotswap param tables (mirror AiPlaygroundScreen)
    private static final int[]    ROLLOUTS = {40, 80, 150, 300};
    private static final int[]    POP      = {16, 24, 40, 64, 128, 256, 512, 1000};
    private static final int[]    RETURNS  = {1000, 2000, 4000};
    private static final double[] LRS      = {1e-3, 3e-3, 1e-2};

    // Board game-unit footprint (well + walls), used for tiling.
    private static final float BOARD_GW = (float) (PhysicsConfig.CONTAINER_WIDTH  + 2 * PhysicsConfig.WALL_THICKNESS);
    private static final float BOARD_GH = (float) (PhysicsConfig.CONTAINER_HEIGHT + PhysicsConfig.WALL_THICKNESS + 1.0);

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
        if (landscape) layoutButtonsLandscape(); else layoutButtonsPortrait();
    }

    private void layoutButtonsPortrait() {
        backBtn.set(20, 16, 100, 46);
        pauseBtn.set(128, 16, 100, 46);
        slowBtn.set(240, 16, 44, 46);
        fastBtn.set(344, 16, 44, 46);
        swapBtn.set(400, 16, 86, 46);
        restartBtn.set(574, 16, 122, 46);
    }

    private void layoutButtonsLandscape() {
        backBtn.set(24, 16, 100, 44);
        pauseBtn.set(132, 16, 100, 44);
        slowBtn.set(244, 16, 44, 44);
        fastBtn.set(348, 16, 44, 44);
        swapBtn.set(404, 16, 86, 44);
        restartBtn.set(1138, 16, 118, 44);
    }

    @Override
    public void show() {
        runner.start();
        Gdx.input.setInputProcessor(new InputAdapter() {
            @Override public boolean touchDown(int sx, int sy, int p, int b) {
                unproject(sx, sy);
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

    private void unproject(int sx, int sy) {
        camera.unproject(touch.set(sx, sy, 0),
                viewport.getScreenX(), viewport.getScreenY(),
                viewport.getScreenWidth(), viewport.getScreenHeight());
    }

    private void updateMouse(int sx, int sy) {
        unproject(sx, sy);
        mx = touch.x; my = touch.y;
        if (viewCount() == 1) {
            float ox = landscape ? BoardRenderer.OX_L : BoardRenderer.OX;
            float sc = landscape ? BoardRenderer.SCALE_L : BoardRenderer.SCALE;
            hoverGameX = MathUtils.clamp((mx - ox) / sc,
                    (float) PhysicsConfig.DROP_X_MIN, (float) PhysicsConfig.DROP_X_MAX);
        }
    }

    private void handleClick(float x, float y) {
        if (hotswapOpen) {
            if (swapSpeedCtrl.contains(x, y)) { changeSpeed(x < swapSpeedCtrl.x + swapSpeedCtrl.width / 2f ? -1 : +1); return; }
            if (swapParamCtrl.contains(x, y) && paramApplicable()) {
                cycleParam(x < swapParamCtrl.x + swapParamCtrl.width / 2f ? -1 : +1); return;
            }
            if (swapCloseBtn.contains(x, y)) { hotswapOpen = false; return; }
            float m0x = swapModalX(), m0y = swapModalY();
            if (x < m0x || x > m0x + SWAP_MW || y < m0y || y > m0y + SWAP_MH) hotswapOpen = false;
            return;
        }
        if (backBtn.contains(x, y))    { game.setScreen(new AiPlaygroundScreen(game, cfg)); return; }
        if (pauseBtn.contains(x, y))   { runner.setPaused(!runner.paused()); return; }
        if (slowBtn.contains(x, y))    { changeSpeed(-1); return; }
        if (fastBtn.contains(x, y))    { changeSpeed(+1); return; }
        if (swapBtn.contains(x, y))    { openHotswap(); return; }
        if (restartBtn.contains(x, y)) { runner.restart(); return; }
        if (viewCount() == 1 && runner.acceptsHumanInput()) runner.humanDrop(hoverGameX);
    }

    private void changeSpeed(int d) {
        cfg.speedIndex = MathUtils.clamp(cfg.speedIndex + d, 0, PlaygroundConfig.SPEEDS.length - 1);
        runner.setSpeed(cfg.speed());
    }

    // ---- view-count rule ----
    private int viewCount() {
        if (runner instanceof EvolutionRunner && !cfg.ghostView) return 4;
        if (cfg.technique == AiTechnique.SELF_PLAY)              return 2;
        return 1;
    }

    // -------------------------------------------------------------------------
    // Hotswap modal
    // -------------------------------------------------------------------------

    private float swapModalX() { return ((landscape ? Theme.VW_L : Theme.VW) - SWAP_MW) / 2f; }
    private float swapModalY() { return ((landscape ? Theme.VH_L : Theme.VH) - SWAP_MH) / 2f; }

    /** Test/QA hook: open the quick-settings modal (used by the capture harness). */
    void openHotswapForCapture() { openHotswap(); }

    private void openHotswap() {
        hotswapOpen = true;
        float m0x = swapModalX(), m0y = swapModalY();
        swapSpeedCtrl.set(m0x + 230, m0y + SWAP_MH - 96,  220, 38);
        swapParamCtrl.set(m0x + 230, m0y + SWAP_MH - 150, 220, 38);
        swapCloseBtn.set( m0x + SWAP_MW / 2f - 90, m0y + 22, 180, 44);
    }

    private boolean paramApplicable() {
        return switch (cfg.technique) {
            case MCTS, ALPHAZERO, NEUROEVO, PBT,
                 DECISION_TRANSFORMER, OFFLINE_RL, BC, DAGGER -> true;
            default -> false;
        };
    }
    private String paramLabel() {
        return switch (cfg.technique) {
            case MCTS, ALPHAZERO                  -> "Rollouts";
            case NEUROEVO, PBT                    -> "Population";
            case DECISION_TRANSFORMER, OFFLINE_RL -> "Return";
            case BC, DAGGER                       -> "LR";
            default                               -> "—";
        };
    }
    private String paramValue() {
        return switch (cfg.technique) {
            case MCTS, ALPHAZERO                  -> Integer.toString(cfg.rollouts);
            case NEUROEVO, PBT                    -> Integer.toString(cfg.populationSize);
            case DECISION_TRANSFORMER, OFFLINE_RL -> Integer.toString((int) cfg.targetReturn);
            case BC, DAGGER                       -> String.format("%.0e", cfg.learningRate);
            default                               -> "—";
        };
    }
    private void cycleParam(int d) {
        switch (cfg.technique) {
            case MCTS, ALPHAZERO                  -> cfg.rollouts       = cycleInt(ROLLOUTS, cfg.rollouts, d);
            case NEUROEVO, PBT                    -> cfg.populationSize = cycleInt(POP, cfg.populationSize, d);
            case DECISION_TRANSFORMER, OFFLINE_RL -> cfg.targetReturn   = cycleInt(RETURNS, (int) cfg.targetReturn, d);
            case BC, DAGGER                       -> cfg.learningRate   = cycleDouble(LRS, cfg.learningRate, d);
            default -> { }
        }
    }
    private int cycleInt(int[] opts, int cur, int d) {
        int idx = 0; for (int i = 0; i < opts.length; i++) if (opts[i] == cur) idx = i;
        return opts[Math.floorMod(idx + d, opts.length)];
    }
    private double cycleDouble(double[] opts, double cur, int d) {
        int idx = 0; for (int i = 0; i < opts.length; i++) if (Math.abs(opts[i] - cur) < 1e-9) idx = i;
        return opts[Math.floorMod(idx + d, opts.length)];
    }

    // -------------------------------------------------------------------------
    // Board tiling
    // -------------------------------------------------------------------------

    /** Free screen area (x, y, w, h) not covered by the side panel or control bar. */
    private float[] boardRegion() {
        if (landscape) {
            float[] p = panelBounds();
            float left = p[0] + p[2] + 24f;
            return new float[]{ left, 78f, Theme.VW_L - left - 16f, Theme.VH_L - 78f - 12f };
        }
        return new float[]{ 10f, 78f, Theme.VW - 20f, 980f - 78f - 8f };
    }

    /** Per-board transform {ox, oy, scale} tiling {@code n} boards into the free region. */
    private float[][] placements(int n) {
        float[] r = boardRegion();
        int cols = n >= 4 ? 2 : n;             // 1→1, 2→2, 4→2
        int rows = (n + cols - 1) / cols;       // 1→1, 2→1, 4→2
        float pad = 10f, tag = 22f;
        float cw = r[2] / cols, ch = r[3] / rows;
        float[][] out = new float[n][3];
        for (int i = 0; i < n; i++) {
            int cxIdx = i % cols, cyIdx = i / cols;
            float cellX = r[0] + cxIdx * cw;
            // row 0 sits at the TOP of the region
            float cellY = r[1] + (rows - 1 - cyIdx) * ch;
            float availW = cw - 2 * pad, availH = ch - 2 * pad - tag;
            float sc = Math.min(availW / BOARD_GW, availH / BOARD_GH);
            float ox = cellX + cw / 2f - 5f * sc;                      // centre the 10-wide well
            float oy = cellY + pad + (availH - (float) PhysicsConfig.CONTAINER_HEIGHT * sc) / 2f;
            out[i] = new float[]{ ox, oy, sc };
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Render
    // -------------------------------------------------------------------------

    @Override
    public void render(float delta) {
        delta = Math.min(delta, 0.05f);
        int views = viewCount();
        if (views == 1 && runner.acceptsHumanInput()) runner.setHover(hoverGameX);
        runner.update(delta);
        // Score pops are positioned in full-board space, so they only make sense on the
        // single interactive board — clear them while a multi-board grid is shown.
        if (views == 1) game.scorePops.update(delta); else game.scorePops.clear();

        float vw = landscape ? Theme.VW_L : Theme.VW;
        float vh = landscape ? Theme.VH_L : Theme.VH;

        Gdx.gl.glClearColor(0.05f, 0.06f, 0.10f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        viewport.apply();
        game.shapes.setProjectionMatrix(camera.combined);
        game.batch.setProjectionMatrix(camera.combined);

        ShapeRenderer s = game.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);
        s.rect(0, 0, vw, vh, Theme.BG_BOTTOM, Theme.BG_BOTTOM, Theme.BG_TOP, Theme.BG_TOP);

        if (views == 1) {
            if (landscape) board.useLandscape(); else board.usePortrait();
            boolean human = runner.acceptsHumanInput();
            board.setHover(human ? hoverGameX : Float.NaN, runner.board().currentFruitTier());
            board.drawBoard(s, runner.board(), game.settings, game.particles);
            if (cfg.ghostView && runner instanceof EvolutionRunner er) {
                GameState[] ghosts = er.ghostStates();
                if (ghosts != null) for (GameState g : ghosts)
                    if (g != null) board.drawBoard(s, g, game.settings, null, 0.12f);
            }
            drawColumnOverlay(s, runner.board());
        } else {
            renderGridShapes(s, views);
        }

        drawControlBar(s);
        s.end();

        // board labels (own batch pass)
        game.batch.begin();
        if (views == 1) {
            if (landscape) board.useLandscape(); else board.usePortrait();
            board.drawLabels(game.batch, game.fontSmall, runner.board(), game.settings);
        } else {
            renderGridLabels(views);
        }
        game.batch.end();

        // diagnostics panel (opaque)
        s.begin(ShapeRenderer.ShapeType.Filled);
        drawPanel(s);
        s.end();

        game.batch.begin();
        drawPanelText();
        drawControlBarText();
        if (views == 1) game.scorePops.draw(game.batch, game.fontMed);
        game.batch.end();

        if (runner.modalActive()) drawModal();
        if (hotswapOpen) drawHotswap();
    }

    // ---- multi-board grid ----

    private void renderGridShapes(ShapeRenderer s, int views) {
        GameState[] states = runner.multiStates();
        float[][] place = placements(views);
        board.setHover(Float.NaN, null);
        for (int i = 0; i < views; i++) {
            if (i >= states.length || states[i] == null) continue;
            board.useCustom(place[i][0], place[i][1], place[i][2]);
            board.drawBoard(s, states[i], game.settings, null, 1f);
        }
    }

    private void renderGridLabels(int views) {
        GameState[] states = runner.multiStates();
        String[] labels = runner.multiLabels();
        float[][] place = placements(views);
        for (int i = 0; i < views; i++) {
            if (i >= states.length || states[i] == null) continue;
            float ox = place[i][0], oy = place[i][1], sc = place[i][2];
            board.useCustom(ox, oy, sc);
            board.drawLabels(game.batch, game.fontSmall, states[i], game.settings);
            String tag = i < labels.length ? labels[i] : "VIEW " + (i + 1);
            float tagY = oy + (float) PhysicsConfig.CONTAINER_HEIGHT * sc + 16f;
            Ui.text(game.batch, game.fontSmall, tag,
                    ox - (float) PhysicsConfig.WALL_THICKNESS * sc, tagY, Theme.TEXT);
        }
    }

    /** MCTS visit bars / chosen-column marker (single-view only). */
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
        if (landscape) return new float[]{12f, 72f, 470f, Theme.VH_L - 90f};
        return new float[]{26f, 980f, Theme.VW - 52f, 286f};
    }

    private void drawPanel(ShapeRenderer s) {
        float[] p = panelBounds();
        float px = p[0], py = p[1], pw = p[2], ph = p[3];
        s.setColor(0.07f, 0.08f, 0.12f, 1f);
        Ui.fillRoundRect(s, px - 4, py - 4, pw + 8, ph + 8, 18);
        Ui.panel(s, px, py, pw, ph, 16, Theme.PANEL, Theme.PANEL_EDGE);

        for (float[] c : chartSlots()) {
            chartFrame(s, c[0], c[1], c[2], c[3], chartFor((int) c[4]), chartColor((int) c[4]));
        }
    }

    /** Chart frames as {x, y, w, h, index}. Landscape stacks up to 3; portrait shows 2. */
    private float[][] chartSlots() {
        float[] p = panelBounds();
        float px = p[0], py = p[1], pw = p[2], ph = p[3];
        if (landscape) {
            // Charts live in the right column; the topmost is lowered enough that its
            // caption clears the (wide) title/subtitle in the left column.
            float cw = 200f, cx = px + pw - cw - 16f, chh = 116f;
            return new float[][]{
                {cx, py + ph - 218f, cw, chh, 1},
                {cx, py + ph - 364f, cw, chh, 2},
                {cx, py + ph - 510f, cw, chh, 3},
            };
        }
        float cw = 286f, cx = px + pw - cw - 16f;
        return new float[][]{
            {cx, py + 150f, cw, 96f, 1},
            {cx, py + 26f,  cw, 96f, 2},
        };
    }

    private LiveChart chartFor(int i) {
        return switch (i) { case 1 -> runner.chart1(); case 2 -> runner.chart2(); default -> runner.chart3(); };
    }
    private String chartLabelFor(int i) {
        return switch (i) { case 1 -> runner.chart1Label(); case 2 -> runner.chart2Label(); default -> runner.chart3Label(); };
    }
    private Color chartColor(int i) {
        return switch (i) { case 1 -> Theme.ACCENT_2; case 2 -> Theme.ACCENT_BLUE; default -> Theme.GOLD; };
    }

    private void chartFrame(ShapeRenderer s, float x, float y, float w, float h, LiveChart c, Color col) {
        if (c == null) return;
        s.setColor(Theme.PANEL_DEEP);
        Ui.fillRoundRect(s, x, y, w, h, 8f);
        c.render(s, x + 8, y + 8, w - 16, h - 16, col);
    }

    private void drawPanelText() {
        float[] p = panelBounds();
        float px = p[0], py = p[1], pw = p[2], ph = p[3];

        Ui.text(game.batch, game.fontMed,   runner.title(),    px + 18, py + ph - 22, Theme.TEXT);
        Ui.text(game.batch, game.fontSmall, runner.subtitle(), px + 18, py + ph - 56, Theme.ACCENT_BLUE);

        float ly = py + ph - 86;
        for (String line : runner.stats()) {
            Ui.text(game.batch, game.fontSmall, line, px + 18, ly, Theme.TEXT_DIM);
            ly -= 24;
        }
        if (landscape) {   // extended stats fill the extra vertical room
            ly -= 8;
            for (String line : runner.extendedStats()) {
                Ui.text(game.batch, game.fontSmall, line, px + 18, ly, Theme.TEXT_FAINT);
                ly -= 24;
            }
        }
        for (float[] c : chartSlots()) {
            String lbl = chartLabelFor((int) c[4]);
            if (lbl != null) Ui.text(game.batch, game.fontSmall, lbl, c[0] + 4, c[1] + c[3] + 16, Theme.TEXT_DIM);
        }
    }

    // ---- control bar ----

    private void drawControlBar(ShapeRenderer s) {
        Ui.button(s, backBtn,    Theme.PANEL_EDGE,  backBtn.contains(mx, my),    true);
        Ui.button(s, pauseBtn,   Theme.ACCENT_BLUE, pauseBtn.contains(mx, my),   true);
        Ui.button(s, slowBtn,    Theme.PANEL_EDGE,  slowBtn.contains(mx, my),    true);
        Ui.button(s, fastBtn,    Theme.PANEL_EDGE,  fastBtn.contains(mx, my),    true);
        Ui.button(s, swapBtn,    Theme.GOLD,        swapBtn.contains(mx, my),    true);
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
        // Speed multiplier centred between the −/+ buttons.
        float speedX = (slowBtn.x + slowBtn.width + fastBtn.x) / 2f;
        Ui.textCenter(game.batch, game.fontSmall, cfg.speedLabel(),
                speedX, slowBtn.y + 27, Theme.GOLD);
        Ui.textCenter(game.batch, game.fontSmall, "SETUP",
                swapBtn.x + swapBtn.width / 2f, swapBtn.y + 28, Theme.BG_BOTTOM);
        Ui.textCenter(game.batch, game.fontSmall, "RESTART",
                restartBtn.x + restartBtn.width / 2f, restartBtn.y + 28, Theme.TEXT);
        if (viewCount() == 1 && runner.acceptsHumanInput())
            Ui.textCenter(game.batch, game.fontSmall, "click the well to drop",
                    landscape ? 960f : Theme.VW / 2f, 84, Theme.TEXT_FAINT);
    }

    // ---- hotswap (quick settings) modal ----

    private void drawHotswap() {
        float vw = landscape ? Theme.VW_L : Theme.VW;
        float vh = landscape ? Theme.VH_L : Theme.VH;
        float m0x = swapModalX(), m0y = swapModalY();

        ShapeRenderer s = game.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);
        s.setColor(0f, 0f, 0f, 0.86f);
        s.rect(0, 0, vw, vh);
        s.setColor(0.08f, 0.09f, 0.13f, 1f);                      // opaque backing
        Ui.fillRoundRect(s, m0x, m0y, SWAP_MW, SWAP_MH, 16);
        Ui.panel(s, m0x, m0y, SWAP_MW, SWAP_MH, 16, Theme.PANEL_DEEP, Theme.PANEL_EDGE);
        s.setColor(Theme.GOLD);
        Ui.fillRoundRect(s, m0x, m0y + SWAP_MH - 4f, SWAP_MW, 4f, 3f);

        drawHotswapCycler(s, swapSpeedCtrl, true);
        drawHotswapCycler(s, swapParamCtrl, paramApplicable());
        // Flat CLOSE button (no glossy sheen, so the label stays crisp).
        boolean ch = swapCloseBtn.contains(mx, my);
        s.setColor(0f, 0f, 0f, 0.35f);
        Ui.fillRoundRect(s, swapCloseBtn.x + 3, swapCloseBtn.y - 4, swapCloseBtn.width, swapCloseBtn.height, 14);
        s.setColor(ch ? 1f : 0.92f, ch ? 0.40f : 0.32f, ch ? 0.43f : 0.36f, 1f);
        Ui.fillRoundRect(s, swapCloseBtn.x, swapCloseBtn.y, swapCloseBtn.width, swapCloseBtn.height, 14);
        s.end();

        game.batch.begin();
        Ui.textCenter(game.batch, game.fontMed, "QUICK SETTINGS",
                m0x + SWAP_MW / 2f, m0y + SWAP_MH - 32, Theme.TEXT);
        Ui.text(game.batch, game.font, "Speed", m0x + 24, swapSpeedCtrl.y + 25, Theme.TEXT);
        cyclerGlyphs(swapSpeedCtrl, cfg.speedLabel(), true);
        boolean pa = paramApplicable();
        Ui.text(game.batch, game.font, pa ? paramLabel() : "Parameter",
                m0x + 24, swapParamCtrl.y + 25, pa ? Theme.TEXT : Theme.TEXT_FAINT);
        cyclerGlyphs(swapParamCtrl, pa ? paramValue() : "n/a", pa);
        Ui.textCenter(game.batch, game.fontSmall, "CLOSE",
                swapCloseBtn.x + swapCloseBtn.width / 2f, swapCloseBtn.y + 25, Theme.TEXT);
        game.batch.end();
    }

    private void drawHotswapCycler(ShapeRenderer s, Rectangle r, boolean enabled) {
        s.setColor(enabled ? Theme.PANEL : new Color(0.10f, 0.11f, 0.16f, 0.8f));
        Ui.fillRoundRect(s, r.x, r.y, r.width, r.height, 8);
        if (!enabled) return;
        boolean hov = r.contains(mx, my);
        s.setColor(hov && mx < r.x + r.width / 2f ? Theme.ACCENT_BLUE : Theme.PANEL_EDGE);
        Ui.fillRoundRect(s, r.x + 4, r.y + 4, 34, r.height - 8, 6);
        s.setColor(hov && mx >= r.x + r.width / 2f ? Theme.ACCENT_BLUE : Theme.PANEL_EDGE);
        Ui.fillRoundRect(s, r.x + r.width - 38, r.y + 4, 34, r.height - 8, 6);
    }

    private void cyclerGlyphs(Rectangle r, String value, boolean enabled) {
        Ui.textCenter(game.batch, game.font, value,
                r.x + r.width / 2f, r.y + r.height / 2f, enabled ? Theme.TEXT : Theme.TEXT_FAINT);
        if (!enabled) return;
        Ui.textCenter(game.batch, game.fontMed, "−", r.x + 21, r.y + r.height / 2f + 1, Theme.TEXT);
        Ui.textCenter(game.batch, game.fontMed, "+", r.x + r.width - 21, r.y + r.height / 2f + 1, Theme.TEXT);
    }

    // ---- technique-launch modal (imitation "play first") ----

    private void drawModal() {
        float vw = landscape ? Theme.VW_L : Theme.VW;
        float vh = landscape ? Theme.VH_L : Theme.VH;
        ShapeRenderer s = game.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);
        s.setColor(0f, 0f, 0f, 0.72f);
        s.rect(0, 0, vw, vh);
        s.setColor(0.08f, 0.09f, 0.13f, 1f);
        Ui.fillRoundRect(s, vw / 2f - 290, vh / 2f - 70, 580, 360, 18);
        Ui.panel(s, vw / 2f - 290, vh / 2f - 70, 580, 360, 18, Theme.PANEL, Theme.GOLD);
        s.end();
        game.batch.begin();
        Ui.textCenter(game.batch, game.fontMed, runner.modalTitle(), vw / 2f, vh / 2f + 240, Theme.GOLD);
        float y = vh / 2f + 160;
        for (String line : runner.modalBody()) {
            Ui.textCenter(game.batch, game.font, line, vw / 2f, y, Theme.TEXT);
            y -= 34;
        }
        game.batch.end();
    }

    @Override
    public void resize(int width, int height) {
        applyOrientation(width, height);
        viewport.update(width, height);
    }

    @Override public void hide()    { Gdx.input.setInputProcessor(null); }
    @Override public void dispose() { runner.dispose(); }
}
