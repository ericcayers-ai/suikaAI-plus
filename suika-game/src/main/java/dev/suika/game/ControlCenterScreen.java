package dev.suika.game;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
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
    private final Rectangle slotsBtn   = new Rectangle();
    private final Rectangle restartBtn = new Rectangle();

    // Save/load slots modal — only meaningful for Evolution (champion weights) and
    // Imitation (BC/DAgger policy weights); the button is hidden for every other
    // family since there's no trainable state to persist.
    private boolean slotsOpen = false;
    private final Rectangle[] slotSaveBtn = { new Rectangle(), new Rectangle(), new Rectangle() };
    private final Rectangle[] slotLoadBtn = { new Rectangle(), new Rectangle(), new Rectangle() };
    private final Rectangle   slotsCloseBtn = new Rectangle();
    private static final float SLOTS_MW = 560f, SLOTS_MH = 360f;
    private volatile String slotsMessage = "";

    // Hotswap (quick-settings) modal — mirrors the AI Playground drawer so every
    // per-technique launch knob (including evolution's sims/gen + ghost lineage) is
    // reachable mid-run, not just before LAUNCH.
    private boolean hotswapOpen = false;
    private final Rectangle swapSpeedCtrl     = new Rectangle();
    private final Rectangle swapParaCtrl      = new Rectangle();
    private final Rectangle swapParamCtrl     = new Rectangle();
    private final Rectangle swapSimsCtrl      = new Rectangle();
    private final Rectangle swapGhostCullCtrl = new Rectangle();
    private final Rectangle swapEliteViewCtrl = new Rectangle();
    private final Rectangle swapCloseBtn      = new Rectangle();
    private static final float SWAP_MW = 480f, SWAP_MH = 500f;

    // Hotswap param tables (mirror AiPlaygroundScreen)
    private static final int[]    ROLLOUTS = {40, 80, 150, 300};
    private static final int[]    POP      = {16, 24, 40, 64, 128, 256, 512, 1000};
    private static final int[]    RETURNS  = {1000, 2000, 4000};
    private static final double[] LRS      = {1e-3, 3e-3, 1e-2};

    // Board game-unit footprint (well + walls), used for tiling.
    private static final float BOARD_GW = (float) (PhysicsConfig.CONTAINER_WIDTH  + 2 * PhysicsConfig.WALL_THICKNESS);
    private static final float BOARD_GH = (float) (PhysicsConfig.CONTAINER_HEIGHT + PhysicsConfig.WALL_THICKNESS + 1.0);

    // Shared with placements(): how much screen space is reserved above each mini-board
    // for its caption. renderGridShapes() clips fruit rendering to this same budget so a
    // tall/dense stack can never visually bleed into the label above it.
    private static final float GRID_CELL_PAD = 10f, GRID_TAG_H = 46f;

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
        slotsBtn.set(492, 16, 74, 46);
        restartBtn.set(574, 16, 122, 46);
    }

    private void layoutButtonsLandscape() {
        backBtn.set(24, 16, 100, 44);
        pauseBtn.set(132, 16, 100, 44);
        slowBtn.set(244, 16, 44, 44);
        fastBtn.set(348, 16, 44, 44);
        swapBtn.set(404, 16, 86, 44);
        slotsBtn.set(496, 16, 92, 44);
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
            hoverGameX = clampDropHover((mx - ox) / sc, runner.board());
        } else if (imitationDual()) {
            float[] p = placements(2)[0];                 // YOU = left board
            hoverGameX = clampDropHover((mx - p[0]) / p[2], runner.board());
        }
    }

    /** Clamp a hovered game-x so the previewed/dropped fruit stays fully inside the walls. */
    private float clampDropHover(float gx, GameState gs) {
        float r = gs != null && gs.currentFruitTier() != null ? gs.currentFruitTier().radius : 0.5f;
        float lo = (float) PhysicsConfig.LEFT_WALL_X  + r;
        float hi = (float) PhysicsConfig.RIGHT_WALL_X - r;
        if (lo > hi) { lo = hi = (float) (PhysicsConfig.LEFT_WALL_X + PhysicsConfig.RIGHT_WALL_X) / 2f; }
        return MathUtils.clamp(gx, lo, hi);
    }

    /** Only Evolution (champion weights) and Imitation (BC/DAgger policy) have trainable
     *  state worth persisting — every other family's JVM surrogate has nothing to save. */
    private boolean slotsSupported() {
        return cfg.technique.family == AiTechnique.Family.EVOLUTION
                || cfg.technique.family == AiTechnique.Family.IMITATION;
    }

    private void handleClick(float x, float y) {
        if (slotsOpen) {
            for (int i = 0; i < ModelSlots.SLOT_COUNT; i++) {
                if (slotSaveBtn[i].contains(x, y)) { doSaveSlot(i + 1); return; }
                if (slotLoadBtn[i].contains(x, y)) { doLoadSlot(i + 1); return; }
            }
            if (slotsCloseBtn.contains(x, y)) { slotsOpen = false; return; }
            float m0x = slotsModalX(), m0y = slotsModalY();
            if (x < m0x || x > m0x + SLOTS_MW || y < m0y || y > m0y + SLOTS_MH) slotsOpen = false;
            return;
        }
        if (hotswapOpen) {
            boolean evo = ghostApplicable();
            if (swapSpeedCtrl.contains(x, y)) { changeSpeed(x < swapSpeedCtrl.x + swapSpeedCtrl.width / 2f ? -1 : +1); return; }
            if (swapParaCtrl.contains(x, y) && cfg.technique.parallel) {
                int cores = Runtime.getRuntime().availableProcessors();
                cfg.parallelism = MathUtils.clamp(cfg.parallelism + (x < swapParaCtrl.x + swapParaCtrl.width / 2f ? -1 : +1), 0, cores);
                return;
            }
            if (swapParamCtrl.contains(x, y) && paramApplicable()) {
                cycleParam(x < swapParamCtrl.x + swapParamCtrl.width / 2f ? -1 : +1); return;
            }
            if (swapSimsCtrl.contains(x, y) && evo) {
                int d = x < swapSimsCtrl.x + swapSimsCtrl.width / 2f ? -1 : +1;
                cfg.simsPerGenIndex = Math.floorMod(cfg.simsPerGenIndex + d, PlaygroundConfig.SIMS_PER_GEN_OPTIONS.length);
                return;
            }
            if (swapGhostCullCtrl.contains(x, y) && evo) {
                int d = x < swapGhostCullCtrl.x + swapGhostCullCtrl.width / 2f ? -1 : +1;
                cfg.ghostCullIndex = Math.floorMod(cfg.ghostCullIndex + d, PlaygroundConfig.GHOST_CULL_OPTIONS.length);
                return;
            }
            if (swapEliteViewCtrl.contains(x, y) && evo) {
                int d = x < swapEliteViewCtrl.x + swapEliteViewCtrl.width / 2f ? -1 : +1;
                cfg.eliteViewIndex = Math.floorMod(cfg.eliteViewIndex + d, PlaygroundConfig.ELITE_VIEW_OPTIONS.length);
                return;
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
        if (slotsBtn.contains(x, y) && slotsSupported()) { openSlots(); return; }
        if (restartBtn.contains(x, y)) { runner.restart(); return; }
        // Gate human drops on the chute being clear — matches SuikaScreen's classic-play
        // guard, which this dispatch was previously missing, letting a fast clicker stack
        // fruit in the chute above the well instead of one drop settling before the next.
        if (viewCount() == 1 && runner.acceptsHumanInput()) {
            if (chuteClear()) runner.humanDrop(hoverGameX);
            return;
        }
        // Imitation dual view: only the YOU board (left) accepts drops.
        if (imitationDual() && runner.acceptsHumanInput()) {
            float[] p = placements(2)[0];
            float wallT = (float) PhysicsConfig.WALL_THICKNESS * p[2];
            float left  = p[0] - wallT;
            float right = p[0] + (float) PhysicsConfig.CONTAINER_WIDTH * p[2] + wallT;
            if (x >= left && x <= right && y > 70f && chuteClear()) runner.humanDrop(hoverGameX);
        }
    }

    /** True when nothing in the primary board is still falling through the chute above the rim. */
    private boolean chuteClear() {
        double thresh = PhysicsConfig.CONTAINER_HEIGHT - 0.5;
        for (var f : runner.board().fruits()) if (f.y() > thresh) return false;
        return true;
    }

    private void changeSpeed(int d) {
        cfg.speedIndex = MathUtils.clamp(cfg.speedIndex + d, 0, PlaygroundConfig.SPEEDS.length - 1);
        runner.setSpeed(cfg.speed());
    }

    // ---- view-count rule ----
    private int viewCount() {
        if (runner instanceof EvolutionRunner && !cfg.ghostView) return cfg.eliteViewCount();
        if (cfg.technique == AiTechnique.SELF_PLAY)              return 2;
        if (cfg.technique.family == AiTechnique.Family.IMITATION) return 2; // YOU | AI clone
        return 1;
    }

    private boolean imitationDual() {
        return cfg.technique.family == AiTechnique.Family.IMITATION && viewCount() == 2;
    }

    // -------------------------------------------------------------------------
    // Save / load slots modal
    // -------------------------------------------------------------------------

    private float slotsModalX() { return ((landscape ? Theme.VW_L : Theme.VW) - SLOTS_MW) / 2f; }
    private float slotsModalY() { return ((landscape ? Theme.VH_L : Theme.VH) - SLOTS_MH) / 2f; }

    /** Test/QA hook: open the save/load modal (used by the capture harness). */
    void openSlotsForCapture() { openSlots(); }

    private void openSlots() {
        slotsOpen = true;
        slotsMessage = "";
        layoutSlotsModal();
    }

    private void doSaveSlot(int slot) {
        boolean ok = runner instanceof EvolutionRunner er ? er.saveToSlot(slot)
                   : runner instanceof ImitationRunner ir ? ir.saveToSlot(slot)
                   : false;
        slotsMessage = ok ? "Saved to slot " + slot : "Nothing to save yet";
    }

    private void doLoadSlot(int slot) {
        boolean ok = runner instanceof EvolutionRunner er ? er.loadFromSlot(slot)
                   : runner instanceof ImitationRunner ir ? ir.loadFromSlot(slot)
                   : false;
        slotsMessage = ok ? "Loaded slot " + slot
                : (runner instanceof EvolutionRunner || runner instanceof ImitationRunner)
                        ? "Slot " + slot + " is empty" : "Not supported here";
    }

    private ModelSlots.SlotInfo slotInfo(int slot) {
        if (runner instanceof EvolutionRunner er) return er.slotInfo(slot);
        if (runner instanceof ImitationRunner ir) return ir.slotInfo(slot);
        return ModelSlots.SlotInfo.EMPTY;
    }

    // -------------------------------------------------------------------------
    // Hotswap modal
    // -------------------------------------------------------------------------

    private float swapModalX() { return ((landscape ? Theme.VW_L : Theme.VW) - SWAP_MW) / 2f; }
    private float swapModalY() { return ((landscape ? Theme.VH_L : Theme.VH) - SWAP_MH) / 2f; }

    /** Test/QA hook: open the quick-settings modal (used by the capture harness). */
    void openHotswapForCapture() { openHotswap(); }

    /** Test/QA hook: drop in the centre column (dismisses the imitation "play first" modal). */
    void forceHumanDropForCapture() {
        forceHumanDropForCapture((PhysicsConfig.DROP_X_MIN + PhysicsConfig.DROP_X_MAX) / 2.0);
    }

    /** Test/QA hook: drop at a specific game-x — used to auto-play a full game for capture. */
    void forceHumanDropForCapture(double gx) {
        if (runner.acceptsHumanInput()) runner.humanDrop((float) gx);
    }

    /** Test/QA hook: true when the primary board's chute is clear (safe to drop). */
    boolean chuteClearForCapture() { return chuteClear(); }

    /** Test/QA hook: true once the primary board's game has ended. */
    boolean isGameOverForCapture() { return runner.board().gameOver(); }

    /** Test/QA hook: jump imitation runners straight to the TRAIN phase (see {@link ImitationRunner#forceTrainPhaseForCapture}). */
    void forceImitationTrainPhaseForCapture() {
        if (runner instanceof ImitationRunner ir) ir.forceTrainPhaseForCapture();
    }

    private void openHotswap() {
        hotswapOpen = true;
        float m0x = swapModalX(), m0y = swapModalY();
        swapSpeedCtrl.set(    m0x + 230, m0y + SWAP_MH - 96,  220, 38);
        swapParaCtrl.set(     m0x + 230, m0y + SWAP_MH - 150, 220, 38);
        swapParamCtrl.set(    m0x + 230, m0y + SWAP_MH - 204, 220, 38);
        swapSimsCtrl.set(     m0x + 230, m0y + SWAP_MH - 258, 220, 38);
        swapGhostCullCtrl.set(m0x + 230, m0y + SWAP_MH - 312, 220, 38);
        swapEliteViewCtrl.set(m0x + 230, m0y + SWAP_MH - 366, 220, 38);
        swapCloseBtn.set( m0x + SWAP_MW / 2f - 90, m0y + 22, 180, 44);
    }

    private boolean ghostApplicable() {
        return cfg.technique.family == AiTechnique.Family.EVOLUTION;
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

    /**
     * Per-board transform {ox, oy, scale} tiling {@code n} boards into the free region.
     *
     * <p>Rows are packed from the TOP of the region using each board's actual rendered
     * height, not centred inside an evenly-divided slice. For 2 side-by-side boards in
     * portrait, width (not height) bounds the scale — the region is much taller than two
     * narrow boards need — so centering left a large, header-hugging dead gap above the
     * boards that read as a layout bug (and an equal one below). Anchoring to the top
     * with a small fixed margin pushes any leftover space to the bottom instead, where
     * it reads as ordinary breathing room above the control bar.
     */
    private float[][] placements(int n) {
        float[] r = boardRegion();
        // Auto-grid: cols = ceil(sqrt(n)) — reproduces the previous hand-picked shapes
        // exactly (1→1×1, 2→2×1, 4→2×2) and extends cleanly to any configured elite
        // view count up to 16 (4×4).
        int cols = Math.max(1, (int) Math.ceil(Math.sqrt(n)));
        int rows = (n + cols - 1) / cols;
        // `tag` reserves room for the caption ABOVE each board. It must clear not just
        // the label text but a freshly-dropped fruit still above the well (spawns at
        // CONTAINER_HEIGHT + 1 game-unit) — the vertical centering in BOARD_GH only
        // puts about half that unit's worth of pixels above the container itself, so a
        // small tag gap let a falling fruit visually graze the label text.
        float pad = GRID_CELL_PAD, tag = GRID_TAG_H, topMargin = 16f;
        float cw = r[2] / cols, chFull = r[3] / rows;
        float availW = cw - 2 * pad, availHFull = chFull - 2 * pad - tag;
        float sc = Math.min(availW / BOARD_GW, availHFull / BOARD_GH);
        float boardPxH = (float) PhysicsConfig.CONTAINER_HEIGHT * sc;
        float rowPxH = tag + 2 * pad + boardPxH;   // this row's actual rendered height, not chFull
        float regionTop = r[1] + r[3] - topMargin;
        float[][] out = new float[n][3];
        for (int i = 0; i < n; i++) {
            int cxIdx = i % cols, cyIdx = i / cols;
            float cellX = r[0] + cxIdx * cw;
            float rowTop = regionTop - cyIdx * rowPxH;   // row 0 sits at the TOP of the region
            float ox = cellX + cw / 2f - 5f * sc;        // centre the 10-wide well
            float oy = rowTop - tag - pad - boardPxH;    // floor y, right below this row's tag+pad
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
        else if (imitationDual()) runner.setHover(hoverGameX);
        // Merge feedback (particle bursts, score pops) only makes sense in full-board
        // space on the single primary board — tell the runner which orientation's
        // transform to use for it BEFORE stepping physics (where merges happen).
        if (views == 1) runner.setPopTransform(
                landscape ? BoardRenderer.OX_L : BoardRenderer.OX,
                landscape ? BoardRenderer.OY_L : BoardRenderer.OY,
                landscape ? BoardRenderer.SCALE_L : BoardRenderer.SCALE);
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
        if (slotsOpen) drawSlots();
    }

    // ---- multi-board grid ----

    private void renderGridShapes(ShapeRenderer s, int views) {
        GameState[] states = runner.multiStates();
        float[][] place = placements(views);
        boolean dual = imitationDual();
        // Same headroom placements() reserved for the tag above each cell (minus a little
        // slack for the label's own text height) — a fruit rendered any higher than this
        // would otherwise visually collide with the caption.
        float clipPx = GRID_TAG_H + GRID_CELL_PAD - 20f;
        for (int i = 0; i < views; i++) {
            if (i >= states.length || states[i] == null) continue;
            // Show the human drop-guide on the YOU board (index 0) only.
            if (dual && i == 0) board.setHover(hoverGameX, states[0].currentFruitTier());
            else                board.setHover(Float.NaN, null);
            board.useCustom(place[i][0], place[i][1], place[i][2]);
            board.drawBoard(s, states[i], game.settings, null, 1f, clipPx);
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
        // Landscape width was 470 — with the fuller per-technique stat lines added since
        // (throughput/elapsed/parallel-search detail etc.), several ran long enough to
        // visually run into the floating chart column at whatever Y they landed on.
        // Widened so the text column has real clearance before the chart's left edge
        // (still leaves a comfortable gap to the fixed single-board landscape position).
        if (landscape) return new float[]{12f, 72f, 560f, Theme.VH_L - 90f};
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

        // Long technique titles (e.g. "Population-Based Training") can reach far enough
        // right to collide with chart1's "score · N" caption, which sits on the same
        // row in portrait. Ellipsize rather than let them overlap.
        float titleMaxW = (chartSlots()[0][0] - 8f) - (px + 18f);
        Ui.text(game.batch, game.fontMed,   ellipsize(runner.title(), game.fontMed, titleMaxW), px + 18, py + ph - 22, Theme.TEXT);
        Ui.text(game.batch, game.fontSmall, runner.subtitle(), px + 18, py + ph - 56, Theme.ACCENT_BLUE);

        // In landscape the chart column floats beside (not below) the text column, at
        // fixed Y positions independent of how many stat lines precede them — long
        // free-text lines (technique explainers) must stop before that column starts,
        // or they visually collide with a chart's box/label. Portrait's charts sit
        // below all text, so no width constraint is needed there.
        float maxTextW = landscape ? (chartSlots()[0][0] - 16f) - (px + 18f) : pw - 36f;
        float minY = py + 8f; // never draw below the panel's own background

        float ly = py + ph - 86;
        ly = drawWrappedLines(runner.stats(), px + 18, ly, maxTextW, minY, Theme.TEXT_DIM);
        if (landscape) {   // extended stats fill the extra vertical room
            ly -= 8;
            drawWrappedLines(runner.extendedStats(), px + 18, ly, maxTextW, minY, Theme.TEXT_FAINT);
        }
        for (float[] c : chartSlots()) {
            String lbl = chartLabelFor((int) c[4]);
            if (lbl != null) Ui.text(game.batch, game.fontSmall, lbl, c[0] + 4, c[1] + c[3] + 16, Theme.TEXT_DIM);
        }
    }

    /** Draws each line at fontSmall, wrapping any that would exceed {@code maxW} at word
     *  boundaries (continuations indented to align under the label column), and stops
     *  once the cursor drops below {@code minY} so overlong content is silently clipped
     *  rather than bleeding into the board/control-bar area below the panel.
     *  Returns the cursor Y after the last line drawn. */
    private float drawWrappedLines(String[] lines, float x, float y, float maxW, float minY, Color color) {
        for (String line : lines) {
            for (String seg : wrapForWidth(line, maxW)) {
                if (y < minY) return y;
                Ui.text(game.batch, game.fontSmall, seg, x, y, color);
                y -= 24;
            }
        }
        return y;
    }

    private static final String WRAP_INDENT = "             "; // 13 spaces — matches the padded label-column width runners use for their stat strings

    /** Splits {@code line} into the fewest word-wrapped segments that each render no
     *  wider than {@code maxW} at fontSmall. Preserves the original text (and its
     *  hand-aligned label padding) verbatim when it already fits. */
    private java.util.List<String> wrapForWidth(String line, float maxW) {
        if (Ui.textWidth(game.fontSmall, line) <= maxW) return java.util.List.of(line);
        java.util.List<String> out = new java.util.ArrayList<>();
        String remaining = line;
        boolean first = true;
        while (!remaining.isEmpty()) {
            String prefix = first ? "" : WRAP_INDENT;
            int fitEnd = -1, searchFrom = 0;
            while (true) {
                int sp = remaining.indexOf(' ', searchFrom);
                String candidate = sp < 0 ? remaining : remaining.substring(0, sp);
                if (Ui.textWidth(game.fontSmall, prefix + candidate) > maxW) break;
                fitEnd = sp < 0 ? remaining.length() : sp;
                if (sp < 0) break;
                searchFrom = sp + 1;
            }
            if (fitEnd <= 0) fitEnd = remaining.length(); // single word wider than the column — take it whole
            out.add(prefix + remaining.substring(0, fitEnd).stripTrailing());
            remaining = remaining.substring(fitEnd).stripLeading();
            first = false;
        }
        return out;
    }

    /** Truncates {@code text} with a trailing ellipsis so it renders no wider than
     *  {@code maxW} at the given font; returns it unchanged if it already fits. */
    private String ellipsize(String text, BitmapFont font, float maxW) {
        if (Ui.textWidth(font, text) <= maxW) return text;
        String ell = "…";
        if (Ui.textWidth(font, ell) > maxW) return ell;
        int lo = 0, hi = text.length();
        String best = ell;
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            String candidate = text.substring(0, mid).stripTrailing() + ell;
            if (Ui.textWidth(font, candidate) <= maxW) { best = candidate; lo = mid; } else hi = mid - 1;
        }
        return best;
    }

    // ---- control bar ----

    private void drawControlBar(ShapeRenderer s) {
        Ui.button(s, backBtn,    Theme.PANEL_EDGE,  backBtn.contains(mx, my),    true);
        Ui.button(s, pauseBtn,   Theme.ACCENT_BLUE, pauseBtn.contains(mx, my),   true);
        Ui.button(s, slowBtn,    Theme.PANEL_EDGE,  slowBtn.contains(mx, my),    true);
        Ui.button(s, fastBtn,    Theme.PANEL_EDGE,  fastBtn.contains(mx, my),    true);
        Ui.button(s, swapBtn,    Theme.GOLD,        swapBtn.contains(mx, my),    true);
        if (slotsSupported()) Ui.button(s, slotsBtn, Theme.ACCENT_2, slotsBtn.contains(mx, my), true);
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
        if (slotsSupported()) Ui.textCenter(game.batch, game.fontSmall, "SLOTS",
                slotsBtn.x + slotsBtn.width / 2f, slotsBtn.y + 28, Theme.BG_BOTTOM);
        Ui.textCenter(game.batch, game.fontSmall, "RESTART",
                restartBtn.x + restartBtn.width / 2f, restartBtn.y + 28, Theme.TEXT);
        // Live caption sits in the clear gap above the control bar — portrait only
        // (landscape has no room below the well; its panel shows "doing now" instead).
        if (!landscape && viewCount() == 1 && runner.acceptsHumanInput())
            Ui.textCenter(game.batch, game.fontSmall, "click the well to drop",
                    Theme.VW / 2f, 84, Theme.TEXT_FAINT);
        else if (!landscape && viewCount() == 1)
            Ui.textCenter(game.batch, game.fontSmall, "live · " + cfg.technique.liveHint(),
                    Theme.VW / 2f, 84, Theme.ACCENT_BLUE);
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

        boolean evo = ghostApplicable();
        drawHotswapCycler(s, swapSpeedCtrl,     true);
        drawHotswapCycler(s, swapParaCtrl,      cfg.technique.parallel);
        drawHotswapCycler(s, swapParamCtrl,     paramApplicable());
        drawHotswapCycler(s, swapSimsCtrl,      evo);
        drawHotswapCycler(s, swapGhostCullCtrl, evo);
        drawHotswapCycler(s, swapEliteViewCtrl, evo);
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

        Ui.text(game.batch, game.font, "Parallelism",
                m0x + 24, swapParaCtrl.y + 25, cfg.technique.parallel ? Theme.TEXT : Theme.TEXT_FAINT);
        cyclerGlyphs(swapParaCtrl, cfg.technique.parallel ? cfg.parallelismLabel() : "n/a", cfg.technique.parallel);

        boolean pa = paramApplicable();
        Ui.text(game.batch, game.font, pa ? paramLabel() : "Parameter",
                m0x + 24, swapParamCtrl.y + 25, pa ? Theme.TEXT : Theme.TEXT_FAINT);
        cyclerGlyphs(swapParamCtrl, pa ? paramValue() : "n/a", pa);

        Ui.text(game.batch, game.font, "Sims/generation",
                m0x + 24, swapSimsCtrl.y + 25, evo ? Theme.TEXT : Theme.TEXT_FAINT);
        cyclerGlyphs(swapSimsCtrl, evo ? Integer.toString(cfg.simsPerGen()) : "n/a", evo);

        Ui.text(game.batch, game.font, "Ghost lineage",
                m0x + 24, swapGhostCullCtrl.y + 25, evo ? Theme.TEXT : Theme.TEXT_FAINT);
        cyclerGlyphs(swapGhostCullCtrl, evo ? cfg.ghostCullGens() + " gens" : "n/a", evo);

        Ui.text(game.batch, game.font, "Elite views",
                m0x + 24, swapEliteViewCtrl.y + 25, evo ? Theme.TEXT : Theme.TEXT_FAINT);
        cyclerGlyphs(swapEliteViewCtrl, evo ? cfg.eliteViewCount() + "x" : "n/a", evo);

        // Imitation's trainer deliberately persists across RESTART (so accumulated
        // learning isn't thrown away) — its knobs don't get a "rebuild" note.
        boolean restartRebuilds = cfg.technique.family != AiTechnique.Family.IMITATION
                && (evo || paramApplicable() || cfg.technique.parallel);
        if (restartRebuilds) Ui.textCenter(game.batch, game.fontSmall,
                "changes apply on RESTART", m0x + SWAP_MW / 2f, m0y + 96, Theme.TEXT_FAINT);

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

    // ---- save / load slots modal ----

    private void layoutSlotsModal() {
        float m0x = slotsModalX(), m0y = slotsModalY();
        for (int i = 0; i < ModelSlots.SLOT_COUNT; i++) {
            float rowY = m0y + SLOTS_MH - 100 - i * 70;
            slotSaveBtn[i].set(m0x + 330, rowY, 95, 40);
            slotLoadBtn[i].set(m0x + 435, rowY, 95, 40);
        }
        slotsCloseBtn.set(m0x + SLOTS_MW / 2f - 90, m0y + 20, 180, 44);
    }

    private void drawSlots() {
        layoutSlotsModal();
        float vw = landscape ? Theme.VW_L : Theme.VW;
        float vh = landscape ? Theme.VH_L : Theme.VH;
        float m0x = slotsModalX(), m0y = slotsModalY();

        ShapeRenderer s = game.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);
        s.setColor(0f, 0f, 0f, 0.86f);
        s.rect(0, 0, vw, vh);
        s.setColor(0.08f, 0.09f, 0.13f, 1f);
        Ui.fillRoundRect(s, m0x, m0y, SLOTS_MW, SLOTS_MH, 16);
        Ui.panel(s, m0x, m0y, SLOTS_MW, SLOTS_MH, 16, Theme.PANEL_DEEP, Theme.PANEL_EDGE);
        s.setColor(Theme.ACCENT_2);
        Ui.fillRoundRect(s, m0x, m0y + SLOTS_MH - 4f, SLOTS_MW, 4f, 3f);

        for (int i = 0; i < ModelSlots.SLOT_COUNT; i++) {
            s.setColor(Theme.PANEL);
            Ui.fillRoundRect(s, slotSaveBtn[i].x, slotSaveBtn[i].y, slotSaveBtn[i].width, slotSaveBtn[i].height, 8);
            Ui.fillRoundRect(s, slotLoadBtn[i].x, slotLoadBtn[i].y, slotLoadBtn[i].width, slotLoadBtn[i].height, 8);
            s.setColor(slotSaveBtn[i].contains(mx, my) ? Theme.ACCENT_2    : Theme.PANEL_EDGE);
            Ui.fillRoundRect(s, slotSaveBtn[i].x + 3, slotSaveBtn[i].y + 3, slotSaveBtn[i].width - 6, slotSaveBtn[i].height - 6, 6);
            s.setColor(slotLoadBtn[i].contains(mx, my) ? Theme.ACCENT_BLUE : Theme.PANEL_EDGE);
            Ui.fillRoundRect(s, slotLoadBtn[i].x + 3, slotLoadBtn[i].y + 3, slotLoadBtn[i].width - 6, slotLoadBtn[i].height - 6, 6);
        }
        boolean ch = slotsCloseBtn.contains(mx, my);
        s.setColor(0f, 0f, 0f, 0.35f);
        Ui.fillRoundRect(s, slotsCloseBtn.x + 3, slotsCloseBtn.y - 4, slotsCloseBtn.width, slotsCloseBtn.height, 14);
        s.setColor(ch ? 1f : 0.92f, ch ? 0.40f : 0.32f, ch ? 0.43f : 0.36f, 1f);
        Ui.fillRoundRect(s, slotsCloseBtn.x, slotsCloseBtn.y, slotsCloseBtn.width, slotsCloseBtn.height, 14);
        s.end();

        game.batch.begin();
        Ui.textCenter(game.batch, game.fontMed, "SAVE / LOAD PROGRESS",
                m0x + SLOTS_MW / 2f, m0y + SLOTS_MH - 32, Theme.TEXT);
        for (int i = 0; i < ModelSlots.SLOT_COUNT; i++) {
            ModelSlots.SlotInfo info = slotInfo(i + 1);
            float labelY = slotSaveBtn[i].y + slotSaveBtn[i].height / 2f + 7f;
            Ui.text(game.batch, game.font, "Slot " + (i + 1), m0x + 24, labelY, Theme.TEXT);
            String detail = info.present()
                    ? String.format("saved %tR  ·  score %.0f", new java.util.Date(info.savedAtMillis()), info.score())
                    : "empty";
            Ui.text(game.batch, game.fontSmall, detail, m0x + 24, labelY - 22f, Theme.TEXT_DIM);
            Ui.textCenter(game.batch, game.fontSmall, "SAVE",
                    slotSaveBtn[i].x + slotSaveBtn[i].width / 2f, slotSaveBtn[i].y + slotSaveBtn[i].height / 2f - 5f, Theme.TEXT);
            Ui.textCenter(game.batch, game.fontSmall, "LOAD",
                    slotLoadBtn[i].x + slotLoadBtn[i].width / 2f, slotLoadBtn[i].y + slotLoadBtn[i].height / 2f - 5f,
                    info.present() ? Theme.TEXT : Theme.TEXT_FAINT);
        }
        if (!slotsMessage.isEmpty()) Ui.textCenter(game.batch, game.fontSmall, slotsMessage,
                m0x + SLOTS_MW / 2f, m0y + 70, Theme.GOLD);
        Ui.textCenter(game.batch, game.fontSmall, "CLOSE",
                slotsCloseBtn.x + slotsCloseBtn.width / 2f, slotsCloseBtn.y + 25, Theme.TEXT);
        game.batch.end();
    }

    // ---- technique-launch modal (imitation "play first") ----

    private void drawModal() {
        float vw = landscape ? Theme.VW_L : Theme.VW;
        float vh = landscape ? Theme.VH_L : Theme.VH;
        ShapeRenderer s = game.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);
        float mw = 620f, mh = 420f;
        float mx0 = vw / 2f - mw / 2f, my0 = vh / 2f - mh / 2f;
        s.setColor(0f, 0f, 0f, 0.72f);
        s.rect(0, 0, vw, vh);
        s.setColor(0.08f, 0.09f, 0.13f, 1f);
        Ui.fillRoundRect(s, mx0, my0, mw, mh, 18);
        Ui.panel(s, mx0, my0, mw, mh, 18, Theme.PANEL, Theme.GOLD);
        s.end();
        game.batch.begin();
        Ui.textCenter(game.batch, game.fontMed, runner.modalTitle(), vw / 2f, my0 + mh - 48, Theme.GOLD);
        float y = my0 + mh - 110;
        for (String line : runner.modalBody()) {
            Ui.textCenter(game.batch, game.fontSmall, line, vw / 2f, y, Theme.TEXT);
            y -= 32;
        }
        game.batch.end();
    }

    @Override
    public void resize(int width, int height) {
        applyOrientation(width, height);
        viewport.update(width, height);
    }

    /**
     * LibGDX's {@code Game.setScreen()} only ever calls {@link #hide()} on the outgoing
     * screen — {@link #dispose()} normally never fires until the whole application
     * exits. Evolution and Imitation runners spawn a persistent background training
     * thread (plus, for evolution, a bounded eval thread pool sized up to every CPU
     * core); disposing only from {@code dispose()} meant hitting BACK never stopped
     * them — each one kept running and consuming CPU forever, and launching another
     * technique afterward just piled a second one on top, compounding into exactly the
     * "gets slower over time" symptom. Both hide() and dispose() call this (each
     * runner's dispose() is idempotent — safe either way, in case a screen is ever
     * disposed directly without a hide()).
     */
    @Override public void hide() {
        Gdx.input.setInputProcessor(null);
        runner.dispose();
    }
    @Override public void dispose() { runner.dispose(); }
}
