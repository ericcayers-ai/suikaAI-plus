package dev.suika.game;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.viewport.FitViewport;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Schema-light settings panel (ROADMAP §VII): graphics, simulation, and AI-watch
 * options, each editable live with toggles, cyclers, and sliders. Frame-rate and
 * vsync changes apply to the window immediately.
 */
public final class SettingsScreen extends ScreenAdapter {

    private enum Kind { TOGGLE, CYCLE, SLIDER }

    private final class Row {
        String  section;            // optional header drawn above this row
        String  label;
        Kind    kind;
        Supplier<String> value;     // CYCLE display
        BooleanSupplier  on;        // TOGGLE state
        Runnable prev, next;        // CYCLE prev/next  (TOGGLE uses next)
        DoubleSupplier   frac;      // SLIDER fill 0..1
        DoubleConsumer   setFrac;   // SLIDER click
        final Rectangle area = new Rectangle();
    }

    private final SuikaGame game;
    private final GameSettings cfg;
    private final Function<SuikaGame, Screen> back;

    private final OrthographicCamera camera = new OrthographicCamera();
    private final FitViewport viewport;
    private final Vector3 touch = new Vector3();
    private float mx, my;

    private final List<Row> rows = new ArrayList<>();
    private final Rectangle backBtn = new Rectangle(Theme.VW / 2f - 130, 70, 260, 70);

    private static final float ROW_H       = 54f;
    private static final float ROW_GAP      = 6f;
    private static final float SECTION_GAP  = 50f;
    private static final float CTRL_W       = 250f;
    private static final float MARGIN_X     = 60f;
    private static final float TOP          = Theme.VH - 200f;

    public SettingsScreen(SuikaGame game, Function<SuikaGame, Screen> back) {
        this.game = game;
        this.cfg  = game.settings;
        this.back = back;
        viewport = new FitViewport(Theme.VW, Theme.VH, camera);
        camera.position.set(Theme.VW / 2f, Theme.VH / 2f, 0f);
        camera.update();
        buildRows();
    }

    private void buildRows() {
        // ---- Graphics ----
        cycle("GRAPHICS", "Frame rate", () -> cfg.fpsLabel(),
                () -> { cfg.fpsIndex = wrap(cfg.fpsIndex - 1, GameSettings.FPS_OPTIONS.length); cfg.applyDisplay(); },
                () -> { cfg.fpsIndex = wrap(cfg.fpsIndex + 1, GameSettings.FPS_OPTIONS.length); cfg.applyDisplay(); });
        toggle(null, "V-Sync", () -> cfg.vsync, () -> { cfg.vsync = !cfg.vsync; cfg.applyDisplay(); });
        toggle(null, "Smooth shading (glossy fruit)", () -> cfg.smoothShading, () -> cfg.smoothShading = !cfg.smoothShading);
        toggle(null, "Merge particles", () -> cfg.particles, () -> cfg.particles = !cfg.particles);
        toggle(null, "Drop guide line", () -> cfg.showGuide, () -> cfg.showGuide = !cfg.showGuide);
        toggle(null, "Tier number labels", () -> cfg.tierLabels, () -> cfg.tierLabels = !cfg.tierLabels);
        toggle(null, "Screen shake", () -> cfg.screenShake, () -> cfg.screenShake = !cfg.screenShake);

        // ---- Simulation ----
        cycle("SIMULATION", "Drop columns", () -> Integer.toString(cfg.actionBins()),
                () -> cfg.binIndex = wrap(cfg.binIndex - 1, GameSettings.BIN_OPTIONS.length),
                () -> cfg.binIndex = wrap(cfg.binIndex + 1, GameSettings.BIN_OPTIONS.length));
        toggle(null, "Seed", () -> cfg.randomSeed, () -> cfg.randomSeed = !cfg.randomSeed) // label shows mode via value
                .value = () -> cfg.randomSeed ? "Random" : "Fixed " + cfg.fixedSeed;

        // ---- Gameplay ----
        toggle("GAMEPLAY", "Immediate game over (no safety delay)",
                () -> cfg.immediateDeadline, () -> cfg.immediateDeadline = !cfg.immediateDeadline);

        // AI Watch configuration lives inside the AI game itself (side toggle per technique).
    }

    // --- row builders ---
    private Row add(String section, String label, Kind kind) {
        Row r = new Row(); r.section = section; r.label = label; r.kind = kind; rows.add(r); return r;
    }
    private Row toggle(String section, String label, BooleanSupplier on, Runnable act) {
        Row r = add(section, label, Kind.TOGGLE); r.on = on; r.next = act; return r;
    }
    private Row cycle(String section, String label, Supplier<String> value, Runnable prev, Runnable next) {
        Row r = add(section, label, Kind.CYCLE); r.value = value; r.prev = prev; r.next = next; return r;
    }
    private Row slider(String section, String label, DoubleSupplier frac, DoubleConsumer set) {
        Row r = add(section, label, Kind.SLIDER); r.frac = frac; r.setFrac = set; return r;
    }
    private static int wrap(int i, int n) { return Math.floorMod(i, n); }

    @Override
    public void show() {
        Gdx.input.setInputProcessor(new InputAdapter() {
            @Override public boolean touchDown(int sx, int sy, int p, int b) {
                camera.unproject(touch.set(sx, sy, 0), viewport.getScreenX(), viewport.getScreenY(), viewport.getScreenWidth(), viewport.getScreenHeight());
                handleClick(touch.x, touch.y); return true;
            }
            @Override public boolean mouseMoved(int sx, int sy) {
                camera.unproject(touch.set(sx, sy, 0), viewport.getScreenX(), viewport.getScreenY(), viewport.getScreenWidth(), viewport.getScreenHeight()); mx = touch.x; my = touch.y; return false;
            }
            @Override public boolean keyDown(int k) {
                if (k == Input.Keys.ESCAPE) { game.setScreen(back.apply(game)); return true; }
                return false;
            }
        });
    }

    private void handleClick(float x, float y) {
        if (backBtn.contains(x, y)) { game.setScreen(back.apply(game)); return; }
        for (Row r : rows) {
            if (!r.area.contains(x, y)) continue;
            switch (r.kind) {
                case TOGGLE -> r.next.run();
                case CYCLE  -> { if (x < r.area.x + r.area.width / 2f) r.prev.run(); else r.next.run(); }
                case SLIDER -> r.setFrac.accept(clamp01((x - r.area.x) / r.area.width));
            }
            return;
        }
    }

    private static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }

    @Override
    public void render(float delta) {
        Gdx.gl.glClearColor(0.05f, 0.06f, 0.10f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        viewport.apply();
        game.shapes.setProjectionMatrix(camera.combined);
        game.batch.setProjectionMatrix(camera.combined);

        ShapeRenderer s = game.shapes;
        float ctrlX = Theme.VW - MARGIN_X - CTRL_W;

        s.begin(ShapeRenderer.ShapeType.Filled);
        s.rect(0, 0, Theme.VW, Theme.VH, Theme.BG_BOTTOM, Theme.BG_BOTTOM, Theme.BG_TOP, Theme.BG_TOP);

        float y = TOP;
        for (Row r : rows) {
            if (r.section != null) y -= SECTION_GAP;     // reserve a band for the header
            float rowTop = y;
            float rowBot = y - ROW_H;
            r.area.set(ctrlX, rowBot + 9f, CTRL_W, ROW_H - 18f);

            // row background
            s.setColor(Theme.PANEL.r, Theme.PANEL.g, Theme.PANEL.b, 0.55f);
            Ui.fillRoundRect(s, MARGIN_X, rowBot + 4f, Theme.VW - 2 * MARGIN_X, ROW_H - 8f, 10f);

            switch (r.kind) {
                case TOGGLE -> Ui.toggle(s, r.area.x + r.area.width - 70f, r.area.y + 3f, 60f, r.area.height - 6f, r.on.getAsBoolean());
                case CYCLE  -> {
                    boolean hov = r.area.contains(mx, my);
                    s.setColor(Theme.PANEL_DEEP);
                    Ui.fillRoundRect(s, r.area.x, r.area.y, r.area.width, r.area.height, 8f);
                    s.setColor(hov && mx < r.area.x + r.area.width / 2f ? Theme.ACCENT_BLUE : Theme.PANEL_EDGE);
                    Ui.fillRoundRect(s, r.area.x + 4f, r.area.y + 4f, 32f, r.area.height - 8f, 6f);
                    s.setColor(hov && mx >= r.area.x + r.area.width / 2f ? Theme.ACCENT_BLUE : Theme.PANEL_EDGE);
                    Ui.fillRoundRect(s, r.area.x + r.area.width - 36f, r.area.y + 4f, 32f, r.area.height - 8f, 6f);
                }
                case SLIDER -> {
                    s.setColor(Theme.PANEL_DEEP);
                    Ui.fillRoundRect(s, r.area.x, r.area.y + r.area.height / 2f - 5f, r.area.width, 10f, 5f);
                    float f = (float) r.frac.getAsDouble();
                    s.setColor(Theme.ACCENT_2);
                    Ui.fillRoundRect(s, r.area.x, r.area.y + r.area.height / 2f - 5f, r.area.width * f, 10f, 5f);
                    s.setColor(0.97f, 0.98f, 1f, 1f);
                    s.circle(r.area.x + r.area.width * f, r.area.y + r.area.height / 2f, 11f, 18);
                }
            }
            y = rowBot - ROW_GAP;
        }

        Ui.button(s, backBtn, Theme.ACCENT, backBtn.contains(mx, my), true);
        s.end();

        // ---- text pass (identical layout maths) ----
        game.batch.begin();
        Ui.textCenter(game.batch, game.fontBig, "SETTINGS", Theme.VW / 2f, Theme.VH - 120f, Theme.TEXT);

        y = TOP;
        for (Row r : rows) {
            if (r.section != null) {
                Ui.text(game.batch, game.fontMed, r.section, MARGIN_X, y - 14f, Theme.GOLD);
                y -= SECTION_GAP;
            }
            float rowTop = y;
            float rowBot = y - ROW_H;
            float labelY = rowBot + ROW_H / 2f + 8f;
            Ui.text(game.batch, game.font, r.label, MARGIN_X + 16f, labelY, Theme.TEXT);
            if (r.kind == Kind.CYCLE) {
                Ui.textCenter(game.batch, game.font, r.value.get(),
                        r.area.x + r.area.width / 2f, r.area.y + r.area.height / 2f, Theme.TEXT);
                Ui.textCenter(game.batch, game.fontMed, "<", r.area.x + 20f, r.area.y + r.area.height / 2f + 1f, Theme.TEXT);
                Ui.textCenter(game.batch, game.fontMed, ">", r.area.x + r.area.width - 20f, r.area.y + r.area.height / 2f + 1f, Theme.TEXT);
            } else if (r.kind == Kind.TOGGLE && r.value != null) {
                Ui.textRight(game.batch, game.fontSmall, r.value.get(),
                        r.area.x + r.area.width - 80f, labelY - 2f, Theme.TEXT_DIM);
            }
            y = rowBot - ROW_GAP;
        }

        Ui.textCenter(game.batch, game.fontMed, "BACK", Theme.VW / 2f, backBtn.y + 35f, Theme.TEXT);
        game.batch.end();
    }

    @Override public void resize(int width, int height) { viewport.update(width, height); }
    @Override public void hide() { Gdx.input.setInputProcessor(null); }
}
