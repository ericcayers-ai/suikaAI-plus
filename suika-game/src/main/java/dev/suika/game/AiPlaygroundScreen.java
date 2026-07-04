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

/**
 * AI Playground — scrollable technique matrix with per-technique info cards,
 * a config drawer for the selected technique, and a LAUNCH button.
 *
 * <p>The drawer's top row is the hardware-aware quality preset (Slow = best quality /
 * Normal / High = fastest — see {@link HardwarePresets}); below it, only the knobs
 * the selected technique actually reads are enabled, including the per-ensemble
 * customization (donor net, blend weight, tie threshold, UCB c, adapt rate) and the
 * evolution selection-math controls.
 */
public final class AiPlaygroundScreen extends ScreenAdapter {

    private final SuikaGame game;
    private final PlaygroundConfig cfg;

    private final OrthographicCamera camera = new OrthographicCamera();
    private final FitViewport viewport;
    private final Vector3 touch = new Vector3();
    private float mx, my;

    // Ensembles get their own sorted (best -> worst, by AiTechnique#strength),
    // collapsible section at the top of the list; the rest of the matrix keeps its
    // curated order.
    private final AiTechnique[] ensembleTechs;
    private final AiTechnique[] otherTechs;
    private boolean ensemblesExpanded = false;
    private float scroll = 0f;

    // Layout constants
    private static final float CARD_H   = 60f;
    private static final float LIST_TOP = Theme.VH - 158f;
    private static final float LIST_BOT = 470f;
    private static final float CARD_X   = 36f;
    private static final float CARD_W   = Theme.VW - 72f;

    // "i" icon — fixed right column inside each card
    private static final float INFO_R  = 11f;
    private static final float INFO_CX = CARD_X + CARD_W - INFO_R - 12f;

    // Drawer controls — 11 stacked rows; rows are enabled per technique ("n/a"
    // elsewhere), with rows 5-10 context-switching between evolution's selection-math
    // knobs and the ensembles' customization knobs.
    private static final float CTRL_X = 420, CTRL_W = 260, CTRL_H = 24, CTRL_STEP = 30;
    private final Rectangle presetCtrl    = row(0);
    private final Rectangle speedCtrl     = row(1);
    private final Rectangle paraCtrl      = row(2);
    private final Rectangle paramCtrl     = row(3);
    private final Rectangle ctx1Ctrl      = row(4);
    private final Rectangle ctx2Ctrl      = row(5);
    private final Rectangle ctx3Ctrl      = row(6);
    private final Rectangle simsCtrl      = row(7);
    private final Rectangle eliteViewCtrl = row(8);
    private final Rectangle ghostCullCtrl = row(9);
    private final Rectangle ghostCtrl     = row(10);
    private final Rectangle backBtn       = new Rectangle(36, 16, 300, 64);
    private final Rectangle launchBtn     = new Rectangle(Theme.VW - 336, 16, 300, 64);

    private static Rectangle row(int i) {
        return new Rectangle(CTRL_X, 386 - i * CTRL_STEP, CTRL_W, CTRL_H);
    }

    // Infocard modal (null = closed). Sized to fit the explainer, ensemble members, the
    // ordered per-setting guide, and the attribute bars without crowding.
    private static final float INFO_MW = 600f, INFO_MH = 640f;
    private AiTechnique infocardTech = null;

    /** Test/QA hook: open the info modal for a technique (used by the capture harness). */
    void openInfocardForCapture(AiTechnique t) { this.infocardTech = t; }

    /** Test/QA hook: expand/collapse the ensemble dropdown (used by the capture harness). */
    void setEnsemblesExpandedForCapture(boolean expanded) { this.ensemblesExpanded = expanded; }

    private static final int[]    ROLLOUTS = {40, 80, 150, 300, 600, 1200, 2400};
    private static final int[]    POP      = {16, 24, 40, 64, 128, 256, 512, 1000};
    private static final int[]    RETURNS  = {1000, 2000, 4000};
    private static final double[] LRS      = {1e-3, 3e-3, 1e-2};

    public AiPlaygroundScreen(SuikaGame game) { this(game, null); }

    public AiPlaygroundScreen(SuikaGame game, PlaygroundConfig existing) {
        this.game = game;
        this.cfg  = existing != null ? existing : new PlaygroundConfig();
        if (existing == null) cfg.selectDefaultsFor(AiTechnique.MCTS);
        cfg.actionBins = game.settings.actionBins();
        viewport = new FitViewport(Theme.VW, Theme.VH, camera);
        camera.position.set(Theme.VW / 2f, Theme.VH / 2f, 0f);
        camera.update();

        java.util.List<AiTechnique> ens = new java.util.ArrayList<>();
        java.util.List<AiTechnique> other = new java.util.ArrayList<>();
        for (AiTechnique t : AiTechnique.values()) (t.isEnsemble() ? ens : other).add(t);
        ens.sort((a, b) -> b.strength - a.strength);
        ensembleTechs = ens.toArray(new AiTechnique[0]);
        otherTechs = other.toArray(new AiTechnique[0]);
    }

    /** Row 0 is the collapsible "ENSEMBLES" header; rows after it are the sorted
     *  ensembles (only while expanded), then every other technique in curated order. */
    private int rowCount() {
        return 1 + (ensemblesExpanded ? ensembleTechs.length : 0) + otherTechs.length;
    }

    /** The technique shown at a given row, or {@code null} for the header row (0). */
    private AiTechnique rowTech(int row) {
        if (row == 0) return null;
        row--;
        if (ensemblesExpanded) {
            if (row < ensembleTechs.length) return ensembleTechs[row];
            row -= ensembleTechs.length;
        }
        return row < otherTechs.length ? otherTechs[row] : null;
    }

    @Override
    public void show() {
        Gdx.input.setInputProcessor(new InputAdapter() {
            @Override public boolean touchDown(int sx, int sy, int p, int b) {
                camera.unproject(touch.set(sx, sy, 0),
                        viewport.getScreenX(), viewport.getScreenY(),
                        viewport.getScreenWidth(), viewport.getScreenHeight());
                handleClick(touch.x, touch.y);
                return true;
            }
            @Override public boolean mouseMoved(int sx, int sy) {
                camera.unproject(touch.set(sx, sy, 0),
                        viewport.getScreenX(), viewport.getScreenY(),
                        viewport.getScreenWidth(), viewport.getScreenHeight());
                mx = touch.x; my = touch.y;
                return false;
            }
            @Override public boolean scrolled(float ax, float ay) {
                // Wheel-down (ay > 0) reveals content further down the list. Increasing
                // `scroll` lifts lower cards up into the visible band (see cardTop()), so
                // this ADDS ay — the previous negation scrolled the wrong way (reported
                // by the user, confirmed across every scrollable view; fixed app-wide).
                if (infocardTech == null)
                    scroll = MathUtils.clamp(scroll + ay * 46f, 0f, maxScroll());
                return true;
            }
            @Override public boolean keyDown(int k) {
                if (k == Input.Keys.ESCAPE) {
                    if (infocardTech != null) { infocardTech = null; return true; }
                    game.setScreen(new MainMenuScreen(game));
                    return true;
                }
                return false;
            }
        });
    }

    private float maxScroll() {
        float band = LIST_TOP - LIST_BOT;
        return Math.max(0f, rowCount() * CARD_H - band);
    }

    private float cardTop(int i) { return LIST_TOP + scroll - i * CARD_H; }

    private float infoCY(int i) { return cardTop(i) - CARD_H / 2f + 2f; }

    private boolean hitInfoIcon(int i, float x, float y) {
        float dx = x - INFO_CX, dy = y - infoCY(i);
        return dx * dx + dy * dy <= (INFO_R + 6f) * (INFO_R + 6f);
    }

    /** A row is clickable only while its card centre is inside the visible list band —
     *  the same rule the text pass uses to decide whether to draw its label. Clicking a
     *  card that's mostly masked behind the header/drawer used to still select it
     *  (invisible selection = the "scrolled list clicks the wrong card" bug). */
    private boolean rowClickable(int i) {
        float cy = cardTop(i) - CARD_H / 2f;
        return cy > LIST_BOT && cardTop(i) - CARD_H <= LIST_TOP;
    }

    private void handleClick(float x, float y) {
        if (infocardTech != null) { infocardTech = null; return; }

        if (backBtn.contains(x, y))   { game.setScreen(new MainMenuScreen(game)); return; }
        if (launchBtn.contains(x, y)) {
            // The control center's multi-board / stats layout reads far better in
            // landscape, so launching from here defaults the window to it — a portrait
            // window the player deliberately narrowed is left alone.
            if (Gdx.graphics.getWidth() <= Gdx.graphics.getHeight() * 1.3f) goLandscape();
            game.setScreen(new ControlCenterScreen(game, cfg));
            return;
        }
        if (presetCtrl.contains(x, y)) {
            var presets = HardwarePresets.values();
            int idx = Math.floorMod(cfg.preset.ordinal() + dir(x, presetCtrl), presets.length);
            presets[idx].applyTo(cfg);
            return;
        }
        if (speedCtrl.contains(x, y)) {
            cfg.speedIndex = wrap(cfg.speedIndex + dir(x, speedCtrl), PlaygroundConfig.SPEEDS.length); return;
        }
        if (paraCtrl.contains(x, y) && cfg.technique.parallel) {
            int cores = Runtime.getRuntime().availableProcessors();
            cfg.parallelism = MathUtils.clamp(cfg.parallelism + dir(x, paraCtrl), 0, cores); return;
        }
        if (paramCtrl.contains(x, y) && paramApplicable()) { cycleParam(dir(x, paramCtrl)); return; }
        if (ctx1Ctrl.contains(x, y) && ctx1Applicable()) { cycleCtx1(dir(x, ctx1Ctrl)); return; }
        if (ctx2Ctrl.contains(x, y) && ctx2Applicable()) { cycleCtx2(dir(x, ctx2Ctrl)); return; }
        if (ctx3Ctrl.contains(x, y) && ctx3Applicable()) { cycleCtx3(dir(x, ctx3Ctrl)); return; }
        boolean evo = evolutionApplicable();
        if (simsCtrl.contains(x, y) && evo) {
            cfg.simsPerGenIndex = wrap(cfg.simsPerGenIndex + dir(x, simsCtrl), PlaygroundConfig.SIMS_PER_GEN_OPTIONS.length);
            return;
        }
        if (eliteViewCtrl.contains(x, y) && evo) {
            cfg.eliteViewIndex = wrap(cfg.eliteViewIndex + dir(x, eliteViewCtrl), PlaygroundConfig.ELITE_VIEW_OPTIONS.length);
            return;
        }
        if (ghostCullCtrl.contains(x, y) && evo) {
            cfg.ghostCullIndex = wrap(cfg.ghostCullIndex + dir(x, ghostCullCtrl), PlaygroundConfig.GHOST_CULL_OPTIONS.length);
            return;
        }
        if (ghostCtrl.contains(x, y) && evo) { cfg.ghostView = !cfg.ghostView; return; }

        for (int i = 0; i < rowCount(); i++) {
            if (!rowClickable(i)) continue;
            float top = cardTop(i);
            boolean inCard = x >= CARD_X && x <= CARD_X + CARD_W && y <= top && y >= top - CARD_H + 6;
            AiTechnique t = rowTech(i);
            if (t == null) {
                // Header row: toggle only when actually hit — clicks that miss it must
                // keep falling through to the cards BELOW it. (An unconditional return
                // here used to swallow every selection click while the header was
                // visible — the "ensembles selection" bug.)
                if (inCard) {
                    ensemblesExpanded = !ensemblesExpanded;
                    scroll = MathUtils.clamp(scroll, 0f, maxScroll());
                    return;
                }
                continue;
            }
            if (hitInfoIcon(i, x, y)) { infocardTech = t; return; }
            if (inCard) { cfg.selectDefaultsFor(t); return; }
        }
    }

    private int dir(float x, Rectangle r) { return x < r.x + r.width / 2f ? -1 : +1; }
    private static int wrap(int i, int n)  { return Math.floorMod(i, n); }

    /** Resizes the window to a landscape aspect, capped to fit the current display —
     *  mirrors {@code DesktopLauncher}'s own portrait sizing logic, just widthwise. */
    private static void goLandscape() {
        var dm = com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration.getDisplayMode();
        int winW = Math.min(1600, (int) (dm.width * 0.88f));
        int winH = (int) (winW * 720.0 / 1280.0);
        Gdx.graphics.setWindowedMode(winW, winH);
    }

    private boolean evolutionApplicable() {
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
            case NEUROEVO, CMA_ES, DECISION_TRANSFORMER, DAGGER, ENS_RTG_VERIFIED -> true;
            default -> false;
        };
    }
    private String paramLabel() {
        if (ROLLOUT_PARAM_TECHS.contains(cfg.technique)) return "Rollouts";
        return switch (cfg.technique) {
            case NEUROEVO, CMA_ES                         -> "Population";
            case DECISION_TRANSFORMER, ENS_RTG_VERIFIED   -> "Target return";
            case DAGGER                                   -> "Learning rate";
            default                                       -> "—";
        };
    }
    private String paramValue() {
        if (ROLLOUT_PARAM_TECHS.contains(cfg.technique)) return Integer.toString(cfg.rollouts);
        return switch (cfg.technique) {
            case NEUROEVO, CMA_ES                         -> Integer.toString(cfg.populationSize);
            case DECISION_TRANSFORMER, ENS_RTG_VERIFIED   -> Integer.toString((int) cfg.targetReturn);
            case DAGGER                                   -> String.format("%.0e", cfg.learningRate);
            default                                       -> "—";
        };
    }
    private void cycleParam(int d) {
        if (ROLLOUT_PARAM_TECHS.contains(cfg.technique)) { cfg.rollouts = cycleInt(ROLLOUTS, cfg.rollouts, d); return; }
        switch (cfg.technique) {
            case NEUROEVO, CMA_ES                         -> cfg.populationSize = cycleInt(POP, cfg.populationSize, d);
            case DECISION_TRANSFORMER, ENS_RTG_VERIFIED   -> cfg.targetReturn = cycleInt(RETURNS, (int) cfg.targetReturn, d);
            case DAGGER                                   -> cfg.learningRate = cycleDouble(LRS, cfg.learningRate, d);
            default -> { }
        }
    }
    private int cycleInt(int[] opts, int cur, int d) {
        int idx = 0; for (int i = 0; i < opts.length; i++) if (opts[i] == cur) idx = i;
        return opts[wrap(idx + d, opts.length)];
    }
    private double cycleDouble(double[] opts, double cur, int d) {
        int idx = 0; for (int i = 0; i < opts.length; i++) if (Math.abs(opts[i] - cur) < 1e-9) idx = i;
        return opts[wrap(idx + d, opts.length)];
    }

    // ---- context rows 5-7: evolution selection math OR ensemble customization ----

    private boolean ctx1Applicable() {
        if (cfg.technique == AiTechnique.NEUROEVO) return true;                 // Selection
        return switch (cfg.technique) {
            case ENS_MCTS_NET, ENS_MCTS_TIEBREAK, ENS_BANDIT, ENS_ADAPTIVE_VOTE -> true;
            default -> false;
        };
    }
    private String ctx1Label() {
        if (cfg.technique == AiTechnique.NEUROEVO) return "Selection";
        return switch (cfg.technique) {
            case ENS_MCTS_NET      -> "Donor net";
            case ENS_MCTS_TIEBREAK -> "Tie threshold";
            case ENS_BANDIT        -> "Explore (UCB c)";
            case ENS_ADAPTIVE_VOTE -> "Adapt rate";
            default                -> "—";
        };
    }
    private String ctx1Value() {
        if (cfg.technique == AiTechnique.NEUROEVO) return cfg.selection().label;
        return switch (cfg.technique) {
            case ENS_MCTS_NET      -> cfg.ensembleDonor().display
                    + (EnsembleAgents.donorTrained(cfg.ensembleDonor(), cfg.ensembleDonorSlot) ? " (trained)" : " (untrained)");
            case ENS_MCTS_TIEBREAK -> Math.round(cfg.ensembleTieThreshold() * 100) + "%";
            case ENS_BANDIT        -> String.format("%.1f", cfg.ensembleUcbC());
            case ENS_ADAPTIVE_VOTE -> String.format("%.2f", cfg.ensembleAdaptLr());
            default                -> "—";
        };
    }
    private void cycleCtx1(int d) {
        switch (cfg.technique) {
            case NEUROEVO          -> cfg.selectionIndex = wrap(cfg.selectionIndex + d,
                    dev.suika.ai.GeneticTrainer.Selection.values().length);
            case ENS_MCTS_NET      -> cfg.ensembleDonorIndex = wrap(cfg.ensembleDonorIndex + d, PlaygroundConfig.ENSEMBLE_DONORS.length);
            case ENS_MCTS_TIEBREAK -> cfg.tieThresholdIndex = wrap(cfg.tieThresholdIndex + d, PlaygroundConfig.TIE_THRESHOLD_OPTIONS.length);
            case ENS_BANDIT        -> cfg.ucbCIndex = wrap(cfg.ucbCIndex + d, PlaygroundConfig.UCB_C_OPTIONS.length);
            case ENS_ADAPTIVE_VOTE -> cfg.adaptLrIndex = wrap(cfg.adaptLrIndex + d, PlaygroundConfig.ADAPT_LR_OPTIONS.length);
            default -> { }
        }
    }

    private boolean ctx2Applicable() {
        return cfg.technique == AiTechnique.NEUROEVO || cfg.technique == AiTechnique.ENS_MCTS_NET;
    }
    private String ctx2Label() {
        return cfg.technique == AiTechnique.ENS_MCTS_NET ? "Net weight" : "Mutation σ";
    }
    private String ctx2Value() {
        if (cfg.technique == AiTechnique.ENS_MCTS_NET)
            return Math.round(cfg.ensembleNetWeight() * 100) + "%";
        return String.format("%.2f", PlaygroundConfig.MUTATION_SIGMA_OPTIONS[cfg.mutationSigmaIndex]);
    }
    private void cycleCtx2(int d) {
        if (cfg.technique == AiTechnique.ENS_MCTS_NET) {
            cfg.netWeightIndex = wrap(cfg.netWeightIndex + d, PlaygroundConfig.NET_WEIGHT_OPTIONS.length);
        } else {
            cfg.mutationSigmaIndex = wrap(cfg.mutationSigmaIndex + d, PlaygroundConfig.MUTATION_SIGMA_OPTIONS.length);
            cfg.mutationSigma = PlaygroundConfig.MUTATION_SIGMA_OPTIONS[cfg.mutationSigmaIndex];
        }
    }

    /** NEUROEVO: breeding combo (crossover × σ-anneal). ENS_MCTS_NET: donor save slot. */
    private boolean ctx3Applicable() {
        return cfg.technique == AiTechnique.NEUROEVO || cfg.technique == AiTechnique.ENS_MCTS_NET;
    }
    private String ctx3Label() {
        return cfg.technique == AiTechnique.ENS_MCTS_NET ? "Donor slot" : "Breeding";
    }
    private String ctx3Value() {
        if (cfg.technique == AiTechnique.ENS_MCTS_NET) {
            String base = cfg.ensembleDonorSlotLabel();
            if (cfg.ensembleDonorSlot >= 1)
                return base + (EnsembleAgents.donorTrained(cfg.ensembleDonor(), cfg.ensembleDonorSlot) ? " (saved)" : " (empty)");
            return base;
        }
        if (cfg.crossover && cfg.sigmaAnneal) return "Crossover + anneal";
        if (cfg.crossover)   return "Crossover";
        if (cfg.sigmaAnneal) return "Mutation + anneal";
        return "Mutation only";
    }
    private void cycleCtx3(int d) {
        if (cfg.technique == AiTechnique.ENS_MCTS_NET) {
            cfg.ensembleDonorSlot = wrap(cfg.ensembleDonorSlot + d, ModelSlots.SLOT_COUNT + 1); // 0=Auto,1..3
            return;
        }
        int cur = (cfg.crossover ? 1 : 0) | (cfg.sigmaAnneal ? 2 : 0);
        int next = wrap(cur + d, 4);
        cfg.crossover   = (next & 1) != 0;
        cfg.sigmaAnneal = (next & 2) != 0;
    }

    private Color familyColor(AiTechnique t) {
        return switch (t.family) {
            case PLANNING  -> t.isEnsemble() ? Theme.GOLD : Theme.ACCENT_BLUE;
            case EVOLUTION -> Theme.ACCENT_2;
            case IMITATION -> Theme.GOLD;
            case PYTHON    -> Theme.ACCENT;
        };
    }

    // -------------------------------------------------------------------------
    // Render
    // -------------------------------------------------------------------------

    @Override
    public void render(float delta) {
        Gdx.gl.glClearColor(0.05f, 0.06f, 0.10f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        viewport.apply();
        game.shapes.setProjectionMatrix(camera.combined);
        game.batch.setProjectionMatrix(camera.combined);

        ShapeRenderer s = game.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);
        s.rect(0, 0, Theme.VW, Theme.VH, Theme.BG_BOTTOM, Theme.BG_BOTTOM, Theme.BG_TOP, Theme.BG_TOP);

        // Card shapes + "i" icons
        for (int i = 0; i < rowCount(); i++) {
            float top = cardTop(i);
            if (top < LIST_BOT || top - CARD_H > LIST_TOP) continue;
            AiTechnique t = rowTech(i);
            boolean hov = rowClickable(i)
                       && mx >= CARD_X && mx <= CARD_X + CARD_W
                       && my <= top    && my >= top - CARD_H + 6;
            if (t == null) { // collapsible "ENSEMBLES" header row
                s.setColor(hov ? Theme.GOLD : Theme.PANEL_EDGE);
                Ui.fillRoundRect(s, CARD_X, top - CARD_H + 6, CARD_W, CARD_H - 8, 10);
                continue;
            }
            boolean sel = t == cfg.technique;
            if (sel) {
                s.setColor(familyColor(t));
                Ui.fillRoundRect(s, CARD_X - 3, top - CARD_H + 3, CARD_W + 6, CARD_H, 12);
            }
            s.setColor(hov ? Theme.PANEL_EDGE : Theme.PANEL);
            Ui.fillRoundRect(s, CARD_X, top - CARD_H + 6, CARD_W, CARD_H - 8, 10);
            s.setColor(familyColor(t));
            s.circle(CARD_X + 24, top - CARD_H / 2f + 2, 9, 16);
            // "i" icon
            boolean iHov = hitInfoIcon(i, mx, my);
            s.setColor(iHov ? familyColor(t) : Theme.PANEL_EDGE);
            s.circle(INFO_CX, infoCY(i), INFO_R, 18);
        }

        // Opaque masks — clip cards that bleed into header / drawer
        s.setColor(Theme.BG_TOP);
        s.rect(0, Theme.VH - 150, Theme.VW, 150);
        s.setColor(Theme.PANEL_DEEP);
        Ui.fillRoundRect(s, 0, 0, Theme.VW, LIST_BOT, 0);

        // Drawer controls
        boolean evo = evolutionApplicable();
        drawCycler(s, presetCtrl, true);
        drawCycler(s, speedCtrl, true);
        drawCycler(s, paraCtrl,  cfg.technique.parallel);
        drawCycler(s, paramCtrl, paramApplicable());
        drawCycler(s, ctx1Ctrl, ctx1Applicable());
        drawCycler(s, ctx2Ctrl, ctx2Applicable());
        drawCycler(s, ctx3Ctrl, ctx3Applicable());
        drawCycler(s, simsCtrl,      evo);
        drawCycler(s, eliteViewCtrl, evo);
        drawCycler(s, ghostCullCtrl, evo);
        s.setColor(evo ? Theme.PANEL_DEEP : new Color(0.10f, 0.11f, 0.16f, 0.6f));
        Ui.fillRoundRect(s, ghostCtrl.x, ghostCtrl.y, ghostCtrl.width, ghostCtrl.height, 8);
        if (evo) Ui.toggle(s,
                ghostCtrl.x + ghostCtrl.width - 64f, ghostCtrl.y + 3f,
                58f, ghostCtrl.height - 6f, cfg.ghostView);
        Ui.button(s, backBtn,   Theme.PANEL_EDGE, backBtn.contains(mx, my),   true);
        Ui.button(s, launchBtn, Theme.ACCENT_2,   launchBtn.contains(mx, my), true);

        // Infocard modal overlay — drawn opaque so the busy list behind it is fully hidden.
        if (infocardTech != null) {
            s.setColor(0.03f, 0.04f, 0.07f, 0.94f);
            s.rect(0, 0, Theme.VW, Theme.VH);
            float mW = INFO_MW, mH = INFO_MH;
            float mX = (Theme.VW - mW) / 2f, mY = (Theme.VH - mH) / 2f;
            s.setColor(0.08f, 0.09f, 0.13f, 1f);
            Ui.fillRoundRect(s, mX, mY, mW, mH, 18);
            Ui.panel(s, mX, mY, mW, mH, 18, Theme.PANEL, Theme.PANEL_EDGE);
            s.setColor(familyColor(infocardTech));
            Ui.fillRoundRect(s, mX, mY + mH - 4f, mW, 4f, 3f);
            drawInfoBarsShapes(s, infocardTech, mX, mY, mW);
        }

        s.end();

        // ---- Text pass ----
        game.batch.begin();
        if (infocardTech == null) {
            Ui.textCenter(game.batch, game.fontBig, "AI PLAYGROUND",
                    Theme.VW / 2f, Theme.VH - 86, Theme.TEXT);
            Ui.textCenter(game.batch, game.fontSmall,
                    "Top " + otherTechs.length + " techniques + " + ensembleTechs.length
                            + " ensembles · " + HardwarePresets.hardwareLabel(),
                    Theme.VW / 2f, Theme.VH - 126, Theme.TEXT_DIM);

            // Card labels — only when card centre is above the drawer
            for (int i = 0; i < rowCount(); i++) {
                float top = cardTop(i);
                float cy  = top - CARD_H / 2f;
                if (cy <= LIST_BOT || top - CARD_H > LIST_TOP) continue;
                AiTechnique t = rowTech(i);
                if (t == null) { // collapsible "ENSEMBLES" header row
                    Ui.text(game.batch, game.font,
                            (ensemblesExpanded ? "[-]" : "[+]") + "  ENSEMBLES",
                            CARD_X + 20, cy + 6, Theme.GOLD);
                    Ui.textRight(game.batch, game.fontSmall,
                            ensembleTechs.length + " · best to worst",
                            CARD_X + CARD_W - 20, cy + 6, Theme.TEXT_DIM);
                    continue;
                }
                Ui.text(game.batch, game.font,      t.display,
                        CARD_X + 46, cy + 12, Theme.TEXT);
                Ui.text(game.batch, game.fontSmall,
                        t.isEnsemble() ? "uses " + shortMembers(t) : t.category + "  ·  " + t.kind,
                        CARD_X + 46, cy - 12, Theme.TEXT_DIM);
                Ui.textRight(game.batch, game.fontSmall, t.envBadge(),
                        INFO_CX - INFO_R - 10f, cy + 6, familyColor(t));
                boolean iHov = hitInfoIcon(i, mx, my);
                Ui.textCenter(game.batch, game.fontSmall, "i",
                        INFO_CX, cy + 5, iHov ? Theme.TEXT : Theme.TEXT_DIM);
            }

            drawDrawerText();
        }

        // Infocard modal text
        if (infocardTech != null) {
            AiTechnique t = infocardTech;
            float mW = INFO_MW, mH = INFO_MH;
            float mX = (Theme.VW - mW) / 2f, mY = (Theme.VH - mH) / 2f;
            float tx  = mX + 28f;
            Ui.text(game.batch, game.fontMed, t.display,
                    tx, mY + mH - 40, Theme.TEXT);
            Ui.textRight(game.batch, game.fontSmall, t.envBadge(),
                    mX + mW - 24, mY + mH - 42, familyColor(t));
            Ui.text(game.batch, game.fontSmall,
                    t.category + "  ·  " + t.kind + "  ·  obs: " + t.dataMode,
                    tx, mY + mH - 74, Theme.TEXT_DIM);
            // Content flows top→bottom with a running cursor: explanation, then (for
            // ensembles) the member manifest, then the ordered per-setting guide. The
            // attribute bars are pinned to the bottom region (drawInfoBarsShapes), so the
            // flow stops just above them.
            float cy = mY + mH - 100;
            for (String line : t.explainerLines()) {
                Ui.text(game.batch, game.fontSmall, line, tx, cy, Theme.TEXT);
                cy -= 21f;
            }
            if (t.isEnsemble()) {
                cy -= 6f;
                Ui.text(game.batch, game.fontSmall, "USES", tx, cy, Theme.GOLD);
                for (String m : t.ensembleMembers()) {
                    Ui.text(game.batch, game.fontSmall, "· " + m, tx + 60f, cy, Theme.TEXT);
                    cy -= 20f;
                }
                cy -= 4f;
            } else {
                cy -= 10f;
            }
            // Ordered "what each setting does" guide — the whole point of the infocard's
            // new lower half, so a player knows which drawer knobs matter for this pick.
            Ui.text(game.batch, game.fontSmall, "SETTINGS — WHAT TO TUNE", tx, cy, Theme.GOLD);
            cy -= 20f;
            float barsTop = mY + 176f;   // don't overrun the attribute bars pinned below
            for (String hint : t.settingsHints()) {
                if (cy < barsTop) break;
                Ui.text(game.batch, game.fontSmall, hint, tx, cy, Theme.TEXT_DIM);
                cy -= 19f;
            }
            // Attribute bars section (pinned bottom)
            Ui.text(game.batch, game.fontSmall, "ATTRIBUTES", tx, mY + 152f, Theme.TEXT_DIM);
            Ui.text(game.batch, game.fontSmall, "Performance", tx, mY + 124f, Theme.TEXT_DIM);
            Ui.text(game.batch, game.fontSmall, "Speed",       tx, mY + 94f,  Theme.TEXT_DIM);
            Ui.text(game.batch, game.fontSmall, "Setup ease",  tx, mY + 64f,  Theme.TEXT_DIM);
            Ui.textCenter(game.batch, game.fontSmall, "tap anywhere to close",
                    Theme.VW / 2f, mY + 26, Theme.TEXT_FAINT);
        }

        game.batch.end();
    }

    /** Compact "MCTS + Greedy + Heuristic" line for the card subtitle. */
    private static String shortMembers(AiTechnique t) {
        String[] members = t.ensembleMembers();
        StringBuilder sb = new StringBuilder();
        for (String m : members) {
            if (sb.length() > 0) sb.append(" + ");
            int paren = m.indexOf(" (");
            sb.append(paren > 0 ? m.substring(0, paren) : m);
        }
        return sb.toString();
    }

    private void drawInfoBarsShapes(ShapeRenderer s, AiTechnique t, float mX, float mY, float mW) {
        float bX   = mX + 158f;   // left edge of bar (after 130px label column)
        float bW   = mW - 186f;
        float barH = 14f;
        float[] bY = {mY + 117f, mY + 87f, mY + 57f};
        float[] frac = {
            t.strength / 100f,
            techSpeedFrac(t),
            t.jvmNative && !t.python ? 1.0f : t.jvmNative ? 0.55f : 0.25f
        };
        Color[] cols = {Theme.ACCENT_2, Theme.ACCENT_BLUE, Theme.GOLD};
        for (int i = 0; i < 3; i++) {
            s.setColor(0.08f, 0.09f, 0.13f, 1f);
            Ui.fillRoundRect(s, bX, bY[i], bW, barH, 5f);
            s.setColor(cols[i]);
            Ui.fillRoundRect(s, bX, bY[i], Math.max(bW * 0.05f, bW * frac[i]), barH, 5f);
        }
    }

    private static float techSpeedFrac(AiTechnique t) {
        return switch (t.family) {
            case PLANNING  -> t.isEnsemble() ? 0.65f : 0.82f;
            case EVOLUTION -> 0.60f;
            case IMITATION -> 0.50f;
            case PYTHON    -> 0.30f;
        };
    }

    private void drawCycler(ShapeRenderer s, Rectangle r, boolean enabled) {
        s.setColor(enabled ? Theme.PANEL_DEEP : new Color(0.10f, 0.11f, 0.16f, 0.6f));
        Ui.fillRoundRect(s, r.x, r.y, r.width, r.height, 8);
        if (!enabled) return;
        boolean hov = r.contains(mx, my);
        s.setColor(hov && mx < r.x + r.width / 2f ? Theme.ACCENT_BLUE : Theme.PANEL_EDGE);
        Ui.fillRoundRect(s, r.x + 4, r.y + 4, 30, r.height - 8, 6);
        s.setColor(hov && mx >= r.x + r.width / 2f ? Theme.ACCENT_BLUE : Theme.PANEL_EDGE);
        Ui.fillRoundRect(s, r.x + r.width - 34, r.y + 4, 30, r.height - 8, 6);
    }

    private void drawDrawerText() {
        AiTechnique t = cfg.technique;
        Ui.text(game.batch, game.fontMed, t.display,
                CARD_X + 4, LIST_BOT - 18, Theme.TEXT);
        Ui.textRight(game.batch, game.fontSmall, t.envBadge(),
                Theme.VW - 40, LIST_BOT - 20, familyColor(t));
        Ui.text(game.batch, game.fontSmall,
                t.isEnsemble() ? "uses " + shortMembers(t) : t.category + "  ·  " + t.kind,
                CARD_X + 4, LIST_BOT - 44, Theme.TEXT_DIM);

        cyclerText("Preset",      cfg.preset.cyclerLabel(),  presetCtrl, true);
        cyclerText("Speed",       cfg.speedLabel(),          speedCtrl, true);
        cyclerText("Parallelism", cfg.parallelismLabel(),    paraCtrl,  t.parallel);
        cyclerText(paramLabel(),  paramValue(),              paramCtrl, paramApplicable());
        cyclerText(ctx1Applicable() ? ctx1Label() : "Strategy", ctx1Value(), ctx1Ctrl, ctx1Applicable());
        cyclerText(ctx2Applicable() ? ctx2Label() : "Blend",    ctx2Value(), ctx2Ctrl, ctx2Applicable());
        cyclerText(ctx3Applicable() ? ctx3Label() : "Breeding", ctx3Value(), ctx3Ctrl, ctx3Applicable());

        boolean evo = evolutionApplicable();
        cyclerText("Sims/generation", Integer.toString(cfg.simsPerGen()),   simsCtrl,      evo);
        cyclerText("Elite views",     cfg.eliteViewCount() + "x",           eliteViewCtrl, evo);
        cyclerText("Ghost lineage",   cfg.ghostCullGens() + " gens",        ghostCullCtrl, evo);

        Color glc = evo ? Theme.TEXT : Theme.TEXT_FAINT;
        Ui.text(game.batch, game.font, "Ghost overlay",
                CARD_X + 4, ghostCtrl.y + ghostCtrl.height / 2f + 8, glc);
        if (!evo) Ui.textCenter(game.batch, game.fontSmall, "n/a (evolution only)",
                ghostCtrl.x + ghostCtrl.width / 2f - 28f,
                ghostCtrl.y + ghostCtrl.height / 2f, Theme.TEXT_FAINT);

        Ui.textCenter(game.batch, game.fontMed, "BACK",
                backBtn.x   + backBtn.width   / 2f, backBtn.y   + 32, Theme.TEXT);
        Ui.textCenter(game.batch, game.fontMed, "LAUNCH",
                launchBtn.x + launchBtn.width / 2f, launchBtn.y + 32, Theme.TEXT);
    }

    private void cyclerText(String label, String value, Rectangle r, boolean enabled) {
        Color lc = enabled ? Theme.TEXT : Theme.TEXT_FAINT;
        Ui.text(game.batch, game.fontSmall, label, CARD_X + 4, r.y + r.height / 2f + 7, lc);
        if (!enabled) {
            Ui.textCenter(game.batch, game.fontSmall, "n/a",
                    r.x + r.width / 2f, r.y + r.height / 2f, Theme.TEXT_FAINT);
            return;
        }
        Ui.textCenter(game.batch, game.fontSmall, value,
                r.x + r.width / 2f, r.y + r.height / 2f, Theme.TEXT);
        Ui.textCenter(game.batch, game.fontMed, "<",
                r.x + 19,           r.y + r.height / 2f + 1, Theme.TEXT);
        Ui.textCenter(game.batch, game.fontMed, ">",
                r.x + r.width - 19, r.y + r.height / 2f + 1, Theme.TEXT);
    }

    @Override public void resize(int width, int height) { viewport.update(width, height); }
    @Override public void hide() { Gdx.input.setInputProcessor(null); }
}
