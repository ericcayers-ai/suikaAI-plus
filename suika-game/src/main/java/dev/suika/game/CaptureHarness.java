package dev.suika.game;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.utils.ScreenUtils;
import dev.suika.core.Fruit;
import dev.suika.core.GameState;
import dev.suika.core.PhysicsConfig;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Headless-ish QA / debug harness: drives the real screens through a scripted timeline
 * and writes PNG screenshots of the live framebuffer. Activated by
 * {@code DesktopLauncher} when the {@code suika.capture.dir} system property is set;
 * never part of normal play.
 *
 * <p>Two modes, chosen with {@code -Dsuika.capture.mode}:
 *
 * <p><b>showcase</b> (default) — the curated tour: menu, settings, playground,
 * infocard, hotswap, saves, a 16× elite grid, then one screenshot per
 * {@link AiTechnique}, plus self-play / ghost / human-play / game-over views. This is
 * the "did the UI render" pass.
 *
 * <p><b>matrix</b> — a SELECTABLE debug sweep: the cartesian product of chosen
 * techniques × drop-column counts × speed multipliers × dwell durations (× each
 * technique's own hyper-parameter options, when {@code params=on}). Every cell launches
 * its control center, lets it run for the chosen duration, screenshots it, and inspects
 * the live board for anomalies (out-of-bounds fruit, NaNs, stalls). A {@code report.txt}
 * summarising every cell and any anomalies is written at the end. This is the "does it
 * actually behave, at every setting, at every depth into the game" pass.
 *
 * <p>Matrix selection (all optional, sensible defaults; comma-separated lists):
 * <pre>
 *   -Dsuika.capture.mode=matrix
 *   -Dsuika.capture.techniques=all        all | ensembles | planning | evolution |
 *                                         imitation | python | baselines | &lt;csv of ids&gt;
 *   -Dsuika.capture.columns=32            drop-column counts, e.g. 16,32,64,128
 *   -Dsuika.capture.speeds=1              speed multipliers, e.g. 1,16,256,1024
 *   -Dsuika.capture.durations=4           seconds into the game per shot, e.g. 1,10,30
 *   -Dsuika.capture.elites=1              evolution elite-view counts, e.g. 1,4,16
 *   -Dsuika.capture.params=off            off | on — also sweep each technique's own
 *                                         hyper-parameter options (rollouts/pop/return/lr)
 *   -Dsuika.capture.max=240               hard cap on total cells (safety)
 * </pre>
 */
public final class CaptureHarness implements ApplicationListener {

    private final String outDir;
    private SuikaGame game;

    /** Time spent in the CURRENT stage (reset by {@link #nextStage()}), not global. */
    private float stageT = 0f;
    private int stage = 0;

    private final boolean matrixMode;

    // ---- showcase state ----
    private AiPlaygroundScreen playground;
    private ControlCenterScreen cc;
    private SettingsScreen settings;
    private SuikaScreen humanPlay;

    private static final AiTechnique[] TECHS = AiTechnique.values();
    private int techIndex = 0;

    // Imitation auto-play: keep dropping (at chute-clear checkpoints, like a fast real
    // player) until game 1 ends and training kicks in, so the captured dual board shows
    // real numbers instead of an all-zero "still watching" state.
    private static final double[] AUTO_DROP_XS = {2.0, 5.0, 8.0, 3.0, 7.0, 4.5, 6.0, 1.5, 8.5, 3.5, 2.5, 6.5};
    private int   autoDropCount;
    private float autoDropTimer;

    // ---- matrix state ----
    private List<Job> jobs = new ArrayList<>();
    private int jobIndex = 0;
    private boolean jobLaunched = false;
    private final StringBuilder report = new StringBuilder();
    private int anomalyCount = 0;

    // Per-technique hyper-parameter option tables (mirror AiPlaygroundScreen / ControlCenterScreen).
    private static final int[]    ROLLOUTS = {40, 80, 150, 300, 600, 1200, 2400};
    private static final int[]    POP      = {16, 24, 40, 64, 128, 256, 512, 1000};
    private static final int[]    RETURNS  = {1000, 2000, 4000};
    private static final double[] LRS      = {1e-3, 3e-3, 1e-2};

    /** One cell of the debug matrix. {@code uiScaleIndex} is §12's display axis —
     *  the one display setting that can vary between cells within a single running
     *  process (a font regen, not a window resize/recreate); resolution/fullscreen
     *  are process-wide (see DesktopLauncher's {@code -Dsuika.capture.land}) and
     *  swept by launching the harness once per value instead — see the CI job's
     *  two separate portrait/landscape invocations in .github/workflows/ci.yml. */
    private record Job(AiTechnique tech, int bins, float speed, float dwell,
                       int eliteViews, String paramName, double paramVal, String paramLabel,
                       int uiScaleIndex) {
        String fileName() {
            String base = String.format(Locale.ROOT, "mtx_%s_c%d_s%s_d%s",
                    tech.id, bins, speedTag(speed), durTag(dwell));
            if (paramName != null) base += "_" + paramLabel;
            if (uiScaleIndex != 1) base += "_ui" + GameSettings.UI_SCALE_OPTIONS[uiScaleIndex];
            return base.replaceAll("[^A-Za-z0-9_.-]", "") + ".png";
        }
    }

    public CaptureHarness(String outDir) {
        this.outDir = outDir;
        this.matrixMode = "matrix".equalsIgnoreCase(System.getProperty("suika.capture.mode", "showcase"));
    }

    @Override public void create() {
        game = new SuikaGame();
        game.create();
        // -Dsuika.capture.uiscale=<index into GameSettings.UI_SCALE_OPTIONS> — QA hook
        // to torture-test every screen at a non-default text size in one pass, instead
        // of writing a dedicated stage per scale level.
        String uiScale = System.getProperty("suika.capture.uiscale");
        if (uiScale != null) {
            game.settings.uiScaleIndex = Integer.parseInt(uiScale.trim());
            game.regenerateFonts();
        }
        if (matrixMode) buildMatrix();
    }

    @Override public void resize(int width, int height) { game.resize(width, height); }

    private void nextStage() { stage++; stageT = 0f; }

    @Override
    public void render() {
        float dt = Gdx.graphics.getDeltaTime();
        stageT += dt;
        game.render();
        if (matrixMode) renderMatrix(dt); else renderShowcase(dt);
    }

    // =====================================================================
    // Matrix mode
    // =====================================================================

    private void buildMatrix() {
        List<AiTechnique> techs = parseTechniques(System.getProperty("suika.capture.techniques", "all"));
        int[]   cols  = parseInts(System.getProperty("suika.capture.columns", "32"), new int[]{32});
        float[] speeds= parseFloats(System.getProperty("suika.capture.speeds", "1"), new float[]{1f});
        float[] durs  = parseFloats(System.getProperty("suika.capture.durations", "4"), new float[]{4f});
        int[]   elites= parseInts(System.getProperty("suika.capture.elites", "1"), new int[]{1});
        boolean sweepParams = "on".equalsIgnoreCase(System.getProperty("suika.capture.params", "off"));
        // §12: display axis — indices into GameSettings.UI_SCALE_OPTIONS (0=90%,
        // 1=100% default, 2=110%, 3=120%). Defaults to just 100% so existing
        // -Dsuika.capture.* invocations don't silently multiply their cell count;
        // opt in with e.g. -Dsuika.capture.uiscales=0,1,2,3 for a full sweep.
        int[] uiScales = parseInts(System.getProperty("suika.capture.uiscales", "1"), new int[]{1});
        int max = parseInt(System.getProperty("suika.capture.max", "240"), 240);

        int dropped = 0;
        for (AiTechnique t : techs) {
            List<int[]> elitesForTech = t.family == AiTechnique.Family.EVOLUTION
                    ? intsAsList(elites) : List.of(new int[]{1});
            List<Object[]> params = sweepParams ? paramAxis(t) : List.<Object[]>of(new Object[]{null, 0.0, ""});
            for (int bins : cols) {
                for (float sp : speeds) {
                    for (float d : durs) {
                        for (int[] ev : elitesForTech) {
                            for (Object[] p : params) {
                                for (int ui : uiScales) {
                                    if (jobs.size() >= max) { dropped++; continue; }
                                    jobs.add(new Job(t, bins, sp, d, ev[0],
                                            (String) p[0], (Double) p[1], (String) p[2], ui));
                                }
                            }
                        }
                    }
                }
            }
        }

        report.append("# Suika debug-matrix report\n");
        report.append("techniques=").append(System.getProperty("suika.capture.techniques", "all"))
              .append("  columns=").append(System.getProperty("suika.capture.columns", "32"))
              .append("  speeds=").append(System.getProperty("suika.capture.speeds", "1"))
              .append("  durations=").append(System.getProperty("suika.capture.durations", "4"))
              .append("  elites=").append(System.getProperty("suika.capture.elites", "1"))
              .append("  params=").append(sweepParams ? "on" : "off")
              .append("  uiscales=").append(System.getProperty("suika.capture.uiscales", "1")).append('\n');
        double estSecs = 0; for (Job j : jobs) estSecs += j.dwell() + 0.35f;
        report.append(String.format(Locale.ROOT, "planned cells: %d (%d dropped over cap %d)  ~%.0fs wall%n%n",
                jobs.size(), dropped, max, estSecs));
        Gdx.app.log("capture", String.format(Locale.ROOT,
                "matrix: %d cells planned (%d dropped over cap %d), ~%.0fs", jobs.size(), dropped, max, estSecs));
    }

    private void renderMatrix(float dt) {
        if (jobs.isEmpty()) { finishMatrix(); return; }
        if (jobIndex >= jobs.size()) { finishMatrix(); return; }

        Job job = jobs.get(jobIndex);
        if (!jobLaunched) {
            launchJob(job);
            jobLaunched = true;
            stageT = 0f;
            autoDropCount = 0;
            autoDropTimer = 0.3f;
            return;
        }

        // Imitation needs live human drops to leave the watch phase; drive them and jump
        // to TRAIN partway through so a captured cell shows the dual board with real data.
        if (job.tech().family == AiTechnique.Family.IMITATION) {
            driveImitationAutoPlay(dt);
            if (stageT > Math.min(2.0f, job.dwell() * 0.5f)) cc.forceImitationTrainPhaseForCapture();
        }

        if (stageT >= job.dwell()) {
            String status = inspectAndReport(job);
            shoot(job.fileName());
            Gdx.app.log("capture", String.format(Locale.ROOT, "[%d/%d] %s  %s",
                    jobIndex + 1, jobs.size(), job.fileName(), status));
            jobIndex++;
            jobLaunched = false;
        }
    }

    /** Inspects the live board for anomalies and appends a report line. Returns a short
     *  status string for the console log. */
    private String inspectAndReport(Job job) {
        List<String> issues = new ArrayList<>();
        int fruits = 0;
        long score = 0;
        int views = 1;
        try {
            GameState st = cc.boardForCapture();
            views = cc.viewCountForCapture();
            fruits = st.fruits().size();
            score = st.score();
            double lo = PhysicsConfig.LEFT_WALL_X, hi = PhysicsConfig.RIGHT_WALL_X;
            for (Fruit f : st.fruits()) {
                double x = f.x(), y = f.y(), r = f.radius();
                if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(r)) {
                    issues.add("NaN in fruit state"); continue;
                }
                // The exact class of bug the high-speed spawn-overlap fix addressed:
                // a fruit shoved through a wall or the floor. Generous margins so only
                // genuine escapes flag, not a fruit resting against the wall.
                if (x - r < lo - 0.9 || x + r > hi + 0.9)
                    issues.add(String.format(Locale.ROOT, "fruit x=%.2f r=%.2f escaped the walls", x, r));
                if (y < -0.9)
                    issues.add(String.format(Locale.ROOT, "fruit y=%.2f below the floor", y));
                if (y > 45)
                    issues.add(String.format(Locale.ROOT, "fruit y=%.2f far above the jar", y));
            }
        } catch (Exception e) {
            issues.add("board read failed: " + e);
        }

        boolean ok = issues.isEmpty();
        if (!ok) anomalyCount++;
        report.append(ok ? "[OK] " : "[!!] ").append(job.fileName())
              .append(String.format(Locale.ROOT, "  | views=%d fruits=%d score=%d", views, fruits, score));
        for (String s : issues) report.append("\n        ANOMALY: ").append(s);
        report.append('\n');
        return ok ? String.format(Locale.ROOT, "ok (fruits=%d score=%d)", fruits, score)
                  : "ANOMALY x" + issues.size();
    }

    private void launchJob(Job job) {
        if (game.settings.uiScaleIndex != job.uiScaleIndex()) {
            game.settings.uiScaleIndex = job.uiScaleIndex();
            game.regenerateFonts();
        }
        PlaygroundConfig c = new PlaygroundConfig();
        c.selectDefaultsFor(job.tech());
        c.actionBins = job.bins();
        c.speedIndex = speedIndexFor(job.speed());
        if (job.tech().family == AiTechnique.Family.EVOLUTION) {
            c.eliteViewIndex = eliteIndexFor(job.eliteViews());
        }
        if (job.paramName() != null) applyParam(c, job.paramName(), job.paramVal());
        cc = new ControlCenterScreen(game, c);
        game.setScreen(cc);
    }

    private void finishMatrix() {
        report.append(String.format(Locale.ROOT, "%nDONE: %d cells, %d with anomalies.%n",
                jobs.size(), anomalyCount));
        try {
            Gdx.files.absolute(outDir + "/report.txt").writeString(report.toString(), false);
            Gdx.app.log("capture", "wrote " + outDir + "/report.txt  (" + anomalyCount + " anomalies)");
        } catch (Exception e) {
            Gdx.app.error("capture", "failed to write report.txt: " + e);
        }
        Gdx.app.exit();
    }

    // ---- matrix helpers ----

    /** Ordered param options for a technique, as {name, value(double), label} triples;
     *  a single {null,0,""} entry means "no adjustable parameter". */
    private List<Object[]> paramAxis(AiTechnique t) {
        List<Object[]> out = new ArrayList<>();
        if (usesRollouts(t)) {
            for (int v : ROLLOUTS) out.add(new Object[]{"rollouts", (double) v, v + "r"});
        } else if (t == AiTechnique.NEUROEVO || t == AiTechnique.CMA_ES || t == AiTechnique.PBT) {
            for (int v : POP) out.add(new Object[]{"population", (double) v, v + "pop"});
        } else if (t == AiTechnique.DECISION_TRANSFORMER || t == AiTechnique.ENS_RTG_VERIFIED) {
            for (int v : RETURNS) out.add(new Object[]{"return", (double) v, v + "ret"});
        } else if (t == AiTechnique.DAGGER || t == AiTechnique.BC) {
            for (double v : LRS) out.add(new Object[]{"lr", v, String.format(Locale.ROOT, "lr%.0e", v)});
        }
        if (out.isEmpty()) out.add(new Object[]{null, 0.0, ""});
        return out;
    }

    private static boolean usesRollouts(AiTechnique t) {
        return switch (t) {
            case MCTS, ALPHAZERO, ENS_MCTS_NET, ENS_MCTS_TIEBREAK,
                 ENS_ADAPTIVE_VOTE, ENS_BANDIT -> true;
            default -> false;
        };
    }

    private void applyParam(PlaygroundConfig c, String name, double val) {
        switch (name) {
            case "rollouts"   -> c.rollouts = (int) val;
            case "population" -> c.populationSize = (int) val;
            case "return"     -> c.targetReturn = val;
            case "lr"         -> c.learningRate = val;
            default -> { }
        }
    }

    private static int speedIndexFor(float speed) {
        int best = 0; float bestErr = Float.MAX_VALUE;
        for (int i = 0; i < PlaygroundConfig.SPEEDS.length; i++) {
            float err = Math.abs(PlaygroundConfig.SPEEDS[i] - speed);
            if (err < bestErr) { bestErr = err; best = i; }
        }
        return best;
    }

    private static int eliteIndexFor(int views) {
        int best = 0; int bestErr = Integer.MAX_VALUE;
        for (int i = 0; i < PlaygroundConfig.ELITE_VIEW_OPTIONS.length; i++) {
            int err = Math.abs(PlaygroundConfig.ELITE_VIEW_OPTIONS[i] - views);
            if (err < bestErr) { bestErr = err; best = i; }
        }
        return best;
    }

    private List<AiTechnique> parseTechniques(String spec) {
        List<AiTechnique> out = new ArrayList<>();
        String s = spec == null ? "all" : spec.trim().toLowerCase(Locale.ROOT);
        switch (s) {
            case "all"        -> { for (AiTechnique t : TECHS) out.add(t); }
            case "ensembles"  -> { for (AiTechnique t : TECHS) if (t.category.equals("Ensemble")) out.add(t); }
            case "planning"   -> addFamily(out, AiTechnique.Family.PLANNING);
            case "evolution"  -> addFamily(out, AiTechnique.Family.EVOLUTION);
            case "imitation"  -> addFamily(out, AiTechnique.Family.IMITATION);
            case "python"     -> addFamily(out, AiTechnique.Family.PYTHON);
            case "baselines"  -> out.add(AiTechnique.HEURISTIC);
            default -> {
                for (String id : s.split(",")) {
                    String trimmed = id.trim();
                    for (AiTechnique t : TECHS) if (t.id.equalsIgnoreCase(trimmed)) out.add(t);
                }
            }
        }
        if (out.isEmpty()) out.add(AiTechnique.MCTS); // never produce an empty plan
        return out;
    }

    private void addFamily(List<AiTechnique> out, AiTechnique.Family fam) {
        // Ensembles share Family.PLANNING but carry the "Ensemble" category — keep the
        // "planning" selector to the genuine planners so ensembles are their own bucket.
        for (AiTechnique t : TECHS) {
            if (t.family != fam) continue;
            if (fam == AiTechnique.Family.PLANNING && t.category.equals("Ensemble")) continue;
            out.add(t);
        }
    }

    private static List<int[]> intsAsList(int[] vals) {
        List<int[]> out = new ArrayList<>();
        for (int v : vals) out.add(new int[]{v});
        return out;
    }

    private static int[] parseInts(String s, int[] fallback) {
        if (s == null || s.isBlank()) return fallback;
        String[] parts = s.split(",");
        List<Integer> vals = new ArrayList<>();
        for (String p : parts) try { vals.add(Integer.parseInt(p.trim())); } catch (NumberFormatException ignored) {}
        if (vals.isEmpty()) return fallback;
        int[] out = new int[vals.size()];
        for (int i = 0; i < out.length; i++) out[i] = vals.get(i);
        return out;
    }

    private static float[] parseFloats(String s, float[] fallback) {
        if (s == null || s.isBlank()) return fallback;
        String[] parts = s.split(",");
        List<Float> vals = new ArrayList<>();
        for (String p : parts) try { vals.add(Float.parseFloat(p.trim())); } catch (NumberFormatException ignored) {}
        if (vals.isEmpty()) return fallback;
        float[] out = new float[vals.size()];
        for (int i = 0; i < out.length; i++) out[i] = vals.get(i);
        return out;
    }

    private static int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return fallback; }
    }

    private static String speedTag(float sp) {
        return (sp == (int) sp ? Integer.toString((int) sp) : Float.toString(sp)) + "x";
    }

    private static String durTag(float d) {
        return (d == (int) d ? Integer.toString((int) d) : Float.toString(d)) + "s";
    }

    // =====================================================================
    // Showcase mode (the curated tour — unchanged behaviour, default)
    // =====================================================================

    private void renderShowcase(float dt) {
        switch (stage) {
            case 0 -> {
                if (stageT > 0.6f) {
                    // Capture the distilled menu without the first-run help modal.
                    game.settings.firstRunHelpSeen = true;
                    game.setScreen(new MainMenuScreen(game));
                    nextStage();
                }
            }
            case 1 -> { if (stageT > 0.5f) { shoot("00-menu.png"); settings = new SettingsScreen(game, MainMenuScreen::new); game.setScreen(settings); nextStage(); } }
            case 2 -> { if (stageT > 0.7f) { shoot("01-settings.png"); settings.scrollToBottomForCapture(); nextStage(); } }
            // Separate stage so the scrolled state (EXPERIMENTAL + AI ENVIRONMENT incl.
            // the GPU-utilization slider) actually renders a frame before the shot —
            // shooting in the same stage as the scroll call captured the pre-scroll frame.
            case 3 -> { if (stageT > 0.3f) { shoot("01c-settings-scrolled.png"); game.setScreen(new LabHubScreen(game)); nextStage(); } }
            // Research Lab Hub (Reward / Dash / Bench / Replay / Physics / Plugins).
            case 4 -> { if (stageT > 0.5f) { shoot("01d-lab-hub.png"); game.setScreen(new MainMenuScreen(game)); nextStage(); } }
            case 5 -> { if (stageT > 0.5f) { shoot("00b-menu-rtlab.png"); openPlayground(); nextStage(); } }
            case 6 -> { if (stageT > 0.6f) { shoot("02-playground.png"); playground.setEnsemblesExpandedForCapture(true); nextStage(); } }
            // Separate stage so the expanded ensemble dropdown (§8: sorted best->worst)
            // actually renders before the shot — same shoot-before-state-change pitfall
            // as the settings scroll above.
            case 7 -> { if (stageT > 0.3f) { shoot("02b-playground-ensembles.png"); playground.setEnsemblesExpandedForCapture(false); playground.openInfocardForCapture(AiTechnique.PPO); nextStage(); } }
            case 8 -> { if (stageT > 0.4f) { shoot("03-infocard-modal.png"); playground.openInfocardForCapture(null); launchControlCenter(AiTechnique.MCTS, false); nextStage(); } }
            case 9 -> { if (stageT > 3.5f) { shoot("04-mcts-cc.png"); cc.openHotswapForCapture(); nextStage(); } }
            case 10 -> { if (stageT > 0.5f) { shoot("05-hotswap-modal.png"); launchControlCenter(AiTechnique.NEUROEVO, false); nextStage(); } }
            case 11 -> { if (stageT > 3.0f) { cc.openSlotsForCapture(); nextStage(); } }
            case 12 -> { if (stageT > 0.5f) { shoot("06-slots-modal.png"); startSixteenXGrid(); nextStage(); } }
            case 13 -> { if (stageT > 9.0f) { shoot("07-neuroevo-16x-grid.png"); techIndex = 0; startTechnique(TECHS[techIndex]); nextStage(); } }

            // ---- full technique sweep: one screenshot per AiTechnique ----
            case 14 -> {
                AiTechnique tech = TECHS[techIndex];
                if (tech.family == AiTechnique.Family.IMITATION) {
                    driveImitationAutoPlay(dt);
                    if (stageT > 2.0f) cc.forceImitationTrainPhaseForCapture();
                }
                if (stageT > dwellFor(tech)) {
                    shoot(String.format("tech-%02d-%s.png", techIndex + 1, tech.id));
                    techIndex++;
                    if (techIndex < TECHS.length) { startTechnique(TECHS[techIndex]); stageT = 0f; }
                    else nextStage();
                }
            }

            // ---- extra curated views the default sweep doesn't cover ----
            case 15 -> { if (stageT > 0.2f) { launchControlCenter(AiTechnique.ENS_ADAPTIVE_VOTE, false); nextStage(); } }
            case 16 -> { if (stageT > 5.0f) { shoot("90-adaptive-vote.png"); launchControlCenter(AiTechnique.NEUROEVO, true); nextStage(); } }
            case 17 -> { if (stageT > 9.0f) { shoot("91-neuroevo-ghost.png"); nextStage(); } }

            // TensorBoard SETUP row — only meaningful for PPO/Decision Transformer (see
            // AiTechnique#supportsTensorboard()); confirms the toggle + OPEN button render.
            case 18 -> { if (stageT > 0.2f) { launchControlCenter(AiTechnique.PPO, false); nextStage(); } }
            case 19 -> { if (stageT > 1.0f) { cc.openHotswapForCapture(); nextStage(); } }
            case 20 -> { if (stageT > 0.5f) { shoot("94-tensorboard-hotswap.png"); nextStage(); } }

            case 21 -> { if (stageT > 0.2f) { humanPlay = new SuikaScreen(game, SuikaScreen.Mode.HUMAN); game.setScreen(humanPlay); nextStage(); } }
            case 22 -> { if (stageT > 1.5f) { shoot("92-human-play.png"); humanPlay.pauseForCapture(); nextStage(); } }
            case 23 -> { if (stageT > 0.5f) { shoot("92b-human-pause.png"); openGameOver(); nextStage(); } }
            case 24 -> { if (stageT > 1.0f) { shoot("93-game-over.png"); nextStage(); Gdx.app.exit(); } }
            default -> { }
        }
    }

    /** Launches Neuroevolution with the max (16×) elite view count, to exercise the
     *  auto-grid layout at scale. */
    private void startSixteenXGrid() {
        PlaygroundConfig c = new PlaygroundConfig();
        c.selectDefaultsFor(AiTechnique.NEUROEVO);
        c.eliteViewIndex = PlaygroundConfig.ELITE_VIEW_OPTIONS.length - 1; // 16×
        c.actionBins = game.settings.actionBins();
        cc = new ControlCenterScreen(game, c);
        game.setScreen(cc);
    }

    /** How long to let a technique run before its screenshot — enough to reach a live state. */
    private float dwellFor(AiTechnique t) {
        return switch (t.family) {
            case EVOLUTION -> 8.0f;                 // reach a few generations
            case IMITATION -> 8.0f;                 // a few live drops, then a beat of training
            default -> 4.0f;
        };
    }

    private void startTechnique(AiTechnique tech) {
        launchControlCenter(tech, false);
        autoDropCount = 0;
        autoDropTimer = 0.3f;
    }

    /** Drops at chute-clear checkpoints (like a fast real player) until game 1 ends. */
    private void driveImitationAutoPlay(float dt) {
        if (cc == null || cc.isGameOverForCapture() || autoDropCount >= AUTO_DROP_XS.length) return;
        autoDropTimer -= dt;
        if (autoDropTimer <= 0f && cc.chuteClearForCapture()) {
            cc.forceHumanDropForCapture(AUTO_DROP_XS[autoDropCount % AUTO_DROP_XS.length]);
            autoDropCount++;
            autoDropTimer = 0.45f;
        }
    }

    /** Build a short game and hand its final state to the game-over summary. */
    private void openGameOver() {
        dev.suika.core.GameCore core = new dev.suika.core.GameCore(7L);
        double[] xs = {2.0, 2.0, 5.0, 5.0, 8.0, 3.5, 6.5};
        for (double x : xs) core.dropAndSettle(x);
        game.setScreen(new GameOverScreen(game, core.getScore(), core.getState(),
                SuikaScreen.Mode.HUMAN, 7L));
    }

    private void openPlayground() {
        playground = new AiPlaygroundScreen(game);
        game.setScreen(playground);
    }

    private void launchControlCenter(AiTechnique tech, boolean ghost) {
        PlaygroundConfig c = new PlaygroundConfig();
        c.selectDefaultsFor(tech);
        c.ghostView = ghost;
        c.actionBins = game.settings.actionBins();
        cc = new ControlCenterScreen(game, c);
        game.setScreen(cc);
    }

    private void shoot(String name) {
        int w = Gdx.graphics.getBackBufferWidth();
        int h = Gdx.graphics.getBackBufferHeight();
        byte[] pixels = ScreenUtils.getFrameBufferPixels(0, 0, w, h, true);
        Pixmap pm = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        ByteBuffer buf = pm.getPixels();
        buf.clear();
        buf.put(pixels);
        buf.position(0);
        PixmapIO.writePNG(Gdx.files.absolute(outDir + "/" + name), pm);
        pm.dispose();
        Gdx.app.log("capture", "wrote " + outDir + "/" + name);
    }

    @Override public void pause() {}
    @Override public void resume() {}
    @Override public void dispose() { if (game != null) game.dispose(); }
}
