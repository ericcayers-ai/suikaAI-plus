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
 */
public final class AiPlaygroundScreen extends ScreenAdapter {

    private final SuikaGame game;
    private final PlaygroundConfig cfg;

    private final OrthographicCamera camera = new OrthographicCamera();
    private final FitViewport viewport;
    private final Vector3 touch = new Vector3();
    private float mx, my;

    // Ensembles get their own sorted (best -> worst, by AiTechnique#strength),
    // collapsible section at the top of the list instead of being interleaved with
    // everything else — the rest of the matrix keeps its existing curated order.
    private final AiTechnique[] ensembleTechs;
    private final AiTechnique[] otherTechs;
    private boolean ensemblesExpanded = false;
    private float scroll = 0f;

    // Layout constants
    private static final float CARD_H   = 60f;
    private static final float LIST_TOP = Theme.VH - 158f;
    private static final float LIST_BOT = 380f;
    private static final float CARD_X   = 36f;
    private static final float CARD_W   = Theme.VW - 72f;

    // "i" icon — fixed right column inside each card
    private static final float INFO_R  = 11f;
    private static final float INFO_CX = CARD_X + CARD_W - INFO_R - 12f;

    // Drawer controls — 7 stacked rows so evolution's launch knobs (sims/gen, ghost
    // lineage, elite view count) fit in the same drawer as everything else, "n/a"
    // elsewhere just like Parallelism/Param/Ghost-view already are for techniques that
    // don't use them.
    private final Rectangle speedCtrl     = new Rectangle(420, 286, 260, 24);
    private final Rectangle paraCtrl      = new Rectangle(420, 256, 260, 24);
    private final Rectangle paramCtrl     = new Rectangle(420, 226, 260, 24);
    private final Rectangle simsCtrl      = new Rectangle(420, 196, 260, 24);
    private final Rectangle ghostCullCtrl = new Rectangle(420, 166, 260, 24);
    private final Rectangle eliteViewCtrl = new Rectangle(420, 136, 260, 24);
    private final Rectangle ghostCtrl     = new Rectangle(420, 106, 260, 24);
    private final Rectangle backBtn       = new Rectangle(36, 16, 300, 64);
    private final Rectangle launchBtn     = new Rectangle(Theme.VW - 336, 16, 300, 64);

    // Infocard modal (null = closed)
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
        for (AiTechnique t : AiTechnique.values()) (t.kind.equals("ensemble") ? ens : other).add(t);
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
                // Negated: wheel-down (ay > 0) must reveal content further down the
                // list, not scroll back toward the top — was backwards (confirmed by
                // hands-on testing), the classic y-up-virtual-space scroll-sign trap.
                if (infocardTech == null)
                    scroll = MathUtils.clamp(scroll - ay * 46f, 0f, maxScroll());
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

    private void handleClick(float x, float y) {
        if (infocardTech != null) { infocardTech = null; return; }

        if (backBtn.contains(x, y))   { game.setScreen(new MainMenuScreen(game)); return; }
        if (launchBtn.contains(x, y)) {
            // §9: the control center's multi-board / stats layout reads far better in
            // landscape, so launching from here defaults the window to it instead of
            // asking the player to resize manually — a portrait window they already
            // had (e.g. deliberately narrowed) is left alone.
            if (Gdx.graphics.getWidth() <= Gdx.graphics.getHeight() * 1.3f) goLandscape();
            game.setScreen(new ControlCenterScreen(game, cfg));
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
        if (simsCtrl.contains(x, y) && ghostApplicable()) {
            cfg.simsPerGenIndex = wrap(cfg.simsPerGenIndex + dir(x, simsCtrl), PlaygroundConfig.SIMS_PER_GEN_OPTIONS.length);
            return;
        }
        if (ghostCullCtrl.contains(x, y) && ghostApplicable()) {
            cfg.ghostCullIndex = wrap(cfg.ghostCullIndex + dir(x, ghostCullCtrl), PlaygroundConfig.GHOST_CULL_OPTIONS.length);
            return;
        }
        if (eliteViewCtrl.contains(x, y) && ghostApplicable()) {
            cfg.eliteViewIndex = wrap(cfg.eliteViewIndex + dir(x, eliteViewCtrl), PlaygroundConfig.ELITE_VIEW_OPTIONS.length);
            return;
        }
        if (ghostCtrl.contains(x, y) && ghostApplicable()) { cfg.ghostView = !cfg.ghostView; return; }

        for (int i = 0; i < rowCount(); i++) {
            float top = cardTop(i);
            if (top < LIST_BOT || top - CARD_H > LIST_TOP) continue;
            AiTechnique t = rowTech(i);
            if (t == null) { // header row — toggle the ensemble dropdown
                if (x >= CARD_X && x <= CARD_X + CARD_W && y <= top && y >= top - CARD_H + 6) {
                    ensemblesExpanded = !ensemblesExpanded;
                    scroll = MathUtils.clamp(scroll, 0f, maxScroll());
                }
                return;
            }
            if (hitInfoIcon(i, x, y)) { infocardTech = t; return; }
            if (x >= CARD_X && x <= CARD_X + CARD_W && y <= top && y >= top - CARD_H + 6) {
                cfg.selectDefaultsFor(t); return;
            }
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

    private boolean ghostApplicable() {
        return cfg.technique.family == AiTechnique.Family.EVOLUTION;
    }

    // Ensembles built on MCTS search share its Rollouts knob; ENS_RTG_VERIFIED shares
    // Decision Transformer's Return knob. ENS_GREEDY_GUARD/ENS_GENERATIVE_GREEDY have
    // no adjustable launch parameter (they only take the fixed action-bin count).
    private static final java.util.Set<AiTechnique> ROLLOUT_PARAM_TECHS = java.util.Set.of(
            AiTechnique.MCTS, AiTechnique.ALPHAZERO, AiTechnique.ENS_MCTS_NET,
            AiTechnique.ENS_MCTS_TIEBREAK, AiTechnique.ENS_VOTING, AiTechnique.ENS_EVOLVED_MCTS,
            AiTechnique.ENS_IMITATION_MCTS, AiTechnique.ENS_ADAPTIVE_VOTE, AiTechnique.ENS_BANDIT);

    private boolean paramApplicable() {
        if (ROLLOUT_PARAM_TECHS.contains(cfg.technique)) return true;
        return switch (cfg.technique) {
            case NEUROEVO, PBT, DECISION_TRANSFORMER, OFFLINE_RL, BC, DAGGER, ENS_RTG_VERIFIED -> true;
            default -> false;
        };
    }
    private String paramLabel() {
        if (ROLLOUT_PARAM_TECHS.contains(cfg.technique)) return "Rollouts";
        return switch (cfg.technique) {
            case NEUROEVO, PBT                                      -> "Population";
            case DECISION_TRANSFORMER, OFFLINE_RL, ENS_RTG_VERIFIED -> "Target return";
            case BC, DAGGER                                         -> "Learning rate";
            default                                                 -> "—";
        };
    }
    private String paramValue() {
        if (ROLLOUT_PARAM_TECHS.contains(cfg.technique)) return Integer.toString(cfg.rollouts);
        return switch (cfg.technique) {
            case NEUROEVO, PBT                                      -> Integer.toString(cfg.populationSize);
            case DECISION_TRANSFORMER, OFFLINE_RL, ENS_RTG_VERIFIED -> Integer.toString((int) cfg.targetReturn);
            case BC, DAGGER                                         -> String.format("%.0e", cfg.learningRate);
            default                                                 -> "—";
        };
    }
    private void cycleParam(int d) {
        if (ROLLOUT_PARAM_TECHS.contains(cfg.technique)) { cfg.rollouts = cycleInt(ROLLOUTS, cfg.rollouts, d); return; }
        switch (cfg.technique) {
            case NEUROEVO, PBT                                      -> cfg.populationSize = cycleInt(POP, cfg.populationSize, d);
            case DECISION_TRANSFORMER, OFFLINE_RL, ENS_RTG_VERIFIED -> cfg.targetReturn = cycleInt(RETURNS, (int) cfg.targetReturn, d);
            case BC, DAGGER                                         -> cfg.learningRate = cycleDouble(LRS, cfg.learningRate, d);
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

    private Color familyColor(AiTechnique t) {
        return switch (t.family) {
            case PLANNING  -> Theme.ACCENT_BLUE;
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
            boolean hov = mx >= CARD_X && mx <= CARD_X + CARD_W
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
        boolean ghostEn = ghostApplicable();
        drawCycler(s, speedCtrl, true);
        drawCycler(s, paraCtrl,  cfg.technique.parallel);
        drawCycler(s, paramCtrl, paramApplicable());
        drawCycler(s, simsCtrl,      ghostEn);
        drawCycler(s, ghostCullCtrl, ghostEn);
        drawCycler(s, eliteViewCtrl, ghostEn);
        s.setColor(ghostEn ? Theme.PANEL_DEEP : new Color(0.10f, 0.11f, 0.16f, 0.6f));
        Ui.fillRoundRect(s, ghostCtrl.x, ghostCtrl.y, ghostCtrl.width, ghostCtrl.height, 8);
        if (ghostEn) Ui.toggle(s,
                ghostCtrl.x + ghostCtrl.width - 64f, ghostCtrl.y + 3f,
                58f, ghostCtrl.height - 6f, cfg.ghostView);
        Ui.button(s, backBtn,   Theme.PANEL_EDGE, backBtn.contains(mx, my),   true);
        Ui.button(s, launchBtn, Theme.ACCENT_2,   launchBtn.contains(mx, my), true);

        // Infocard modal overlay — drawn opaque so the busy list behind it is fully hidden.
        if (infocardTech != null) {
            s.setColor(0.03f, 0.04f, 0.07f, 0.94f);     // near-opaque dim over everything
            s.rect(0, 0, Theme.VW, Theme.VH);
            float mW = 580f, mH = 400f;
            float mX = (Theme.VW - mW) / 2f, mY = (Theme.VH - mH) / 2f;
            s.setColor(0.08f, 0.09f, 0.13f, 1f);        // solid card backing (no bleed-through)
            Ui.fillRoundRect(s, mX, mY, mW, mH, 18);
            Ui.panel(s, mX, mY, mW, mH, 18, Theme.PANEL, Theme.PANEL_EDGE);
            s.setColor(familyColor(infocardTech));
            Ui.fillRoundRect(s, mX, mY + mH - 4f, mW, 4f, 3f);
            drawInfoBarsShapes(s, infocardTech, mX, mY, mW);
        }

        s.end();

        // ---- Text pass ----
        game.batch.begin();
        // Background text (header, cards, drawer) is suppressed while the modal is open,
        // so nothing bleeds through the dimmed overlay.
        if (infocardTech == null) {
            Ui.textCenter(game.batch, game.fontBig, "AI PLAYGROUND",
                    Theme.VW / 2f, Theme.VH - 86, Theme.TEXT);
            Ui.textCenter(game.batch, game.fontSmall,
                    "Capability matrix · " + AiTechnique.values().length + " techniques · scroll to browse",
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
                Ui.text(game.batch, game.fontSmall, t.category + "  ·  " + t.kind,
                        CARD_X + 46, cy - 12, Theme.TEXT_DIM);
                // env badge left of the "i" circle
                Ui.textRight(game.batch, game.fontSmall, t.envBadge(),
                        INFO_CX - INFO_R - 10f, cy + 6, familyColor(t));
                // "i" glyph
                boolean iHov = hitInfoIcon(i, mx, my);
                Ui.textCenter(game.batch, game.fontSmall, "i",
                        INFO_CX, cy + 5, iHov ? Theme.TEXT : Theme.TEXT_DIM);
            }

            drawDrawerText();
        }

        // Infocard modal text
        if (infocardTech != null) {
            AiTechnique t = infocardTech;
            float mW = 580f, mH = 400f;
            float mX = (Theme.VW - mW) / 2f, mY = (Theme.VH - mH) / 2f;
            float tx  = mX + 28f;
            Ui.text(game.batch, game.fontMed, t.display,
                    tx, mY + mH - 44, Theme.TEXT);
            Ui.textRight(game.batch, game.fontSmall, t.envBadge(),
                    mX + mW - 24, mY + mH - 46, familyColor(t));
            Ui.text(game.batch, game.fontSmall,
                    t.category + "  ·  " + t.kind + "  ·  obs: " + t.dataMode,
                    tx, mY + mH - 82, Theme.TEXT_DIM);
            // Plain-English explanation (no prior knowledge needed), pre-wrapped.
            String[] lines = t.explainerLines();
            float ey = mY + mH - 106;
            for (int li = 0; li < Math.min(4, lines.length); li++) {
                Ui.text(game.batch, game.fontSmall, lines[li], tx, ey, Theme.TEXT);
                ey -= 22f;
            }
            // Attribute bars section
            Ui.text(game.batch, game.fontSmall, "ATTRIBUTES", tx, mY + 192f, Theme.TEXT_DIM);
            Ui.text(game.batch, game.fontSmall, "Performance", tx, mY + 164f, Theme.TEXT_DIM);
            Ui.text(game.batch, game.fontSmall, "Speed",       tx, mY + 124f, Theme.TEXT_DIM);
            Ui.text(game.batch, game.fontSmall, "Setup ease",  tx, mY + 84f,  Theme.TEXT_DIM);
            Ui.textCenter(game.batch, game.fontSmall, "tap anywhere to close",
                    Theme.VW / 2f, mY + 26, Theme.TEXT_FAINT);
        }

        game.batch.end();
    }

    private void drawInfoBarsShapes(ShapeRenderer s, AiTechnique t, float mX, float mY, float mW) {
        float bX   = mX + 158f;   // left edge of bar (after 130px label column)
        float bW   = mW - 186f;   // bar width (580 - 28 margin - 130 label - 28 margin)
        float barH = 14f;
        float[] bY = {mY + 157f, mY + 117f, mY + 77f};
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
            case PLANNING  -> 0.82f;
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
                CARD_X + 4, 358, Theme.TEXT);
        Ui.textRight(game.batch, game.fontSmall, t.envBadge(),
                Theme.VW - 40, 356, familyColor(t));
        Ui.text(game.batch, game.fontSmall,
                t.category + "  ·  " + t.kind,
                CARD_X + 4, 330, Theme.TEXT_DIM);

        cyclerText("Speed",       cfg.speedLabel(),      speedCtrl, true);
        cyclerText("Parallelism", cfg.parallelismLabel(), paraCtrl,  t.parallel);
        cyclerText(paramLabel(),  paramValue(),          paramCtrl, paramApplicable());

        boolean ghostEn = ghostApplicable();
        cyclerText("Sims/generation", Integer.toString(cfg.simsPerGen()),   simsCtrl,      ghostEn);
        cyclerText("Ghost lineage",   cfg.ghostCullGens() + " gens",        ghostCullCtrl, ghostEn);
        cyclerText("Elite views",     cfg.eliteViewCount() + "x",           eliteViewCtrl, ghostEn);

        Color glc = ghostEn ? Theme.TEXT : Theme.TEXT_FAINT;
        Ui.text(game.batch, game.font, "Ghost overlay",
                CARD_X + 4, ghostCtrl.y + ghostCtrl.height / 2f + 8, glc);
        if (!ghostEn) Ui.textCenter(game.batch, game.fontSmall, "n/a (evolution only)",
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
