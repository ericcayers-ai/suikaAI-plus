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
 * The AI Playground entry point — reached from the menu's "Watch AI" (the initial step
 * when AI mode is selected), kept entirely separate from the main settings.
 *
 * <p>Shows the full capability matrix (every technique, JVM and JVM+Python) as a
 * scrollable list. Selecting one reveals a config drawer with the knobs that apply to
 * it — speed, parallelism, and a family-specific hyper-parameter — then LAUNCH opens
 * its specialised {@link ControlCenterScreen}.
 */
public final class AiPlaygroundScreen extends ScreenAdapter {

    private final SuikaGame game;
    private final PlaygroundConfig cfg;

    private final OrthographicCamera camera = new OrthographicCamera();
    private final FitViewport viewport;
    private final Vector3 touch = new Vector3();
    private float mx, my;

    private final AiTechnique[] techs = AiTechnique.values();
    private float scroll = 0f;

    private static final float CARD_H   = 60f;
    private static final float LIST_TOP = Theme.VH - 158f;  // y of first card's top
    private static final float LIST_BOT = 380f;             // drawer top
    private static final float CARD_X   = 36f;
    private static final float CARD_W   = Theme.VW - 72f;

    // drawer controls
    private final Rectangle speedCtrl = new Rectangle(420, 246, 260, 34);
    private final Rectangle paraCtrl  = new Rectangle(420, 204, 260, 34);
    private final Rectangle paramCtrl = new Rectangle(420, 162, 260, 34);
    private final Rectangle backBtn   = new Rectangle(36, 36, 300, 64);
    private final Rectangle launchBtn = new Rectangle(Theme.VW - 336, 36, 300, 64);

    private static final int[] ROLLOUTS = {40, 80, 150, 300};
    private static final int[] POP      = {16, 24, 40, 64};
    private static final int[] RETURNS  = {1000, 2000, 4000};
    private static final double[] LRS   = {1e-3, 3e-3, 1e-2};

    public AiPlaygroundScreen(SuikaGame game) { this(game, null); }

    public AiPlaygroundScreen(SuikaGame game, PlaygroundConfig existing) {
        this.game = game;
        this.cfg  = existing != null ? existing : new PlaygroundConfig();
        if (existing == null) cfg.selectDefaultsFor(AiTechnique.MCTS);
        cfg.actionBins = game.settings.actionBins();
        viewport = new FitViewport(Theme.VW, Theme.VH, camera);
        camera.position.set(Theme.VW / 2f, Theme.VH / 2f, 0f);
        camera.update();
    }

    @Override
    public void show() {
        Gdx.input.setInputProcessor(new InputAdapter() {
            @Override public boolean touchDown(int sx, int sy, int p, int b) {
                camera.unproject(touch.set(sx, sy, 0)); handleClick(touch.x, touch.y); return true;
            }
            @Override public boolean mouseMoved(int sx, int sy) {
                camera.unproject(touch.set(sx, sy, 0)); mx = touch.x; my = touch.y; return false;
            }
            @Override public boolean scrolled(float ax, float ay) {
                scroll = MathUtils.clamp(scroll + ay * 46f, 0f, maxScroll()); return true;
            }
            @Override public boolean keyDown(int k) {
                if (k == Input.Keys.ESCAPE) { game.setScreen(new MainMenuScreen(game)); return true; }
                return false;
            }
        });
    }

    private float maxScroll() {
        float total = techs.length * CARD_H;
        float band  = LIST_TOP - LIST_BOT;
        return Math.max(0f, total - band);
    }

    private float cardTop(int i) { return LIST_TOP + scroll - i * CARD_H; }

    private void handleClick(float x, float y) {
        if (backBtn.contains(x, y))   { game.setScreen(new MainMenuScreen(game)); return; }
        if (launchBtn.contains(x, y)) { game.setScreen(new ControlCenterScreen(game, cfg)); return; }
        if (speedCtrl.contains(x, y)) { cfg.speedIndex = wrap(cfg.speedIndex + dir(x, speedCtrl), PlaygroundConfig.SPEEDS.length); return; }
        if (paraCtrl.contains(x, y) && cfg.technique.parallel) {
            int cores = Runtime.getRuntime().availableProcessors();
            cfg.parallelism = MathUtils.clamp(cfg.parallelism + dir(x, paraCtrl), 1, cores); return;
        }
        if (paramCtrl.contains(x, y) && paramApplicable()) { cycleParam(dir(x, paramCtrl)); return; }
        // card hit-test (only within the visible band)
        for (int i = 0; i < techs.length; i++) {
            float top = cardTop(i);
            if (top > LIST_TOP + 1 || top - CARD_H < LIST_BOT - 1) continue; // off-band
            if (x >= CARD_X && x <= CARD_X + CARD_W && y <= top && y >= top - CARD_H + 6) {
                cfg.selectDefaultsFor(techs[i]);
                return;
            }
        }
    }

    private int dir(float x, Rectangle r) { return x < r.x + r.width / 2f ? -1 : +1; }
    private static int wrap(int i, int n) { return Math.floorMod(i, n); }

    // ---- family-specific param ----
    private boolean paramApplicable() {
        return switch (cfg.technique) {
            case MCTS, ALPHAZERO, NEUROEVO, PBT, DECISION_TRANSFORMER, OFFLINE_RL, BC, DAGGER -> true;
            default -> false;
        };
    }
    private String paramLabel() {
        return switch (cfg.technique) {
            case MCTS, ALPHAZERO -> "Rollouts";
            case NEUROEVO, PBT   -> "Population";
            case DECISION_TRANSFORMER, OFFLINE_RL -> "Target return";
            case BC, DAGGER      -> "Learning rate";
            default -> "—";
        };
    }
    private String paramValue() {
        return switch (cfg.technique) {
            case MCTS, ALPHAZERO -> Integer.toString(cfg.rollouts);
            case NEUROEVO, PBT   -> Integer.toString(cfg.populationSize);
            case DECISION_TRANSFORMER, OFFLINE_RL -> Integer.toString((int) cfg.targetReturn);
            case BC, DAGGER      -> String.format("%.0e", cfg.learningRate);
            default -> "—";
        };
    }
    private void cycleParam(int d) {
        switch (cfg.technique) {
            case MCTS, ALPHAZERO -> cfg.rollouts = cycleInt(ROLLOUTS, cfg.rollouts, d);
            case NEUROEVO, PBT   -> cfg.populationSize = cycleInt(POP, cfg.populationSize, d);
            case DECISION_TRANSFORMER, OFFLINE_RL -> cfg.targetReturn = cycleInt(RETURNS, (int) cfg.targetReturn, d);
            case BC, DAGGER      -> cfg.learningRate = cycleDouble(LRS, cfg.learningRate, d);
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

        // cards
        for (int i = 0; i < techs.length; i++) {
            float top = cardTop(i);
            if (top < LIST_BOT - 1 || top - CARD_H > LIST_TOP + 1) continue;
            AiTechnique t = techs[i];
            boolean sel = t == cfg.technique;
            boolean hov = mx >= CARD_X && mx <= CARD_X + CARD_W && my <= top && my >= top - CARD_H + 6;
            if (sel) { s.setColor(familyColor(t)); Ui.fillRoundRect(s, CARD_X - 3, top - CARD_H + 3, CARD_W + 6, CARD_H - 6 + 6, 12); }
            s.setColor(hov ? Theme.PANEL_EDGE : Theme.PANEL);
            Ui.fillRoundRect(s, CARD_X, top - CARD_H + 6, CARD_W, CARD_H - 8, 10);
            // family dot
            Color fc = familyColor(t);
            s.setColor(fc);
            s.circle(CARD_X + 24, top - CARD_H / 2f + 2, 9, 16);
        }

        // opaque top + bottom masks (so scrolled cards clip cleanly)
        s.setColor(Theme.BG_TOP);
        s.rect(0, Theme.VH - 150, Theme.VW, 150);
        s.setColor(Theme.PANEL_DEEP);
        Ui.fillRoundRect(s, 0, 0, Theme.VW, LIST_BOT - 16, 0);

        // drawer controls
        drawCycler(s, speedCtrl, true);
        drawCycler(s, paraCtrl, cfg.technique.parallel);
        drawCycler(s, paramCtrl, paramApplicable());
        Ui.button(s, backBtn, Theme.PANEL_EDGE, backBtn.contains(mx, my), true);
        Ui.button(s, launchBtn, Theme.ACCENT_2, launchBtn.contains(mx, my), true);
        s.end();

        // ---- text ----
        game.batch.begin();
        Ui.textCenter(game.batch, game.fontBig, "AI PLAYGROUND", Theme.VW / 2f, Theme.VH - 86, Theme.TEXT);
        Ui.textCenter(game.batch, game.fontSmall, "Capability matrix · " + techs.length + " techniques · scroll to browse",
                Theme.VW / 2f, Theme.VH - 126, Theme.TEXT_DIM);

        for (int i = 0; i < techs.length; i++) {
            float top = cardTop(i);
            if (top < LIST_BOT - 1 || top - CARD_H > LIST_TOP + 1) continue;
            AiTechnique t = techs[i];
            float cy = top - CARD_H / 2f;
            Ui.text(game.batch, game.font, t.display, CARD_X + 46, cy + 12, Theme.TEXT);
            Ui.text(game.batch, game.fontSmall, t.category + "  ·  " + t.kind, CARD_X + 46, cy - 12, Theme.TEXT_DIM);
            Ui.textRight(game.batch, game.fontSmall, t.envBadge(), CARD_X + CARD_W - 18, cy + 6, familyColor(t));
        }

        drawDrawerText();
        game.batch.end();
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
        Ui.text(game.batch, game.fontMed, t.display, CARD_X + 4, 348, Theme.TEXT);
        Ui.textRight(game.batch, game.fontSmall, t.envBadge(), Theme.VW - 40, 346, familyColor(t));
        Ui.text(game.batch, game.fontSmall, t.category + "  ·  " + t.kind + "  ·  obs: " + t.dataMode,
                CARD_X + 4, 318, Theme.TEXT_DIM);
        Ui.text(game.batch, game.fontSmall, t.blurb, CARD_X + 4, 292, Theme.TEXT_DIM);

        cyclerText("Speed", cfg.speedLabel(), speedCtrl, true);
        cyclerText("Parallelism", cfg.parallelism + " threads", paraCtrl, t.parallel);
        cyclerText(paramLabel(), paramValue(), paramCtrl, paramApplicable());

        Ui.textCenter(game.batch, game.fontMed, "BACK", backBtn.x + backBtn.width / 2f, backBtn.y + 32, Theme.TEXT);
        Ui.textCenter(game.batch, game.fontMed, "LAUNCH", launchBtn.x + launchBtn.width / 2f, launchBtn.y + 32, Theme.TEXT);
    }

    private void cyclerText(String label, String value, Rectangle r, boolean enabled) {
        Color lc = enabled ? Theme.TEXT : Theme.TEXT_FAINT;
        Ui.text(game.batch, game.font, label, CARD_X + 4, r.y + r.height / 2f + 8, lc);
        if (!enabled) {
            Ui.textCenter(game.batch, game.fontSmall, "n/a", r.x + r.width / 2f, r.y + r.height / 2f, Theme.TEXT_FAINT);
            return;
        }
        Ui.textCenter(game.batch, game.font, value, r.x + r.width / 2f, r.y + r.height / 2f, Theme.TEXT);
        Ui.textCenter(game.batch, game.fontMed, "<", r.x + 19, r.y + r.height / 2f + 1, Theme.TEXT);
        Ui.textCenter(game.batch, game.fontMed, ">", r.x + r.width - 19, r.y + r.height / 2f + 1, Theme.TEXT);
    }

    @Override public void resize(int width, int height) { viewport.update(width, height); }
    @Override public void hide() { Gdx.input.setInputProcessor(null); }
}
