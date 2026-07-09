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

    private enum Kind { TOGGLE, CYCLE, SLIDER, BUTTON }

    private final class Row {
        String  section;            // optional header drawn above this row
        String  label;
        Kind    kind;
        Supplier<String> value;     // CYCLE display
        Supplier<String> value2;    // optional secondary status line (BUTTON rows)
        BooleanSupplier  on;        // TOGGLE state
        Runnable prev, next;        // CYCLE prev/next  (TOGGLE uses next)
        DoubleSupplier   frac;      // SLIDER fill 0..1
        DoubleConsumer   setFrac;   // SLIDER click
        final Rectangle area = new Rectangle();
        /** 0 = use the standard {@link #ROW_H}. Sliders need extra headroom for the
         *  value readout drawn above the bar (see {@link #rowHeight}). */
        float heightOverride = 0f;
        /** When non-null and {@link GameSettings#customValueEntry} is on, clicking this
         *  row opens the type-a-number overlay instead of cycling/sliding. */
        NumericSpec numeric;
    }

    /** Describes how a row's value can be typed exactly in the custom-entry overlay. */
    private static final class NumericSpec {
        final double min, max;
        final boolean integer;
        final DoubleSupplier current;   // present value, to pre-fill the field
        final DoubleConsumer apply;     // called with the clamped typed value
        final String unit;              // e.g. " FPS", "%", "" — shown after the number
        NumericSpec(double min, double max, boolean integer,
                    DoubleSupplier current, DoubleConsumer apply, String unit) {
            this.min = min; this.max = max; this.integer = integer;
            this.current = current; this.apply = apply; this.unit = unit;
        }
    }

    // ---- Type-a-number overlay (opened when Custom values is on) ----
    private boolean numEntryOpen = false;
    private Row     numEntryRow;
    private final StringBuilder numEntryBuf = new StringBuilder();
    private final Rectangle numOkBtn     = new Rectangle();
    private final Rectangle numCancelBtn = new Rectangle();

    /** Effective row height — sliders get extra vertical room so their value label
     *  (drawn above the bar) never crowds the row above/below it (was the "Max GPU
     *  utilization" formatting bug: the "100%" readout sat right at the row's edge). */
    private float rowHeight(Row r) { return r.heightOverride > 0f ? r.heightOverride : ROW_H; }

    private final SuikaGame game;
    private final GameSettings cfg;
    private final Function<SuikaGame, Screen> back;

    private final OrthographicCamera camera = new OrthographicCamera();
    private final FitViewport viewport;
    private final Vector3 touch = new Vector3();
    private float mx, my;

    private final List<Row> rows = new ArrayList<>();
    private final Rectangle backBtn = new Rectangle(Theme.VW / 2f - 130, 70, 260, 70);
    private volatile String installStatus = PythonSetup.isReady()
            ? "Ready  ·  venv" : "Not installed";
    private volatile boolean installing = false;

    private static final float ROW_H       = 54f;
    private static final float ROW_GAP      = 6f;
    private static final float SECTION_GAP  = 50f;
    private static final float CTRL_W       = 250f;
    private static final float MARGIN_X     = 60f;
    private static final float TOP          = Theme.VH - 200f;

    // Scrollable list — the settings list has grown past a single screenful (adding
    // "Bouncy fruit" + "Max GPU utilization" once pushed later rows behind the fixed
    // BACK button with no way to reach them). LIST_BOT sits just above BACK with a
    // small margin; anything scrolled above LIST_TOP or below LIST_BOT is masked out,
    // mirroring AiPlaygroundScreen's technique-list scrolling.
    private static final float LIST_TOP = TOP;
    private static final float LIST_BOT = 170f;
    private float scroll = 0f;

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
        // ---- Display (§VI: resolution/fullscreen/UI scale, persisted across launches) ----
        cycle("DISPLAY", "Window height", () -> cfg.windowHeight() + "p",
                () -> { cfg.resHeightIndex = wrap(cfg.resHeightIndex - 1, GameSettings.RES_HEIGHTS.length);
                    cfg.applyWindowMode(); SettingsPersistence.save(cfg); },
                () -> { cfg.resHeightIndex = wrap(cfg.resHeightIndex + 1, GameSettings.RES_HEIGHTS.length);
                    cfg.applyWindowMode(); SettingsPersistence.save(cfg); });
        toggle(null, "Fullscreen", () -> cfg.fullscreen,
                () -> { cfg.fullscreen = !cfg.fullscreen; cfg.applyWindowMode(); SettingsPersistence.save(cfg); });
        cycle(null, "UI scale", cfg::uiScaleLabel,
                () -> { cfg.uiScaleIndex = wrap(cfg.uiScaleIndex - 1, GameSettings.UI_SCALE_OPTIONS.length);
                    game.regenerateFonts(); SettingsPersistence.save(cfg); },
                () -> { cfg.uiScaleIndex = wrap(cfg.uiScaleIndex + 1, GameSettings.UI_SCALE_OPTIONS.length);
                    game.regenerateFonts(); SettingsPersistence.save(cfg); });

        // ---- Graphics ----
        Row fps = cycle("GRAPHICS", "Frame rate", () -> cfg.fpsLabel(),
                () -> { cfg.customFps = -1; cfg.fpsIndex = wrap(cfg.fpsIndex - 1, GameSettings.FPS_OPTIONS.length); cfg.applyDisplay(); },
                () -> { cfg.customFps = -1; cfg.fpsIndex = wrap(cfg.fpsIndex + 1, GameSettings.FPS_OPTIONS.length); cfg.applyDisplay(); });
        fps.numeric = new NumericSpec(0, 500, true,
                () -> cfg.targetFps(), v -> { cfg.customFps = (int) Math.round(v); cfg.applyDisplay(); }, " FPS");
        toggle(null, "V-Sync", () -> cfg.vsync, () -> { cfg.vsync = !cfg.vsync; cfg.applyDisplay(); });
        toggle(null, "Smooth shading (glossy fruit)", () -> cfg.smoothShading, () -> cfg.smoothShading = !cfg.smoothShading);
        toggle(null, "Merge particles", () -> cfg.particles, () -> cfg.particles = !cfg.particles);
        toggle(null, "Drop guide line", () -> cfg.showGuide, () -> cfg.showGuide = !cfg.showGuide);
        toggle(null, "Tier number labels", () -> cfg.tierLabels, () -> cfg.tierLabels = !cfg.tierLabels);
        toggle(null, "Screen shake", () -> cfg.screenShake, () -> cfg.screenShake = !cfg.screenShake);

        // ---- Simulation ----
        Row bins = cycle("SIMULATION", "Drop columns", cfg::binsLabel,
                () -> { cfg.customBins = -1; cfg.binIndex = wrap(cfg.binIndex - 1, GameSettings.BIN_OPTIONS.length); },
                () -> { cfg.customBins = -1; cfg.binIndex = wrap(cfg.binIndex + 1, GameSettings.BIN_OPTIONS.length); });
        bins.numeric = new NumericSpec(8, 256, true,
                () -> cfg.actionBins(), v -> cfg.customBins = (int) Math.round(v), " cols");
        Row seed = toggle(null, "Seed", () -> cfg.randomSeed, () -> cfg.randomSeed = !cfg.randomSeed);
        seed.value = () -> cfg.randomSeed ? "Random" : "Fixed " + cfg.fixedSeed;
        // Typing a seed implies a fixed seed — the overlay flips randomSeed off on apply.
        seed.numeric = new NumericSpec(0, Long.MAX_VALUE, true,
                () -> cfg.fixedSeed, v -> { cfg.fixedSeed = (long) v; cfg.randomSeed = false; }, "");

        // ---- Gameplay ----
        cycle("GAMEPLAY", "RT Lab physics", () -> cfg.rt3dPhysics ? "3D (true 3D)" : "2D (classic)",
                () -> cfg.rt3dPhysics = !cfg.rt3dPhysics,
                () -> cfg.rt3dPhysics = !cfg.rt3dPhysics);

        // ---- Experimental gameplay variants ----
        toggle("EXPERIMENTAL", "Instant fail (no safety delay)",
                () -> cfg.immediateDeadline, () -> { cfg.immediateDeadline = !cfg.immediateDeadline; cfg.applyPhysics(); });
        toggle(null, "Bouncy fruit (no instant settle)",
                () -> cfg.bounceEnabled, () -> { cfg.bounceEnabled = !cfg.bounceEnabled; cfg.applyPhysics(); });

        // ---- AI Environment ----
        cycle("AI ENVIRONMENT", "Python env", () -> installStatus, null, null);
        button(null, "Download AI GPU deps",
                () -> PythonSetup.isReady() ? "REINSTALL" : installing ? "WORKING…" : "SETUP",
                this::startInstall);

        // Single first-class compute-mode selector — GPU (Python/CUDA, app-wide) vs
        // CPU (JVM). Replaces the old two separate "Prefer GPU" / "Force CPU-only"
        // toggles; applyComputeMode() derives the legacy flags so nothing downstream
        // breaks. The value line reports what will actually happen on this machine.
        cycle(null, "Compute mode", this::computeModeHint,
                () -> toggleComputeMode(), () -> toggleComputeMode());
        Row gpuUtil = slider(null, "Max GPU utilization (Python training)",
                () -> (cfg.gpuUtilPercent - 10) / 90.0,
                f -> cfg.gpuUtilPercent = (int) (Math.round((10 + clamp01(f) * 90) / 5.0) * 5));
        gpuUtil.value = () -> cfg.gpuUtilPercent + "%";
        gpuUtil.numeric = new NumericSpec(10, 100, true,
                () -> cfg.gpuUtilPercent, v -> cfg.gpuUtilPercent = (int) Math.round(v), "%");

        // ---- Input / entry ----
        toggle("INPUT", "Custom values (type exact numbers)",
                () -> cfg.customValueEntry,
                () -> { cfg.customValueEntry = !cfg.customValueEntry; SettingsPersistence.save(cfg); })
                .value = () -> cfg.customValueEntry ? "On — click a number row" : "Off";
        toggle(null, "Stuck-run watchdog (back out after 10s)",
                () -> cfg.watchdogEnabled,
                () -> { cfg.watchdogEnabled = !cfg.watchdogEnabled; SettingsPersistence.save(cfg); });

        // ---- Presets ----
        Row calib = button("PRESETS", "Calibrate presets for this machine",
                () -> PresetCalibration.running() ? PresetCalibration.progressPct() + "%"
                        : PresetCalibration.calibrated() ? "RECALIBRATE" : "CALIBRATE",
                () -> { if (!PresetCalibration.running()) PresetCalibration.calibrateAsync(); });
        calib.value2 = PresetCalibration::statusLabel;
        calib.heightOverride = ROW_H + 26f;

        // ---- Saves ----
        Row autosave = cycle("SAVES", "Autosave (AI progress -> slot 1)", cfg::autosaveLabel,
                () -> { cfg.customAutosaveMinutes = -1; cfg.autosaveIndex = wrap(cfg.autosaveIndex - 1, GameSettings.AUTOSAVE_MINUTES.length); SettingsPersistence.save(cfg); },
                () -> { cfg.customAutosaveMinutes = -1; cfg.autosaveIndex = wrap(cfg.autosaveIndex + 1, GameSettings.AUTOSAVE_MINUTES.length); SettingsPersistence.save(cfg); });
        autosave.numeric = new NumericSpec(0, 240, true,
                () -> cfg.autosaveMinutes(), v -> { cfg.customAutosaveMinutes = (int) Math.round(v); SettingsPersistence.save(cfg); }, " min");
    }

    private String computeModeHint() {
        if (!cfg.gpuMode) return "CPU (JVM)";
        Boolean gpu = GpuProbe.available();
        String dev = GpuProbe.deviceName();
        if (gpu == null) return "GPU — probing…";
        if (Boolean.TRUE.equals(gpu)) return "GPU · " + (dev != null ? fit(dev) : "CUDA");
        return "GPU — no CUDA, CPU fallback";
    }

    private void toggleComputeMode() {
        cfg.gpuMode = !cfg.gpuMode;
        cfg.applyComputeMode();
        SettingsPersistence.save(cfg);
    }

    /** Opens the type-a-number overlay pre-filled with the row's current value. */
    private void openNumEntry(Row r) {
        numEntryOpen = true;
        numEntryRow = r;
        numEntryBuf.setLength(0);
        double cur = r.numeric.current.getAsDouble();
        numEntryBuf.append(r.numeric.integer ? Long.toString((long) cur)
                : trimFloat(cur));
    }

    private static String trimFloat(double v) {
        String s = String.format(java.util.Locale.US, "%.4f", v);
        // strip trailing zeros / dot so the field reads cleanly
        if (s.contains(".")) { s = s.replaceAll("0+$", ""); if (s.endsWith(".")) s = s.substring(0, s.length() - 1); }
        return s;
    }

    private void commitNumEntry() {
        if (numEntryRow == null || numEntryRow.numeric == null) { numEntryOpen = false; return; }
        NumericSpec spec = numEntryRow.numeric;
        try {
            double v = Double.parseDouble(numEntryBuf.toString().trim());
            v = Math.max(spec.min, Math.min(spec.max, v));
            if (spec.integer) v = Math.round(v);
            spec.apply.accept(v);
        } catch (NumberFormatException ignored) {
            // invalid entry — just close without applying
        }
        numEntryOpen = false;
        numEntryRow = null;
    }

    private static String fit(String msg) {
        return msg.length() > 24 ? msg.substring(0, 23) + "…" : msg;
    }

    private void startInstall() {
        if (installing) return;
        installing = true;
        installStatus = "Starting… 0%";
        PythonSetup.installAsync(
                msg -> {
                    boolean done = msg.startsWith("Error")
                            || msg.startsWith("Warning") || msg.startsWith("Python not found");
                    installStatus = done ? fit(msg) : "[" + PythonSetup.installPct() + "%] " + fit(msg);
                    if (done) installing = false;
                },
                () -> {
                    GpuProbe.forceReprobe();
                    cfg.gpuMode = true;
                    cfg.applyComputeMode();   // sets preferGpu=true, jvmCpuOnly=false
                    SettingsPersistence.save(cfg);
                    installing = false;
                    try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                    AppRestart.restart();
                });
    }

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
        Row r = add(section, label, Kind.SLIDER); r.frac = frac; r.setFrac = set;
        r.heightOverride = ROW_H + 40f;
        return r;
    }
    private Row button(String section, String label, Supplier<String> btnText, Runnable act) {
        Row r = add(section, label, Kind.BUTTON); r.next = act; r.value = btnText; return r;
    }
    private static int wrap(int i, int n) { return Math.floorMod(i, n); }

    @Override
    public void show() {
        Gdx.input.setInputProcessor(new InputAdapter() {
            @Override public boolean touchDown(int sx, int sy, int p, int b) {
                camera.unproject(touch.set(sx, sy, 0), viewport.getScreenX(), viewport.getScreenY(), viewport.getScreenWidth(), viewport.getScreenHeight());
                if (numEntryOpen) { handleNumEntryClick(touch.x, touch.y); return true; }
                handleClick(touch.x, touch.y); return true;
            }
            @Override public boolean mouseMoved(int sx, int sy) {
                camera.unproject(touch.set(sx, sy, 0), viewport.getScreenX(), viewport.getScreenY(), viewport.getScreenWidth(), viewport.getScreenHeight()); mx = touch.x; my = touch.y; return false;
            }
            @Override public boolean scrolled(float ax, float ay) {
                if (numEntryOpen) return true;
                scroll = Math.max(0f, Math.min(scroll + ay * 46f, maxScroll()));
                return true;
            }
            @Override public boolean keyDown(int k) {
                if (numEntryOpen) {
                    if (k == Input.Keys.ESCAPE) { numEntryOpen = false; numEntryRow = null; return true; }
                    if (k == Input.Keys.ENTER || k == Input.Keys.NUMPAD_ENTER) { commitNumEntry(); return true; }
                    if (k == Input.Keys.BACKSPACE && numEntryBuf.length() > 0) {
                        numEntryBuf.setLength(numEntryBuf.length() - 1); return true;
                    }
                    return true; // swallow everything else while typing
                }
                if (k == Input.Keys.ESCAPE) { game.setScreen(back.apply(game)); return true; }
                return false;
            }
            @Override public boolean keyTyped(char c) {
                if (!numEntryOpen) return false;
                if ((c >= '0' && c <= '9')
                        || (c == '.' && numEntryBuf.indexOf(".") < 0)
                        || (c == '-' && numEntryBuf.length() == 0)) {
                    numEntryBuf.append(c);
                }
                return true;
            }
        });
    }

    private float contentHeight() {
        float y = 0f;
        for (Row r : rows) {
            if (r.section != null) y -= SECTION_GAP;
            y -= rowHeight(r);
            y -= ROW_GAP;
        }
        return -y;
    }

    private float maxScroll() {
        return Math.max(0f, contentHeight() - (LIST_TOP - LIST_BOT));
    }

    public void scrollToBottomForCapture() { scroll = maxScroll(); }

    private void handleClick(float x, float y) {
        if (backBtn.contains(x, y)) { game.setScreen(back.apply(game)); return; }
        for (Row r : rows) {
            if (!r.area.contains(x, y)) continue;
            if (r.area.y + r.area.height > LIST_TOP || r.area.y < LIST_BOT) continue;
            // Custom-values mode: a numeric row opens the type-a-number overlay instead
            // of cycling/sliding (the arrows still work when the mode is off).
            if (cfg.customValueEntry && r.numeric != null) { openNumEntry(r); return; }
            switch (r.kind) {
                case TOGGLE -> r.next.run();
                case CYCLE  -> { if (r.prev != null && x < r.area.x + r.area.width / 2f) r.prev.run(); else if (r.next != null) r.next.run(); }
                case SLIDER -> r.setFrac.accept(clamp01((x - r.area.x) / r.area.width));
                case BUTTON -> { if (r.next != null) r.next.run(); }
            }
            return;
        }
    }

    private void handleNumEntryClick(float x, float y) {
        if (numOkBtn.contains(x, y)) { commitNumEntry(); return; }
        if (numCancelBtn.contains(x, y)) { numEntryOpen = false; numEntryRow = null; return; }
        // Click outside the modal card cancels.
        float mw = 520f, mh = 250f;
        float m0x = Theme.VW / 2f - mw / 2f, m0y = Theme.VH / 2f - mh / 2f;
        if (x < m0x || x > m0x + mw || y < m0y || y > m0y + mh) { numEntryOpen = false; numEntryRow = null; }
    }

    private static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }

    private String ellipsize(String text, com.badlogic.gdx.graphics.g2d.BitmapFont font, float maxW) {
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
        s.end();

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

        float y = LIST_TOP + scroll;
        for (Row r : rows) {
            if (r.section != null) y -= SECTION_GAP;
            float rh = rowHeight(r);
            float rowTop = y;
            float rowBot = y - rh;
            r.area.set(ctrlX, rowBot + 9f, CTRL_W, rh - 18f);

            s.setColor(Theme.PANEL.r, Theme.PANEL.g, Theme.PANEL.b, 0.55f);
            Ui.fillRoundRect(s, MARGIN_X, rowBot + 4f, Theme.VW - 2 * MARGIN_X, rh - 8f, 10f);

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
                    float barY = r.area.y + 8f;
                    s.setColor(Theme.PANEL_DEEP);
                    Ui.fillRoundRect(s, r.area.x, barY - 5f, r.area.width, 10f, 5f);
                    float f = (float) r.frac.getAsDouble();
                    s.setColor(Theme.ACCENT_2);
                    Ui.fillRoundRect(s, r.area.x, barY - 5f, r.area.width * f, 10f, 5f);
                    s.setColor(0.97f, 0.98f, 1f, 1f);
                    s.circle(r.area.x + r.area.width * f, barY, 11f, 18);
                }
                case BUTTON -> {
                    boolean hov = r.area.contains(mx, my);
                    s.setColor(hov ? Theme.ACCENT_BLUE : Theme.PANEL_EDGE);
                    Ui.fillRoundRect(s, r.area.x, r.area.y, r.area.width, r.area.height, 8f);
                }
            }
            y = rowBot - ROW_GAP;
        }
        s.end();
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);

        s.begin(ShapeRenderer.ShapeType.Filled);
        Ui.button(s, backBtn, Theme.ACCENT, backBtn.contains(mx, my), true);
        s.end();

        // ---- text pass ----
        game.batch.begin();
        Ui.textCenter(game.batch, game.fontBig, "SETTINGS", Theme.VW / 2f, Theme.VH - 120f, Theme.TEXT);

        game.batch.flush();
        Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
        Gdx.gl.glScissor(
                (int) scX,
                (int) (scY + (LIST_BOT / Theme.VH) * scH),
                (int) scW,
                (int) (((LIST_TOP - LIST_BOT) / Theme.VH) * scH)
        );

        y = LIST_TOP + scroll;
        for (Row r : rows) {
            if (r.section != null) {
                Ui.text(game.batch, game.fontMed, r.section, MARGIN_X, y - 14f, Theme.GOLD);
                y -= SECTION_GAP;
            }
            float rh = rowHeight(r);
            float rowTop = y;
            float rowBot = y - rh;

            float labelY = r.kind == Kind.SLIDER ? r.area.y + r.area.height - 10f : rowBot + rh / 2f + 8f;

            // FIX: Prevent text overlap occlusion bug by dynamically truncating long labels
            float maxLabelW = r.area.x - (MARGIN_X + 16f) - 10f;
            String displayedLabel = ellipsize(r.label, game.font, maxLabelW);

            Ui.text(game.batch, game.font, displayedLabel, MARGIN_X + 16f, labelY, Theme.TEXT);
            if (r.kind == Kind.CYCLE) {
                Ui.textCenter(game.batch, game.fontSmall, r.value.get(),
                        r.area.x + r.area.width / 2f, r.area.y + r.area.height / 2f, Theme.TEXT);
                if (r.prev != null) Ui.textCenter(game.batch, game.fontMed, "<", r.area.x + 20f, r.area.y + r.area.height / 2f + 1f, Theme.TEXT);
                if (r.next != null) Ui.textCenter(game.batch, game.fontMed, ">", r.area.x + r.area.width - 20f, r.area.y + r.area.height / 2f + 1f, Theme.TEXT);
            } else if (r.kind == Kind.TOGGLE && r.value != null) {
                Ui.textRight(game.batch, game.fontSmall, r.value.get(),
                        r.area.x + r.area.width - 80f, labelY - 2f, Theme.TEXT_DIM);
            } else if (r.kind == Kind.SLIDER && r.value != null) {
                float barY = r.area.y + 8f;
                Ui.textCenter(game.batch, game.fontSmall, r.value.get(),
                        r.area.x + r.area.width / 2f, barY + 22f, Theme.TEXT);
            } else if (r.kind == Kind.BUTTON) {
                Ui.textCenter(game.batch, game.fontSmall, r.value.get(),
                        r.area.x + r.area.width / 2f, r.area.y + r.area.height / 2f, Theme.TEXT);
                if (r.value2 != null)
                    Ui.text(game.batch, game.fontSmall, r.value2.get(),
                            MARGIN_X + 16f, labelY - 22f, Theme.TEXT_DIM);
            }
            y = rowBot - ROW_GAP;
        }

        game.batch.flush();
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);

        Ui.textCenter(game.batch, game.fontMed, "BACK", Theme.VW / 2f, backBtn.y + 35f, Theme.TEXT);
        game.batch.end();

        if (numEntryOpen) drawNumEntry();
    }

    private void drawNumEntry() {
        if (numEntryRow == null || numEntryRow.numeric == null) { numEntryOpen = false; return; }
        NumericSpec spec = numEntryRow.numeric;
        float mw = 520f, mh = 250f;
        float m0x = Theme.VW / 2f - mw / 2f, m0y = Theme.VH / 2f - mh / 2f;
        numOkBtn.set(m0x + mw / 2f + 12f, m0y + 24f, 150f, 48f);
        numCancelBtn.set(m0x + mw / 2f - 162f, m0y + 24f, 150f, 48f);
        float fieldX = m0x + 30f, fieldY = m0y + mh - 128f, fieldW = mw - 60f, fieldH = 52f;

        ShapeRenderer s = game.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);
        s.setColor(0f, 0f, 0f, 0.82f);
        s.rect(0, 0, Theme.VW, Theme.VH);
        s.setColor(0.08f, 0.09f, 0.13f, 1f);
        Ui.fillRoundRect(s, m0x, m0y, mw, mh, 16);
        Ui.panel(s, m0x, m0y, mw, mh, 16, Theme.PANEL_DEEP, Theme.PANEL_EDGE);
        // input field
        s.setColor(Theme.PANEL_DEEP);
        Ui.fillRoundRect(s, fieldX, fieldY, fieldW, fieldH, 8);
        s.setColor(Theme.ACCENT_BLUE);
        Ui.fillRoundRect(s, fieldX, fieldY, fieldW, 3f, 2f);
        // buttons
        s.setColor(numOkBtn.contains(mx, my) ? Theme.ACCENT_2 : Theme.PANEL_EDGE);
        Ui.fillRoundRect(s, numOkBtn.x, numOkBtn.y, numOkBtn.width, numOkBtn.height, 10);
        s.setColor(numCancelBtn.contains(mx, my) ? Theme.ACCENT : Theme.PANEL_EDGE);
        Ui.fillRoundRect(s, numCancelBtn.x, numCancelBtn.y, numCancelBtn.width, numCancelBtn.height, 10);
        s.end();

        game.batch.begin();
        Ui.textCenter(game.batch, game.fontMed, numEntryRow.label, Theme.VW / 2f, m0y + mh - 34f, Theme.TEXT);
        String rangeHint = spec.integer
                ? String.format(java.util.Locale.US, "range %d – %s", (long) spec.min,
                        spec.max >= Long.MAX_VALUE ? "∞" : Long.toString((long) spec.max))
                : String.format(java.util.Locale.US, "range %.2f – %.2f", spec.min, spec.max);
        Ui.textCenter(game.batch, game.fontSmall, rangeHint, Theme.VW / 2f, m0y + mh - 62f, Theme.TEXT_DIM);
        String shown = numEntryBuf.length() == 0 ? "_" : numEntryBuf.toString();
        Ui.text(game.batch, game.fontMed, shown + spec.unit, fieldX + 16f, fieldY + fieldH / 2f + 8f, Theme.TEXT);
        Ui.textCenter(game.batch, game.fontSmall, "Enter to apply · Esc to cancel", Theme.VW / 2f, fieldY - 18f, Theme.TEXT_FAINT);
        Ui.textCenter(game.batch, game.fontSmall, "APPLY", numOkBtn.x + numOkBtn.width / 2f, numOkBtn.y + numOkBtn.height / 2f - 5f, Theme.TEXT);
        Ui.textCenter(game.batch, game.fontSmall, "CANCEL", numCancelBtn.x + numCancelBtn.width / 2f, numCancelBtn.y + numCancelBtn.height / 2f - 5f, Theme.TEXT);
        game.batch.end();
    }

    @Override public void resize(int width, int height) { viewport.update(width, height); }
    @Override public void hide() { Gdx.input.setInputProcessor(null); }
}