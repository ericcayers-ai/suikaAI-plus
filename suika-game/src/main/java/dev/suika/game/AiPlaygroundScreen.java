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
 * AI Playground — searchable technique matrix with Explorer/Researcher modes,
 * hardware-aware presets, experiment import/export, shared {@link TechniqueConfigPanel},
 * and the persistent {@link ExperimentStatusRail}.
 */
public final class AiPlaygroundScreen extends ScreenAdapter {

    private final SuikaGame game;
    private final PlaygroundConfig cfg;
    private final TechniqueCatalog catalog = new TechniqueCatalog();

    private final OrthographicCamera camera = new OrthographicCamera();
    private final FitViewport viewport;
    private final Vector3 touch = new Vector3();
    private float mx, my;

    private float scroll = 0f;
    private final UiScroll listScroll = new UiScroll();
    private final UiToast toast = new UiToast();
    private final UiModal modal = new UiModal();
    private final UiFocus focus = new UiFocus();
    private final ExperimentStatusRail statusRail = new ExperimentStatusRail();

    // Layout constants — list sits between status rail and config drawer
    private static final float CARD_H   = 60f;
    private static final float LIST_TOP = Theme.VH - 250f;
    private static final float LIST_BOT = 470f;
    private static final float CARD_X   = 36f;
    private static final float CARD_W   = Theme.VW - 72f;

    // "i" icon — fixed right column inside each card
    private static final float INFO_R  = 11f;
    private static final float INFO_CX = CARD_X + CARD_W - INFO_R - 12f;

    // Drawer / toolbar — search widened; export/import use readable labels
    private static final float CTRL_X = 420, CTRL_W = 260, CTRL_H = 24, CTRL_STEP = 30;
    private final Rectangle modeCtrl      = new Rectangle(36, Theme.VH - 200, 140, 34);
    private final Rectangle filterCtrl    = new Rectangle(184, Theme.VH - 200, 170, 34);
    private final Rectangle searchCtrl    = new Rectangle(362, Theme.VH - 200, 150, 34);
    private final Rectangle clearSearchBtn = new Rectangle(518, Theme.VH - 200, 40, 34);
    private final Rectangle exportBtn     = new Rectangle(566, Theme.VH - 200, 70, 34);
    private final Rectangle importBtn     = new Rectangle(644, Theme.VH - 200, 70, 34);
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
    private final Rectangle backBtn       = new Rectangle();
    private final Rectangle launchBtn     = new Rectangle();
    private final TechniqueConfigPanel schemaPanel = new TechniqueConfigPanel();
    private TechniqueConfigPanel.Binding schemaBinding;
    {
        UiChrome.layoutBottomBar(backBtn, launchBtn, Theme.VW);
    }

    private static Rectangle row(int i) {
        return new Rectangle(CTRL_X, 386 - i * CTRL_STEP, CTRL_W, CTRL_H);
    }

    private static final float INFO_MW = 600f, INFO_MH = 640f;
    private AiTechnique infocardTech = null;
    private java.util.List<TechniqueCatalog.Row> visibleRows = java.util.List.of();

    /** One-shot note surfaced when the control center's stuck-run watchdog backs a hung
     *  run out to this menu (Settings → INPUT → "Stuck-run watchdog"). Set statically just
     *  before the screen switch, consumed once on construction. */
    static String pendingBackoutNote = null;

    private float presetHintTimer = 0f;
    private boolean searchFocused = false;

    void openInfocardForCapture(AiTechnique t) {
        this.infocardTech = t;
        this.modal.open(UiModal.Kind.INFO, t);
    }
    void setEnsemblesExpandedForCapture(boolean expanded) { catalog.ensemblesExpanded = expanded; rebuildRows(); }

    public AiPlaygroundScreen(SuikaGame game) { this(game, null); }

    public AiPlaygroundScreen(SuikaGame game, PlaygroundConfig existing) {
        this.game = game;
        this.cfg  = existing != null ? existing : new PlaygroundConfig();
        if (existing == null) cfg.selectDefaultsFor(AiTechnique.MCTS);
        cfg.actionBins = game.settings.actionBins();
        if (pendingBackoutNote != null) {
            toast.show(pendingBackoutNote, UiToast.Tone.WARNING, 6f);
            pendingBackoutNote = null;
        }
        UiViewport.OrientationSession.restorePortraitAfterRun(game.settings);
        viewport = UiViewport.portrait(camera);
        rebuildRows();
        relayoutSchemas();
    }

    private void rebuildRows() {
        visibleRows = catalog.buildRows();
    }

    private void relayoutSchemas() {
        schemaBinding = TechniqueConfigPanel.playgroundBinding(cfg);
        // Schema panel is shared for researcher depth hooks; explorer still uses drawer cyclers.
        var schemas = TechniqueConfigPanel.schemasFor(cfg.technique);
        schemaPanel.layout(36f, 120f, 180f, 200f, catalog.mode == TechniqueCatalog.Mode.RESEARCHER
                ? schemas : java.util.List.of());
    }

    private int rowCount() { return visibleRows.size(); }

    private TechniqueCatalog.Row rowAt(int row) {
        return row >= 0 && row < visibleRows.size() ? visibleRows.get(row) : null;
    }

    private AiTechnique rowTech(int row) {
        TechniqueCatalog.Row r = rowAt(row);
        return r instanceof TechniqueCatalog.TechRow t ? t.technique() : null;
    }

    @Override
    public void show() {
        rebuildFocus();
        Gdx.input.setInputProcessor(new InputAdapter() {
            @Override public boolean touchDown(int sx, int sy, int p, int b) {
                UiViewport.unproject(camera, viewport, touch, sx, sy);
                handleClick(touch.x, touch.y);
                return true;
            }
            @Override public boolean mouseMoved(int sx, int sy) {
                UiViewport.unproject(camera, viewport, touch, sx, sy);
                mx = touch.x; my = touch.y;
                return false;
            }
            @Override public boolean scrolled(float ax, float ay) {
                if (infocardTech == null) {
                    listScroll.contentHeight = rowCount() * CARD_H;
                    listScroll.viewHeight = LIST_TOP - LIST_BOT;
                    listScroll.offset = scroll;
                    listScroll.wheel(ay);
                    scroll = listScroll.offset;
                }
                return true;
            }
            @Override public boolean keyDown(int k) {
                if (UiKeys.isBackOrDismiss(k)) {
                    if (infocardTech != null) { infocardTech = null; modal.close(); return true; }
                    if (modal.dismiss()) return true;
                    if (searchFocused) { searchFocused = false; return true; }
                    game.setScreen(new MainMenuScreen(game));
                    return true;
                }
                if (searchFocused) {
                    if (k == Input.Keys.BACKSPACE) { catalog.backspaceQuery(); rebuildRows(); return true; }
                    if (k == Input.Keys.ENTER) { searchFocused = false; return true; }
                }
                if (infocardTech == null) {
                    listScroll.contentHeight = rowCount() * CARD_H;
                    listScroll.viewHeight = LIST_TOP - LIST_BOT;
                    listScroll.offset = scroll;
                    if (listScroll.key(k)) { scroll = listScroll.offset; return true; }
                }
                if (focus.key(k, Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT)
                        || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT))) return true;
                return false;
            }
            @Override public boolean keyTyped(char c) {
                if (!searchFocused) return false;
                if (c >= 32 && c < 127) {
                    catalog.appendQueryChar(c);
                    rebuildRows();
                    return true;
                }
                return false;
            }
        });
    }

    private void rebuildFocus() {
        focus.clear();
        focus.add(modeCtrl);
        focus.add(filterCtrl);
        focus.add(exportBtn);
        focus.add(importBtn);
        focus.add(launchBtn);
        focus.add(backBtn);
        schemaPanel.registerFocus(focus);
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

    private boolean rowClickable(int i) {
        float cy = cardTop(i) - CARD_H / 2f;
        return cy > LIST_BOT && cardTop(i) - CARD_H <= LIST_TOP;
    }

    private void handleClick(float x, float y) {
        if (infocardTech != null) { infocardTech = null; modal.close(); return; }

        if (backBtn.contains(x, y))   { game.setScreen(new MainMenuScreen(game)); return; }
        if (launchBtn.contains(x, y)) {
            UiViewport.OrientationSession.goLandscapeForRun();
            game.setScreen(new ControlCenterScreen(game, cfg));
            return;
        }
        if (modeCtrl.contains(x, y)) {
            catalog.cycleMode(dir(x, modeCtrl));
            rebuildRows();
            relayoutSchemas();
            return;
        }
        if (filterCtrl.contains(x, y)) {
            catalog.cycleFilter(dir(x, filterCtrl));
            rebuildRows();
            return;
        }
        if (searchCtrl.contains(x, y)) { searchFocused = true; return; }
        if (clearSearchBtn.contains(x, y)) {
            catalog.clearQuery();
            searchFocused = false;
            rebuildRows();
            return;
        }
        if (exportBtn.contains(x, y)) {
            String path = ExperimentIO.exportToFile(cfg);
            Gdx.app.getClipboard().setContents(ExperimentIO.exportText(cfg));
            toast.show(path.startsWith("Export failed") ? path : "Experiment copied + saved",
                    path.startsWith("Export failed") ? UiToast.Tone.WARNING : UiToast.Tone.SUCCESS, 4f);
            return;
        }
        if (importBtn.contains(x, y)) {
            String err = ExperimentIO.importText(cfg, Gdx.app.getClipboard().getContents());
            if (err != null) toast.show(err, UiToast.Tone.WARNING, 4f);
            else {
                toast.show("Imported " + cfg.technique.display, UiToast.Tone.SUCCESS, 3f);
                relayoutSchemas();
                rebuildRows();
            }
            return;
        }
        if (catalog.mode == TechniqueCatalog.Mode.RESEARCHER
                && schemaBinding != null && schemaPanel.click(x, y, schemaBinding)) return;
        if (presetCtrl.contains(x, y)) {
            if (!PresetCalibration.calibrated()) { presetHintTimer = 3f; return; }
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
            TechniqueCatalog.Row row = rowAt(i);
            if (row instanceof TechniqueCatalog.EnsembleHeader) {
                if (inCard) {
                    catalog.ensemblesExpanded = !catalog.ensemblesExpanded;
                    rebuildRows();
                    scroll = MathUtils.clamp(scroll, 0f, maxScroll());
                    return;
                }
                continue;
            }
            if (row instanceof TechniqueCatalog.PluginRow) {
                if (inCard) toast.show("Plugin hook — research-surfaces deepens this", UiToast.Tone.INFO, 3f);
                continue;
            }
            AiTechnique t = rowTech(i);
            if (t == null) continue;
            if (hitInfoIcon(i, x, y)) { infocardTech = t; modal.open(UiModal.Kind.INFO, t); return; }
            if (inCard) {
                cfg.selectDefaultsFor(t);
                relayoutSchemas();
                return;
            }
        }
    }

    private int dir(float x, Rectangle r) { return x < r.x + r.width / 2f ? -1 : +1; }
    private static int wrap(int i, int n)  { return Math.floorMod(i, n); }

    private boolean evolutionApplicable() {
        return TechniqueHyperparams.evolutionApplicable(cfg.technique);
    }

    private boolean paramApplicable() { return TechniqueHyperparams.paramApplicable(cfg.technique); }
    private String paramLabel() { return TechniqueHyperparams.paramLabel(cfg.technique); }
    private String paramValue() { return TechniqueHyperparams.paramValue(cfg); }
    private void cycleParam(int d) { TechniqueHyperparams.cycleParam(cfg, d); }

    private boolean gaEvolution() {
        return cfg.technique == AiTechnique.NEUROEVO || cfg.technique == AiTechnique.PBT;
    }

    private boolean ctx1Applicable() {
        if (gaEvolution()) return true;
        return switch (cfg.technique) {
            case ENS_MCTS_NET, ENS_MCTS_TIEBREAK, ENS_BANDIT, ENS_ADAPTIVE_VOTE -> true;
            default -> false;
        };
    }
    private String ctx1Label() {
        if (gaEvolution()) return "Selection";
        return switch (cfg.technique) {
            case ENS_MCTS_NET      -> "Donor net";
            case ENS_MCTS_TIEBREAK -> "Tie threshold";
            case ENS_BANDIT        -> "Explore (UCB c)";
            case ENS_ADAPTIVE_VOTE -> "Adapt rate";
            default                -> "—";
        };
    }
    private String ctx1Value() {
        if (gaEvolution()) return cfg.selection().label;
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
        if (gaEvolution()) {
            cfg.selectionIndex = wrap(cfg.selectionIndex + d, dev.suika.ai.GeneticTrainer.Selection.values().length);
            return;
        }
        switch (cfg.technique) {
            case ENS_MCTS_NET      -> cfg.ensembleDonorIndex = wrap(cfg.ensembleDonorIndex + d, PlaygroundConfig.ENSEMBLE_DONORS.length);
            case ENS_MCTS_TIEBREAK -> cfg.tieThresholdIndex = wrap(cfg.tieThresholdIndex + d, PlaygroundConfig.TIE_THRESHOLD_OPTIONS.length);
            case ENS_BANDIT        -> cfg.ucbCIndex = wrap(cfg.ucbCIndex + d, PlaygroundConfig.UCB_C_OPTIONS.length);
            case ENS_ADAPTIVE_VOTE -> cfg.adaptLrIndex = wrap(cfg.adaptLrIndex + d, PlaygroundConfig.ADAPT_LR_OPTIONS.length);
            default -> { }
        }
    }

    private boolean ctx2Applicable() {
        return gaEvolution() || cfg.technique == AiTechnique.ENS_MCTS_NET;
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

    private boolean ctx3Applicable() {
        return gaEvolution() || cfg.technique == AiTechnique.ENS_MCTS_NET;
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
            case DEEP_RL   -> Theme.ACCENT;
        };
    }

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
        Ui.background(s, Theme.VW, Theme.VH);
        if (toast.visible()) toast.drawShapes(s, Theme.VW, Theme.VH - 210f);
        s.end();

        // FIX: Enabled precise hardware-clipping glScissor window over the scroll list
        s.begin(ShapeRenderer.ShapeType.Filled);
        Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
        float scX = viewport.getScreenX();
        float scY = viewport.getScreenY();
        float scW = viewport.getScreenWidth();
        float scH = viewport.getScreenHeight();
        Gdx.gl.glScissor(
                (int) scX,
                (int) (scY + (LIST_BOT / Theme.VH) * scH),
                (int) scW,
                (int) (((LIST_TOP - LIST_BOT) / Theme.VH) * scH)
        );

        for (int i = 0; i < rowCount(); i++) {
            float top = cardTop(i);
            TechniqueCatalog.Row row = rowAt(i);
            AiTechnique t = rowTech(i);
            boolean hov = rowClickable(i)
                    && mx >= CARD_X && mx <= CARD_X + CARD_W
                    && my <= top    && my >= top - CARD_H + 6;
            if (t == null) {
                Color edge = row instanceof TechniqueCatalog.PluginRow ? Theme.ACCENT_BLUE
                        : (hov ? Theme.GOLD : Theme.PANEL_EDGE);
                s.setColor(edge);
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

            boolean iHov = hitInfoIcon(i, mx, my);
            s.setColor(iHov ? familyColor(t) : Theme.PANEL_EDGE);
            s.circle(INFO_CX, infoCY(i), INFO_R, 18);
        }
        s.end();
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);

        // Toolbar + drawer + status rail
        s.begin(ShapeRenderer.ShapeType.Filled);
        boolean evo = evolutionApplicable();
        Ui.cycler(s, modeCtrl, modeCtrl.contains(mx, my), true);
        Ui.cycler(s, filterCtrl, filterCtrl.contains(mx, my), true);
        Ui.button(s, searchCtrl, searchFocused ? Theme.ACCENT_BLUE : Theme.PANEL_EDGE, searchCtrl.contains(mx, my), true);
        Ui.button(s, clearSearchBtn, Theme.PANEL_EDGE, clearSearchBtn.contains(mx, my), true);
        Ui.button(s, exportBtn, Theme.GOLD, exportBtn.contains(mx, my), true);
        Ui.button(s, importBtn, Theme.ACCENT_BLUE, importBtn.contains(mx, my), true);
        drawCycler(s, presetCtrl, true);
        drawCycler(s, speedCtrl, true);
        if (cfg.technique.parallel) drawCycler(s, paraCtrl, true);
        if (paramApplicable()) drawCycler(s, paramCtrl, true);
        if (ctx1Applicable()) drawCycler(s, ctx1Ctrl, true);
        if (ctx2Applicable()) drawCycler(s, ctx2Ctrl, true);
        if (ctx3Applicable()) drawCycler(s, ctx3Ctrl, true);
        if (evo) {
            drawCycler(s, simsCtrl, true);
            drawCycler(s, eliteViewCtrl, true);
            drawCycler(s, ghostCullCtrl, true);
            s.setColor(Theme.PANEL_DEEP);
            Ui.fillRoundRect(s, ghostCtrl.x, ghostCtrl.y, ghostCtrl.width, ghostCtrl.height, 8);
            Ui.toggle(s,
                    ghostCtrl.x + ghostCtrl.width - 64f, ghostCtrl.y + 3f,
                    58f, ghostCtrl.height - 6f, cfg.ghostView);
        }
        if (catalog.mode == TechniqueCatalog.Mode.RESEARCHER && schemaBinding != null)
            schemaPanel.drawShapes(s, schemaBinding, mx, my, focus);
        statusRail.layout(36f, Theme.VH - 100f, Theme.VW - 72f, 44f);
        statusRail.drawShapes(s, ExperimentStatus.forPlayground(cfg, game.settings));
        UiChrome.secondaryButton(s, backBtn, backBtn.contains(mx, my), true);
        Ui.button(s, launchBtn, Theme.ACCENT_2, launchBtn.contains(mx, my), true);
        focus.drawRing(s);

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
                    Theme.VW / 2f, Theme.VH - 40f, Theme.TEXT);
            Ui.cyclerLabel(game.batch, game.fontSmall, modeCtrl, catalog.modeLabel(), true);
            Ui.cyclerLabel(game.batch, game.fontSmall, filterCtrl, catalog.filterLabel(), true);
            Ui.textCenter(game.batch, game.fontSmall,
                    catalog.query.isEmpty() ? "Search…" : catalog.query,
                    searchCtrl.x + searchCtrl.width / 2f, searchCtrl.y + 17f,
                    searchFocused ? Theme.TEXT : Theme.TEXT_DIM);
            Ui.textCenter(game.batch, game.fontSmall, "✕",
                    clearSearchBtn.x + clearSearchBtn.width / 2f, clearSearchBtn.y + 17f, Theme.TEXT);
            Ui.textCenter(game.batch, game.fontSmall, "Export",
                    exportBtn.x + exportBtn.width / 2f, exportBtn.y + 17f, Theme.BG_BOTTOM);
            Ui.textCenter(game.batch, game.fontSmall, "Import",
                    importBtn.x + importBtn.width / 2f, importBtn.y + 17f, Theme.TEXT);

            // FIX: Flush batch buffer and enable scissor for text metrics
            game.batch.flush();
            Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
            Gdx.gl.glScissor(
                    (int) scX,
                    (int) (scY + (LIST_BOT / Theme.VH) * scH),
                    (int) scW,
                    (int) (((LIST_TOP - LIST_BOT) / Theme.VH) * scH)
            );

            for (int i = 0; i < rowCount(); i++) {
                float top = cardTop(i);
                float cy  = top - CARD_H / 2f;
                TechniqueCatalog.Row row = rowAt(i);
                if (row instanceof TechniqueCatalog.EnsembleHeader eh) {
                    Ui.text(game.batch, game.font,
                            (catalog.ensemblesExpanded || catalog.filter == TechniqueCatalog.Filter.ENSEMBLES
                                    ? "[-]" : "[+]") + "  ENSEMBLES",
                            CARD_X + 20, cy + 6, Theme.GOLD);
                    Ui.textRight(game.batch, game.fontSmall,
                            eh.count() + " · best to worst",
                            CARD_X + CARD_W - 20, cy + 6, Theme.TEXT_DIM);
                    continue;
                }
                if (row instanceof TechniqueCatalog.PluginRow pr) {
                    Ui.text(game.batch, game.font, pr.displayName(), CARD_X + 20, cy + 12, Theme.TEXT);
                    Ui.text(game.batch, game.fontSmall, pr.blurb(), CARD_X + 20, cy - 12, Theme.TEXT_DIM);
                    continue;
                }
                AiTechnique t = rowTech(i);
                if (t == null) continue;
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

            game.batch.flush();
            Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);

            drawDrawerText();
            statusRail.drawText(game.batch, game.fontSmall, game.fontSmall,
                    ExperimentStatus.forPlayground(cfg, game.settings));
            if (catalog.mode == TechniqueCatalog.Mode.RESEARCHER && schemaBinding != null)
                schemaPanel.drawText(game.batch, game.fontSmall, game.fontSmall, schemaBinding);
        }

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
            Ui.text(game.batch, game.fontSmall, "SETTINGS — WHAT TO TUNE", tx, cy, Theme.GOLD);
            cy -= 20f;
            float barsTop = mY + 176f;
            for (String hint : t.settingsHints()) {
                if (cy < barsTop) break;
                Ui.text(game.batch, game.fontSmall, hint, tx, cy, Theme.TEXT_DIM);
                cy -= 19f;
            }
            Ui.text(game.batch, game.fontSmall, "ATTRIBUTES", tx, mY + 152f, Theme.TEXT_DIM);
            Ui.text(game.batch, game.fontSmall, "Performance", tx, mY + 124f, Theme.TEXT_DIM);
            Ui.text(game.batch, game.fontSmall, "Speed",       tx, mY + 94f,  Theme.TEXT_DIM);
            Ui.text(game.batch, game.fontSmall, "Setup ease",  tx, mY + 64f,  Theme.TEXT_DIM);
            Ui.textCenter(game.batch, game.fontSmall, "tap anywhere to close",
                    Theme.VW / 2f, mY + 26, Theme.TEXT_FAINT);
        }

        game.batch.end();
    }

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
        float bX   = mX + 158f;
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
            case DEEP_RL   -> 0.70f;
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
        if (presetHintTimer > 0f) {
            presetHintTimer -= Gdx.graphics.getDeltaTime();
            Ui.textCenter(game.batch, game.fontSmall, "Calibrate presets in Settings -> RT LAB first",
                    Theme.VW / 2f, 92, Theme.GOLD);
        }
        toast.tick(Gdx.graphics.getDeltaTime());
        toast.drawText(game.batch, game.fontSmall, Theme.VW, Theme.VH - 240f);
        cyclerText("Speed",       cfg.speedLabel(),          speedCtrl, true);
        if (t.parallel) cyclerText("Parallelism", cfg.parallelismLabel(), paraCtrl, true);
        if (paramApplicable()) cyclerText(paramLabel(), paramValue(), paramCtrl, true);
        if (ctx1Applicable()) cyclerText(ctx1Label(), ctx1Value(), ctx1Ctrl, true);
        if (ctx2Applicable()) cyclerText(ctx2Label(), ctx2Value(), ctx2Ctrl, true);
        if (ctx3Applicable()) cyclerText(ctx3Label(), ctx3Value(), ctx3Ctrl, true);

        boolean evo = evolutionApplicable();
        if (evo) {
            cyclerText("Sims/generation", Integer.toString(cfg.simsPerGen()), simsCtrl, true);
            cyclerText("Elite views", cfg.eliteViewCount() + "x", eliteViewCtrl, true);
            cyclerText("Ghost lineage", cfg.ghostCullGens() + " gens", ghostCullCtrl, true);
            Ui.text(game.batch, game.font, "Ghost overlay",
                    CARD_X + 4, ghostCtrl.y + ghostCtrl.height / 2f + 8, Theme.TEXT);
        }

        Ui.textCenter(game.batch, game.fontMed, "BACK",
                backBtn.x   + backBtn.width   / 2f, backBtn.y   + 32, Theme.TEXT);
        Ui.textCenter(game.batch, game.fontMed, "LAUNCH",
                launchBtn.x + launchBtn.width / 2f, launchBtn.y + 32, Theme.TEXT);
    }

    private void cyclerText(String label, String value, Rectangle r, boolean enabled) {
        if (!enabled) return;
        Ui.text(game.batch, game.fontSmall, label, CARD_X + 4, r.y + r.height / 2f + 7, Theme.TEXT);
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