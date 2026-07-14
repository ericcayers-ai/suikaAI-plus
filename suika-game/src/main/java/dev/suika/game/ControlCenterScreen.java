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
import dev.suika.ai.AgentPlugin;
import dev.suika.ai.MctsAgent;
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

    // Landscape diagnostics panel: stats()/extendedStats() text can run to 20+ wrapped
    // display lines for verbose techniques (MCTS/AlphaZero explainers, CMA-ES/DAgger's
    // "reads" breakdown) — more than the fixed-height panel can show at once. Mouse
    // wheel over the panel scrolls it instead of the old behavior of silently cutting
    // text off mid-sentence at the panel's bottom edge.
    private float statsScroll = 0f;

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
    // Autosave (Settings → SAVES → Autosave): every N real-time minutes, silently save
    // the running technique's progress into slot 1 so a long unattended training run
    // survives a crash/close. Uses wall-clock delta (not the sped-up sim time) so "5 min"
    // means 5 real minutes regardless of the playback speed.
    private float autosaveTimer = 0f;
    private String autosaveNote = "";
    private float autosaveNoteTimer = 0f;

    // Stuck-run watchdog (Settings -> INPUT -> "Stuck-run watchdog"): if a single think
    // hangs past STUCK_THINK_MS, or the machine can't keep up (many-second frames piling
    // up), safely back out to the technique/ensemble menu instead of freezing there. The
    // outgoing screen's hide() disposes the runner, so its threads stop cleanly.
    private static final long STUCK_THINK_MS = 10_000;
    private float watchdogStall = 0f;   // accumulated seconds of severe frame stalls
    private boolean bailingOut = false;

    private boolean slotsOpen = false;
    private final Rectangle[] slotSaveBtn = { new Rectangle(), new Rectangle(), new Rectangle() };
    private final Rectangle[] slotLoadBtn = { new Rectangle(), new Rectangle(), new Rectangle() };
    private final Rectangle[] slotRevealBtn = { new Rectangle(), new Rectangle(), new Rectangle() };
    private final Rectangle   slotsCloseBtn = new Rectangle();
    private static final float SLOTS_MW = 640f, SLOTS_MH = 380f;
    private volatile String slotsMessage = "";

    // Hotswap (quick-settings) modal — mirrors the AI Playground drawer so every
    // per-technique launch knob (including evolution's sims/gen + ghost lineage) is
    // reachable mid-run, not just before LAUNCH.
    private boolean hotswapOpen = false;
    private final Rectangle swapPresetCtrl    = new Rectangle();
    private final Rectangle swapSpeedCtrl     = new Rectangle();
    private final Rectangle swapParaCtrl      = new Rectangle();
    private final Rectangle swapParamCtrl     = new Rectangle();
    private final Rectangle swapSimsCtrl      = new Rectangle();
    private final Rectangle swapGhostCullCtrl = new Rectangle();
    private final Rectangle swapEliteViewCtrl = new Rectangle();
    // TensorBoard row — only meaningful for techniques with a real training script (see
    // AiTechnique#supportsTensorboard()); a toggle for detailed logging plus a button
    // that starts/reuses a local TensorBoard server and opens it in the browser.
    private final Rectangle swapTbToggle  = new Rectangle();
    private final Rectangle swapTbOpenBtn = new Rectangle();
    private String tbMessage = "";
    private float  tbMessageTimer = 0f;
    // Drop columns: Auto live adjustment — see PlaygroundConfig#autoDrop. Applies to
    // every technique/ensemble (not gated behind evo/tb like the rows above it).
    private final Rectangle swapAutoDropToggle = new Rectangle();
    private final Rectangle swapCloseBtn      = new Rectangle();
    private static final float SWAP_MW = 480f, SWAP_MH = 668f;

    // Hotswap param tables (mirror AiPlaygroundScreen)
    private static final int[]    ROLLOUTS = {40, 80, 150, 300, 600, 1200, 2400};
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
            case DEEP_RL   -> new DqnRunner(game, cfg);
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
            @Override public boolean scrolled(float amountX, float amountY) {
                if (hotswapOpen || slotsOpen) return false;
                float[] p = panelBounds();
                if (mx < p[0] || mx > p[0] + p[2] || my < p[1] || my > p[1] + p[3]) return false;
                // Wheel-down reveals lower stat lines — see AiPlaygroundScreen's fix.
                statsScroll = MathUtils.clamp(statsScroll + amountY * 40f, 0f, maxStatsScroll());
                return true;
            }
            @Override public boolean keyDown(int k) {
                switch (k) {
                    case Input.Keys.ESCAPE -> {
                        if (hotswapOpen) { hotswapOpen = false; return true; }
                        if (slotsOpen) { slotsOpen = false; return true; }
                        game.setScreen(new AiPlaygroundScreen(game, cfg));
                    }
                    case Input.Keys.SPACE  -> runner.setPaused(!runner.paused());
                    case Input.Keys.R      -> runner.restart();
                    case Input.Keys.EQUALS, Input.Keys.PLUS -> changeSpeed(+1);
                    case Input.Keys.MINUS                   -> changeSpeed(-1);
                    // Down-arrow drop, pairing with the Left/Right arrow-key aim glide in
                    // render() below — Space is already the pause toggle here, so arrows-only
                    // (aim + drop) is the keyboard scheme for techniques that accept human
                    // input (currently Imitation's "YOU" board recording). Same guards as the
                    // mouse-click drop paths in handleClick.
                    case Input.Keys.DOWN -> {
                        if (!runner.paused() && runner.acceptsHumanInput()
                                && !hotswapOpen && !slotsOpen && chuteClear())
                            runner.humanDrop(hoverGameX);
                    }
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

    /** Window-widths-per-second glide speed for arrow-key aiming — matches SuikaScreen's
     *  ARROW_AIM_SPEED so the keyboard aim feels identical between the classic 2D game and
     *  a technique's human-input board (currently Imitation's "YOU" board). */
    private static final float ARROW_AIM_SPEED = (float)
            ((PhysicsConfig.DROP_X_MAX - PhysicsConfig.DROP_X_MIN) * 1.4);

    /** Left/Right arrows glide {@link #hoverGameX} exactly like mouse movement would —
     *  a keyboard-only alternative to {@link #updateMouse} for any technique that accepts
     *  human input (Down-arrow drops, see the keyDown handler). Polled every frame rather
     *  than tracked via press/release, since a human can hold the key across many frames. */
    private void updateHoverFromArrowKeys(float delta) {
        if (!runner.acceptsHumanInput() || runner.paused() || hotswapOpen || slotsOpen) return;
        boolean left  = Gdx.input.isKeyPressed(Input.Keys.LEFT);
        boolean right = Gdx.input.isKeyPressed(Input.Keys.RIGHT);
        if (!left && !right) return;
        float gx = hoverGameX + (right ? 1f : 0f) * ARROW_AIM_SPEED * delta
                              - (left  ? 1f : 0f) * ARROW_AIM_SPEED * delta;
        hoverGameX = clampDropHover(gx, runner.board());
    }

    /** Clamp a hovered game-x so the previewed/dropped fruit stays fully inside the walls. */
    private float clampDropHover(float gx, GameState gs) {
        float r = gs != null && gs.currentFruitTier() != null ? gs.currentFruitTier().radius : 0.5f;
        float lo = (float) PhysicsConfig.LEFT_WALL_X  + r;
        float hi = (float) PhysicsConfig.RIGHT_WALL_X - r;
        if (lo > hi) { lo = hi = (float) (PhysicsConfig.LEFT_WALL_X + PhysicsConfig.RIGHT_WALL_X) / 2f; }
        return MathUtils.clamp(gx, lo, hi);
    }

    /** Every technique can save into its 3 slots: Evolution/Imitation persist trained
     *  weights via {@link EvolutionRunner}/{@link ImitationRunner}; everything else is
     *  fully determined by technique + hyperparameters, saved/loaded via
     *  {@link AiSlotPlayer} — see its class doc. A saved slot (any technique) can then
     *  be picked to autoplay in RT Lab. */
    private boolean slotsSupported() { return true; }

    private void handleClick(float x, float y) {
        if (slotsOpen) {
            for (int i = 0; i < ModelSlots.SLOT_COUNT; i++) {
                if (slotSaveBtn[i].contains(x, y)) { doSaveSlot(i + 1); return; }
                if (slotLoadBtn[i].contains(x, y)) { doLoadSlot(i + 1); return; }
                if (slotRevealBtn[i].contains(x, y)) {
                    String folder = ModelSlots.revealSlotFolder(cfg.technique.id, i + 1);
                    slotsMessage = "Folder: " + folder;
                    return;
                }
            }
            if (slotsCloseBtn.contains(x, y)) { slotsOpen = false; return; }
            float m0x = slotsModalX(), m0y = slotsModalY();
            if (x < m0x || x > m0x + SLOTS_MW || y < m0y || y > m0y + SLOTS_MH) slotsOpen = false;
            return;
        }
        if (hotswapOpen) {
            boolean evo = ghostApplicable();
            if (swapPresetCtrl.contains(x, y)) {
                var presets = HardwarePresets.values();
                int d = x < swapPresetCtrl.x + swapPresetCtrl.width / 2f ? -1 : +1;
                presets[Math.floorMod(cfg.preset.ordinal() + d, presets.length)].applyTo(cfg);
                return;
            }
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
            boolean tb = cfg.technique.supportsTensorboard();
            boolean tbView = cfg.technique.tensorboardViewable();
            if (swapTbToggle.contains(x, y) && tb) { cfg.tensorboardDetailed = !cfg.tensorboardDetailed; return; }
            if (swapTbOpenBtn.contains(x, y) && tbView) {
                tbMessage = TensorboardLauncher.launch(cfg.technique.id);
                tbMessageTimer = 4f;
                return;
            }
            if (swapAutoDropToggle.contains(x, y)) { cfg.autoDrop = !cfg.autoDrop; return; }
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
            if (!runner.paused() && chuteClear()) runner.humanDrop(hoverGameX);
            return;
        }
        // Imitation dual view: only the YOU board (left) accepts drops.
        if (imitationDual() && runner.acceptsHumanInput()) {
            float[] p = placements(2)[0];
            float wallT = (float) PhysicsConfig.WALL_THICKNESS * p[2];
            float left  = p[0] - wallT;
            float right = p[0] + (float) PhysicsConfig.CONTAINER_WIDTH * p[2] + wallT;
            if (!runner.paused() && x >= left && x <= right && y > 70f && chuteClear())
                runner.humanDrop(hoverGameX);
        }
    }

    /** True when nothing in the primary board is still falling through the chute above the
     *  rim — checks each fruit's TOP surface (y + radius), not just its center, so a
     *  large tier can't still be poking into the spawn zone while reading as "clear". */
    private boolean chuteClear() {
        double thresh = PhysicsConfig.CONTAINER_HEIGHT - 1.0;
        for (var f : runner.board().fruits()) if (f.y() + f.radius() > thresh) return false;
        return true;
    }

    private void changeSpeed(int d) {
        cfg.speedIndex = MathUtils.clamp(cfg.speedIndex + d, 0, PlaygroundConfig.SPEEDS.length - 1);
        runner.setSpeed(cfg.speed());
    }

    /**
     * Returns true (and switches back to the technique/ensemble menu) when the run is
     * hung — a single think stuck past {@link #STUCK_THINK_MS}, or the machine buried
     * under multi-second frames for ~10 s total. No-op when the watchdog is disabled.
     */
    private boolean tickWatchdog(float rawDt) {
        if (bailingOut) return true;
        if (!game.settings.watchdogEnabled) { watchdogStall = 0f; return false; }

        boolean stuckThink = runner instanceof AgentRunner ar && ar.thinkingForMs() > STUCK_THINK_MS;
        // "Too much for the system": frames taking > 1.5 s mean the machine can't keep up;
        // accumulate that stall time (and bleed it off when frames are healthy) so a brief
        // hitch never trips it but a sustained overload does.
        if (rawDt > 1.5f) watchdogStall += rawDt;
        else watchdogStall = Math.max(0f, watchdogStall - rawDt);
        boolean overwhelmed = watchdogStall > 10f;

        if (stuckThink || overwhelmed) {
            bailingOut = true;
            AiPlaygroundScreen.pendingBackoutNote = stuckThink
                    ? "Run stalled 10s+ — backed out safely (lower the load or turn off Auto-drop)"
                    : "System overloaded — backed out safely to keep things responsive";
            game.setScreen(new AiPlaygroundScreen(game, cfg));
            return true;
        }
        return false;
    }

    private void tickAutosave(float delta) {
        if (autosaveNoteTimer > 0f) autosaveNoteTimer -= delta;
        int minutes = game.settings.autosaveMinutes();
        if (minutes <= 0 || !slotsSupported()) { autosaveTimer = 0f; return; }
        autosaveTimer += delta;
        if (autosaveTimer >= minutes * 60f) {
            autosaveTimer = 0f;
            doSaveSlot(1);                        // reuses the SAVES path — writes slot 1's folder
            autosaveNote = "Autosaved to slot 1";
            autosaveNoteTimer = 3f;
        }
    }

    // ---- view-count rule ----
    private int viewCount() {
        // While the ray-traced RT Lab window is open, both renderers share one GPU —
        // clamp the control center to a single board so the multi-board tiling
        // (evolution elites, self-play rivals, imitation dual) doesn't pile extra
        // per-frame physics + rendering on top of the RT workload.
        if (dev.suika.game.rtlab.RtLabLauncher.isRunning()) return 1;
        if (runner instanceof EvolutionRunner && !cfg.ghostView) return cfg.eliteViewCount();
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
        boolean ok;
        if (runner instanceof EvolutionRunner er) ok = er.saveToSlot(slot);
        else if (runner instanceof ImitationRunner ir) ok = ir.saveToSlot(slot);
        else if (runner instanceof DqnRunner dr) ok = dr.saveToSlot(slot);
        else {
            // Learning ensembles (adaptive committee, bandit) persist their live trust
            // statistics alongside the hyperparameters — real progress, not just knobs.
            java.util.Map<String, Double> learned =
                    runner instanceof AgentRunner ar && ar.agent() instanceof EnsembleAgents.HasLearnedState h
                            ? h.exportLearnedState() : java.util.Map.of();
            AiSlotPlayer.save(cfg.technique, slot, null, cfg, runner.board().score(), learned);
            ok = true;
        }
        slotsMessage = ok ? "Saved to slot " + slot : "Nothing to save yet";
    }

    private void doLoadSlot(int slot) {
        if (runner instanceof EvolutionRunner er) {
            slotsMessage = er.loadFromSlot(slot) ? "Loaded slot " + slot : "Slot " + slot + " is empty";
            return;
        }
        if (runner instanceof ImitationRunner ir) {
            slotsMessage = ir.loadFromSlot(slot) ? "Loaded slot " + slot : "Slot " + slot + " is empty";
            return;
        }
        if (runner instanceof DqnRunner dr) {
            slotsMessage = dr.loadFromSlot(slot) ? "Loaded slot " + slot : "Slot " + slot + " is empty";
            return;
        }
        // Config-only techniques: apply the saved hyperparameters live (mirrors the
        // hotswap modal) rather than rebuilding the runner — there's no weight array
        // to restore, just the knobs that fully determine this technique's behavior.
        ModelSlots.ConfigSlot saved = ModelSlots.loadConfig(cfg.technique.id, slot);
        if (saved == null) { slotsMessage = "Slot " + slot + " is empty"; return; }
        AiSlotPlayer.applyHyperparams(cfg, saved.params());
        // Restore a learning ensemble's saved trust statistics into the LIVE agent.
        if (runner instanceof AgentRunner ar && ar.agent() instanceof EnsembleAgents.HasLearnedState h) {
            h.importLearnedState(saved.params());
        }
        slotsMessage = "Loaded slot " + slot;
    }

    private ModelSlots.SlotInfo slotInfo(int slot) {
        if (runner instanceof EvolutionRunner er) return er.slotInfo(slot);
        if (runner instanceof ImitationRunner ir) return ir.slotInfo(slot);
        if (runner instanceof DqnRunner dr) return dr.slotInfo(slot);
        return ModelSlots.info(cfg.technique.id, slot);
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

    /** Test/QA hook: the primary board's live state, so the capture harness can inspect
     *  it for anomalies (out-of-bounds fruit, frozen score) across a config sweep. */
    GameState boardForCapture() { return runner.board(); }

    /** Test/QA hook: how many live boards this technique is currently showing (1 for a
     *  single board, up to 16 for an evolution elite grid) — lets the harness confirm
     *  the multi-board tiling actually populated at a given elite-view count. */
    int viewCountForCapture() { return viewCount(); }

    /** Test/QA hook: jump imitation runners straight to the TRAIN phase (see {@link ImitationRunner#forceTrainPhaseForCapture}). */
    void forceImitationTrainPhaseForCapture() {
        if (runner instanceof ImitationRunner ir) ir.forceTrainPhaseForCapture();
    }

    private void openHotswap() {
        hotswapOpen = true;
        float m0x = swapModalX(), m0y = swapModalY();
        swapPresetCtrl.set(   m0x + 230, m0y + SWAP_MH - 96,  220, 38);
        swapSpeedCtrl.set(    m0x + 230, m0y + SWAP_MH - 150, 220, 38);
        swapParaCtrl.set(     m0x + 230, m0y + SWAP_MH - 204, 220, 38);
        swapParamCtrl.set(    m0x + 230, m0y + SWAP_MH - 258, 220, 38);
        swapSimsCtrl.set(     m0x + 230, m0y + SWAP_MH - 312, 220, 38);
        swapGhostCullCtrl.set(m0x + 230, m0y + SWAP_MH - 366, 220, 38);
        swapEliteViewCtrl.set(m0x + 230, m0y + SWAP_MH - 420, 220, 38);
        swapTbToggle.set(     m0x + 230, m0y + SWAP_MH - 474, 70, 38);
        swapTbOpenBtn.set(    m0x + 308, m0y + SWAP_MH - 474, 142, 38);
        swapAutoDropToggle.set(m0x + 230, m0y + SWAP_MH - 528, 70, 38);
        swapCloseBtn.set( m0x + SWAP_MW / 2f - 90, m0y + 22, 180, 44);
    }

    private boolean ghostApplicable() {
        return cfg.technique.family == AiTechnique.Family.EVOLUTION;
    }

    // Ensembles built on MCTS search share its Rollouts knob; ENS_RTG_VERIFIED shares
    // Decision Transformer's Return knob.
    private static final java.util.Set<AiTechnique> ROLLOUT_PARAM_TECHS = java.util.Set.of(
            AiTechnique.MCTS, AiTechnique.ALPHAZERO, AiTechnique.ENS_MCTS_NET,
            AiTechnique.ENS_MCTS_TIEBREAK, AiTechnique.ENS_ADAPTIVE_VOTE, AiTechnique.ENS_BANDIT);

    private boolean paramApplicable() {
        if (ROLLOUT_PARAM_TECHS.contains(cfg.technique)) return true;
        return switch (cfg.technique) {
            case NEUROEVO, CMA_ES, PBT, DECISION_TRANSFORMER, DAGGER, BC, DQN, ENS_RTG_VERIFIED -> true;
            default -> false;
        };
    }
    private String paramLabel() {
        if (ROLLOUT_PARAM_TECHS.contains(cfg.technique)) return "Rollouts";
        return switch (cfg.technique) {
            case NEUROEVO, CMA_ES, PBT                   -> "Population";
            case DECISION_TRANSFORMER, ENS_RTG_VERIFIED  -> "Return";
            case DAGGER, BC, DQN                         -> "LR";
            default                                      -> "—";
        };
    }
    private String paramValue() {
        if (ROLLOUT_PARAM_TECHS.contains(cfg.technique)) return Integer.toString(cfg.rollouts);
        return switch (cfg.technique) {
            case NEUROEVO, CMA_ES, PBT                   -> Integer.toString(cfg.populationSize);
            case DECISION_TRANSFORMER, ENS_RTG_VERIFIED  -> Integer.toString((int) cfg.targetReturn);
            case DAGGER, BC, DQN                         -> String.format("%.0e", cfg.learningRate);
            default                                      -> "—";
        };
    }
    private void cycleParam(int d) {
        if (ROLLOUT_PARAM_TECHS.contains(cfg.technique)) { cfg.rollouts = cycleInt(ROLLOUTS, cfg.rollouts, d); return; }
        switch (cfg.technique) {
            case NEUROEVO, CMA_ES, PBT                   -> cfg.populationSize = cycleInt(POP, cfg.populationSize, d);
            case DECISION_TRANSFORMER, ENS_RTG_VERIFIED  -> cfg.targetReturn = cycleInt(RETURNS, (int) cfg.targetReturn, d);
            case DAGGER, BC, DQN                         -> cfg.learningRate = cycleDouble(LRS, cfg.learningRate, d);
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
        // Watchdog reads the RAW frame time (before the sim clamp below) so a machine that
        // can't keep up — multi-second frames — is actually detectable.
        if (tickWatchdog(Gdx.graphics.getDeltaTime())) return;
        delta = Math.min(delta, 0.05f);
        BoardRenderer.tickFlash(delta);
        tickAutosave(delta);
        if (tbMessageTimer > 0f) tbMessageTimer -= delta;
        int views = viewCount();
        updateHoverFromArrowKeys(delta);
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
        // Shrink caption + in-board tier-number text as the grid gets denser, so labels
        // don't visually overwhelm the (now much smaller) boards at high elite-view
        // counts — up to 16 views packs a 4x4 grid where full-size text would be wider
        // than some boards. Always reset in finally: fontSmall is shared with the
        // single-board and panel text drawn elsewhere this same frame.
        float scale = views <= 4 ? 1f : views <= 9 ? 0.8f : 0.6f;
        game.fontSmall.getData().setScale(scale);
        try {
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
        } finally {
            game.fontSmall.getData().setScale(1f);
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

        MctsAgent tree = mctsTreeSource();
        for (float[] c : chartSlots()) {
            int idx = (int) c[4];
            // Slot 3 is otherwise unused by any MCTS/ensemble technique (chart3() has
            // no meaningful third series for them) — repurpose it for a genuine
            // node-and-edge search-tree diagram instead of leaving it blank. Checked
            // BEFORE the tree branch: MuZero/Dreamer's JVM surrogate is itself an
            // MctsAgent stand-in (see mctsTreeSource()'s doc), so both checks would
            // otherwise be true for them — the perception panel is the more on-topic
            // pick for a "pixels" data-mode technique, and every other MCTS-based
            // technique still gets the tree.
            if (idx == 3 && perceptionApplicable() && chartFor(3) == null) {
                drawPerceptionPanel(s, c[0], c[1], c[2], c[3]);
            } else if (idx == 3 && tree != null && chartFor(3) == null) {
                drawMctsTree(s, c[0], c[1], c[2], c[3], tree);
            } else {
                chartFrame(s, c[0], c[1], c[2], c[3], chartFor(idx), chartColor(idx));
            }
        }
    }

    /** §11: MuZero/Dreamer are nominally "pixels" data-mode techniques (see
     *  {@link AiTechnique#dataMode}) — this panel makes that concrete instead of
     *  aspirational by rasterizing the SAME live board everything else on screen
     *  reads from ({@code runner.board()}) into a low-res sensor-style grid, the
     *  same "fruit as an intensity blob" idea {@code SoftwarePixelEncoder} (the
     *  offline Python-training env's real pixel encoder) uses — this is a live JVM
     *  re-derivation of that same look for the Control Center, not a shared
     *  dependency on the training-side encoder. */
    private boolean perceptionApplicable() {
        return cfg.technique == AiTechnique.MUZERO;
    }

    private static final int PERCEPTION_GRID = 18;

    private void drawPerceptionPanel(ShapeRenderer s, float x, float y, float w, float h) {
        s.setColor(0.03f, 0.05f, 0.04f, 1f); // near-black "sensor feed" backing, not the normal panel color
        Ui.fillRoundRect(s, x, y, w, h, 8f);

        GameState gs = runner.board();
        float gw = (float) PhysicsConfig.CONTAINER_WIDTH;
        float gh = (float) PhysicsConfig.CONTAINER_HEIGHT;
        float cellW = w / PERCEPTION_GRID, cellH = h / PERCEPTION_GRID;

        // Each fruit lights up the grid cells its circle actually overlaps (not just
        // its center cell) — small tiers would otherwise vanish at this resolution.
        float[] intensity = new float[PERCEPTION_GRID * PERCEPTION_GRID];
        for (var f : gs.fruits()) {
            float fx = (float) f.x() / gw, fy = 1f - (float) f.y() / gh; // 0..1, y flipped (grid row 0 = top)
            float fr = (float) f.radius() / gw;
            float tierLevel = f.tier().tier / 11f;
            int cxCell = (int) (fx * PERCEPTION_GRID), cyCell = (int) (fy * PERCEPTION_GRID);
            int rCells = Math.max(1, Math.round(fr * PERCEPTION_GRID));
            for (int gy = Math.max(0, cyCell - rCells); gy <= Math.min(PERCEPTION_GRID - 1, cyCell + rCells); gy++) {
                for (int gx = Math.max(0, cxCell - rCells); gx <= Math.min(PERCEPTION_GRID - 1, cxCell + rCells); gx++) {
                    float dx = gx - cxCell, dy = gy - cyCell;
                    if (dx * dx + dy * dy <= rCells * rCells + 0.5f) {
                        intensity[gy * PERCEPTION_GRID + gx] = Math.max(intensity[gy * PERCEPTION_GRID + gx], tierLevel);
                    }
                }
            }
        }
        for (int gy = 0; gy < PERCEPTION_GRID; gy++) {
            for (int gx = 0; gx < PERCEPTION_GRID; gx++) {
                float v = intensity[gy * PERCEPTION_GRID + gx];
                if (v <= 0f) continue;
                float b = 0.25f + 0.75f * v; // faint grid never fully vanishes, brightest tiers pop
                s.setColor(b * 0.55f, b, b * 0.65f, 1f); // monochrome-green "sensor" tint
                s.rect(x + gx * cellW + 1f, y + h - (gy + 1) * cellH + 1f, cellW - 2f, cellH - 2f);
            }
        }
    }

    /** The live search source for the tree diagram: the current agent itself if it's a
     *  plain {@link MctsAgent} (MCTS/AlphaZero), or the inner search of any ensemble
     *  built on one (see {@link EnsembleAgents.HasMctsCore}) — {@code null} otherwise. */
    private MctsAgent mctsTreeSource() {
        if (!(runner instanceof AgentRunner ar)) return null;
        AgentPlugin a = ar.agent();
        if (a instanceof MctsAgent m) return m;
        if (a instanceof EnsembleAgents.HasMctsCore h) return h.mctsCore();
        return null;
    }

    /** Draws the root plus its top-visited children as nodes with connecting edges
     *  (node radius + fill brightness scaled by visit share), and the SINGLE
     *  best-visited child's own top children nested one level further in — the
     *  "principal variation" a chess engine would show, kept to the one branch that
     *  actually matters so it stays legible in a small panel slot. */
    private void drawMctsTree(ShapeRenderer s, float x, float y, float w, float h, MctsAgent tree) {
        s.setColor(Theme.PANEL_DEEP);
        Ui.fillRoundRect(s, x, y, w, h, 8f);
        MctsAgent.TreeNodeView root = tree.lastTree();
        if (root == null || root.children().isEmpty()) return;

        float pad = 10f;
        float rootX = x + pad + 8f, midY = y + h / 2f;
        float col1X = x + w * 0.42f, col2X = x + w - pad - 8f;
        var kids = root.children();
        int n = kids.size();
        float rowH = (h - 2 * pad) / Math.max(1, n);
        int maxVisits = 0;
        for (var c : kids) maxVisits = Math.max(maxVisits, c.visits());

        s.setColor(Theme.TEXT_DIM.r, Theme.TEXT_DIM.g, Theme.TEXT_DIM.b, 0.5f);
        s.circle(rootX, midY, 5f, 12);

        var best = kids.get(0); // already sorted by visits in MctsAgent.snapshotNode
        for (int i = 0; i < n; i++) {
            var c = kids.get(i);
            float cy = y + pad + rowH * (i + 0.5f);
            float share = maxVisits > 0 ? c.visits() / (float) maxVisits : 0f;
            float r = 3f + 7f * share;
            s.setColor(Theme.ACCENT_BLUE.r, Theme.ACCENT_BLUE.g, Theme.ACCENT_BLUE.b, 0.25f + 0.5f * share);
            drawEdge(s, rootX, midY, col1X, cy);
            boolean isBest = c == best;
            Color nodeColor = isBest ? Theme.GOLD : Theme.ACCENT_BLUE;
            s.setColor(nodeColor.r, nodeColor.g, nodeColor.b, 0.4f + 0.55f * share);
            s.circle(col1X, cy, r, 14);
        }

        // One more level, only for the best branch's own children.
        var grandkids = best.children();
        if (!grandkids.isEmpty()) {
            float bestY = y + pad + rowH * (kids.indexOf(best) + 0.5f);
            int gn = grandkids.size();
            float gRowH = Math.min(rowH, (h - 2 * pad) / Math.max(1, gn));
            int gMax = 0; for (var g : grandkids) gMax = Math.max(gMax, g.visits());
            float startY = bestY - gRowH * (gn - 1) / 2f;
            for (int i = 0; i < gn; i++) {
                var g = grandkids.get(i);
                float gy = startY + gRowH * i;
                gy = Math.max(y + pad, Math.min(y + h - pad, gy));
                float share = gMax > 0 ? g.visits() / (float) gMax : 0f;
                float r = 2f + 4f * share;
                s.setColor(Theme.GOLD.r, Theme.GOLD.g, Theme.GOLD.b, 0.18f + 0.35f * share);
                drawEdge(s, col1X, bestY, col2X, gy);
                s.setColor(Theme.GOLD.r, Theme.GOLD.g, Theme.GOLD.b, 0.3f + 0.5f * share);
                s.circle(col2X, gy, r, 10);
            }
        }
    }

    /** A thin quad standing in for a line segment — {@link ShapeRenderer#line} ignores
     *  the current fill color set via {@code setColor} inconsistently across GL
     *  backends when mixed with filled circles in the same batch, so edges use the
     *  same filled-rect primitive as everything else in this UI for a reliably visible
     *  1.5px stroke regardless of the fill/line render-type context around it. */
    private void drawEdge(ShapeRenderer s, float x0, float y0, float x1, float y1) {
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-3f) return;
        float nx = -dy / len * 0.75f, ny = dx / len * 0.75f;
        float[] verts = { x0 + nx, y0 + ny, x1 + nx, y1 + ny, x1 - nx, y1 - ny, x0 - nx, y0 - ny };
        s.triangle(verts[0], verts[1], verts[2], verts[3], verts[4], verts[5]);
        s.triangle(verts[0], verts[1], verts[4], verts[5], verts[6], verts[7]);
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
        float maxScroll = maxStatsScroll();
        statsScroll = MathUtils.clamp(statsScroll, 0f, maxScroll);
        // Reserve a clear band at the bottom for the scroll hint so wrapped content never
        // renders on top of it (the text-occlusion bug from the screenshots).
        float hintReserve = maxScroll > 1f ? 24f : 0f;
        float minY = py + 8f + hintReserve; // content floor sits above the hint band
        float statsTop = py + ph - 86;      // just below the title/subtitle

        // Layout sign fix: the content flows DOWN from `ly`, and lines above `statsTop`
        // are skipped — so to reveal LATER lines, `ly` must move UP as statsScroll grows,
        // i.e. `statsTop + statsScroll`. The old `statsTop - statsScroll` moved content
        // down into a shrinking window and never revealed anything (control-panel scroll
        // "still wrong"). Wheel handler already adds amountY, so wheel-down reveals more.
        float ly = statsTop + statsScroll;
        ly = drawWrappedLines(runner.stats(), px + 18, ly, maxTextW, minY, statsTop, Theme.TEXT_DIM);
        if (landscape) {   // extended stats fill the extra vertical room
            ly -= 8;
            drawWrappedLines(runner.extendedStats(), px + 18, ly, maxTextW, minY, statsTop, Theme.TEXT_FAINT);
        }
        if (maxScroll > 1f) {
            // Plain ASCII only — an earlier arrow glyph (▸) silently rendered as a tofu
            // box on the bundled DroidSans font; "·" and "-" are already proven safe here.
            String hint = statsScroll < maxScroll - 1f ? "- scroll down for more -" : "- scroll up for less -";
            Ui.text(game.batch, game.fontSmall, hint, px + 18, py + 12f, Theme.GOLD);
        }
        MctsAgent treeForLabel = mctsTreeSource();
        // Chart labels can run past the panel's own right edge at larger UI-scale font
        // sizes (confirmed via the capture harness at 120% scale) — clamp to whatever
        // room is actually left to the screen edge, same fix as the title above.
        float vw = landscape ? Theme.VW_L : Theme.VW;
        for (float[] c : chartSlots()) {
            int idx = (int) c[4];
            String lbl = idx == 3 && perceptionApplicable() && chartFor(3) == null
                    ? "perception  ·  what the model sees"
                    : idx == 3 && treeForLabel != null && chartFor(3) == null
                    ? "search tree  ·  best-branch drill-down"
                    : chartLabelFor(idx);
            if (lbl != null) {
                float maxLblW = vw - 16f - (c[0] + 4);
                Ui.text(game.batch, game.fontSmall, ellipsize(lbl, game.fontSmall, maxLblW),
                        c[0] + 4, c[1] + c[3] + 16, Theme.TEXT_DIM);
            }
        }
    }

    /** How far {@link #statsScroll} can go before the LAST wrapped line has scrolled up
     *  to {@code minY} — i.e. total wrapped content height minus the visible window. */
    private float maxStatsScroll() {
        float[] p = panelBounds();
        float px = p[0], py = p[1], pw = p[2], ph = p[3];
        float maxTextW = landscape ? (chartSlots()[0][0] - 16f) - (px + 18f) : pw - 36f;
        int lines = 0;
        for (String line : runner.stats()) lines += wrapForWidth(line, maxTextW).size();
        if (landscape) for (String line : runner.extendedStats()) lines += wrapForWidth(line, maxTextW).size();
        float baseVisible = (py + ph - 86) - (py + 8f);
        float content = lines * 24f;
        if (content <= baseVisible) return 0f;
        // Once scrolling, a 24px hint band is reserved at the bottom (see drawPanelText),
        // so the reachable window is that much shorter.
        return content - (baseVisible - 24f);
    }

    /** Draws each line at fontSmall, wrapping any that would exceed {@code maxW} at word
     *  boundaries (continuations indented to align under the label column). Lines that
     *  scroll above {@code maxY} are skipped (not drawn, so nothing bleeds into the
     *  title/subtitle above); the loop stops entirely once the cursor drops below
     *  {@code minY}. Returns the cursor Y after the last line drawn. */
    private float drawWrappedLines(String[] lines, float x, float y, float maxW, float minY, float maxY, Color color) {
        for (String line : lines) {
            for (String seg : wrapForWidth(line, maxW)) {
                if (y < minY) return y;
                if (y <= maxY) Ui.text(game.batch, game.fontSmall, seg, x, y, color);
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
        if (slotsSupported()) Ui.textCenter(game.batch, game.fontSmall, "SAVES",
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
        // Transient autosave confirmation, top-centre so it's visible in any layout.
        if (autosaveNoteTimer > 0f) {
            float vw = landscape ? Theme.VW_L : Theme.VW;
            float vh = landscape ? Theme.VH_L : Theme.VH;
            Ui.textCenter(game.batch, game.fontSmall, autosaveNote, vw / 2f, vh - 14, Theme.GOLD);
        }
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
        drawHotswapCycler(s, swapPresetCtrl,    true);
        drawHotswapCycler(s, swapSpeedCtrl,     true);
        drawHotswapCycler(s, swapParaCtrl,      cfg.technique.parallel);
        drawHotswapCycler(s, swapParamCtrl,     paramApplicable());
        drawHotswapCycler(s, swapSimsCtrl,      evo);
        drawHotswapCycler(s, swapGhostCullCtrl, evo);
        drawHotswapCycler(s, swapEliteViewCtrl, evo);

        boolean tb = cfg.technique.supportsTensorboard();
        boolean tbView = cfg.technique.tensorboardViewable();
        s.setColor(tb ? Theme.PANEL : new Color(0.10f, 0.11f, 0.16f, 0.8f));
        Ui.fillRoundRect(s, swapTbToggle.x, swapTbToggle.y, swapTbToggle.width, swapTbToggle.height, 8);
        if (tb) Ui.toggle(s, swapTbToggle.x + 4, swapTbToggle.y + 4,
                swapTbToggle.width - 8, swapTbToggle.height - 8, cfg.tensorboardDetailed);
        s.setColor(tbView ? (swapTbOpenBtn.contains(mx, my) ? Theme.GOLD : Theme.PANEL_EDGE)
                      : new Color(0.10f, 0.11f, 0.16f, 0.8f));
        Ui.fillRoundRect(s, swapTbOpenBtn.x, swapTbOpenBtn.y, swapTbOpenBtn.width, swapTbOpenBtn.height, 8);

        s.setColor(Theme.PANEL);
        Ui.fillRoundRect(s, swapAutoDropToggle.x, swapAutoDropToggle.y,
                swapAutoDropToggle.width, swapAutoDropToggle.height, 8);
        Ui.toggle(s, swapAutoDropToggle.x + 4, swapAutoDropToggle.y + 4,
                swapAutoDropToggle.width - 8, swapAutoDropToggle.height - 8, cfg.autoDrop);

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

        Ui.text(game.batch, game.font, "Preset", m0x + 24, swapPresetCtrl.y + 25, Theme.TEXT);
        cyclerGlyphs(swapPresetCtrl, cfg.preset.label, true);

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

        Ui.text(game.batch, game.font, "TensorBoard", m0x + 24, swapTbToggle.y + 25,
                tbView ? Theme.TEXT : Theme.TEXT_FAINT);
        Ui.textCenter(game.batch, game.fontSmall, tbView ? "OPEN" : "n/a",
                swapTbOpenBtn.x + swapTbOpenBtn.width / 2f, swapTbOpenBtn.y + swapTbOpenBtn.height / 2f - 5f,
                tbView ? Theme.TEXT : Theme.TEXT_FAINT);

        Ui.text(game.batch, game.font, "Drop columns: Auto", m0x + 24, swapAutoDropToggle.y + 25, Theme.TEXT);

        // Imitation's trainer deliberately persists across RESTART (so accumulated
        // learning isn't thrown away) — its knobs don't get a "rebuild" note. The
        // TensorBoard launch status (if any) takes priority over that note — they're
        // both transient, single-line hints and rarely relevant at the same moment.
        if (tbMessageTimer > 0f) {
            Ui.textCenter(game.batch, game.fontSmall, tbMessage, m0x + SWAP_MW / 2f, m0y + 96, Theme.GOLD);
        } else {
            boolean restartRebuilds = cfg.technique.family != AiTechnique.Family.IMITATION
                    && (evo || paramApplicable() || cfg.technique.parallel);
            if (restartRebuilds) Ui.textCenter(game.batch, game.fontSmall,
                    "changes apply on RESTART", m0x + SWAP_MW / 2f, m0y + 96, Theme.TEXT_FAINT);
        }

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
            slotSaveBtn[i].set(m0x + 300, rowY, 88, 40);
            slotLoadBtn[i].set(m0x + 394, rowY, 88, 40);
            slotRevealBtn[i].set(m0x + 488, rowY, 128, 40);
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
            Ui.fillRoundRect(s, slotRevealBtn[i].x, slotRevealBtn[i].y, slotRevealBtn[i].width, slotRevealBtn[i].height, 8);
            s.setColor(slotSaveBtn[i].contains(mx, my) ? Theme.ACCENT_2    : Theme.PANEL_EDGE);
            Ui.fillRoundRect(s, slotSaveBtn[i].x + 3, slotSaveBtn[i].y + 3, slotSaveBtn[i].width - 6, slotSaveBtn[i].height - 6, 6);
            s.setColor(slotLoadBtn[i].contains(mx, my) ? Theme.ACCENT_BLUE : Theme.PANEL_EDGE);
            Ui.fillRoundRect(s, slotLoadBtn[i].x + 3, slotLoadBtn[i].y + 3, slotLoadBtn[i].width - 6, slotLoadBtn[i].height - 6, 6);
            s.setColor(slotRevealBtn[i].contains(mx, my) ? Theme.GOLD : Theme.PANEL_EDGE);
            Ui.fillRoundRect(s, slotRevealBtn[i].x + 3, slotRevealBtn[i].y + 3, slotRevealBtn[i].width - 6, slotRevealBtn[i].height - 6, 6);
        }
        boolean ch = slotsCloseBtn.contains(mx, my);
        s.setColor(0f, 0f, 0f, 0.35f);
        Ui.fillRoundRect(s, slotsCloseBtn.x + 3, slotsCloseBtn.y - 4, slotsCloseBtn.width, slotsCloseBtn.height, 14);
        s.setColor(ch ? 1f : 0.92f, ch ? 0.40f : 0.32f, ch ? 0.43f : 0.36f, 1f);
        Ui.fillRoundRect(s, slotsCloseBtn.x, slotsCloseBtn.y, slotsCloseBtn.width, slotsCloseBtn.height, 14);
        s.end();

        game.batch.begin();
        Ui.textCenter(game.batch, game.fontMed, "SAVES — SAVE / LOAD PROGRESS",
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
            Ui.textCenter(game.batch, game.fontSmall, "FOLDER",
                    slotRevealBtn[i].x + slotRevealBtn[i].width / 2f, slotRevealBtn[i].y + slotRevealBtn[i].height / 2f - 5f, Theme.TEXT);
        }
        // Saves land in per-slot folders (info / progress / model / .sav) — FOLDER opens
        // one in the OS file manager, then echoes its path here.
        String footer = slotsMessage.isEmpty()
                ? "text-file saves in ~/.suikai/saves/" + cfg.technique.id + "/slotN/  ·  FOLDER reveals one"
                : slotsMessage;
        Ui.textCenter(game.batch, game.fontSmall, footer, m0x + SLOTS_MW / 2f, m0y + 74,
                slotsMessage.isEmpty() ? Theme.TEXT_FAINT : Theme.GOLD);
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
