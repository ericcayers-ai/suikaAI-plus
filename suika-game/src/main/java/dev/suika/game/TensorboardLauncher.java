package dev.suika.game;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Starts a local TensorBoard server for a technique's training logs and opens it in the
 * default browser — the same "open in the OS's own tool" pattern as
 * {@link ModelSlots#revealSlotFolder}, just pointed at a TensorBoard dashboard instead of
 * a file manager.
 *
 * <p>The app never runs training itself (see {@link PythonRunner}'s class doc — it only
 * shows the copyable command), so it can't know what {@code --out} path the user actually
 * used. To stay locatable regardless, the log directory is a fixed, well-known path
 * ({@code ~/.suikai/tb_logs/<technique-id>}) that {@code train_ppo.py} and
 * {@code decision_transformer.py} both default their {@code --tb-logdir} to — see
 * {@link AiTechnique#supportsTensorboard()} for which techniques actually have a real
 * script that writes there.
 */
final class TensorboardLauncher {

    private TensorboardLauncher() {}

    /** Fixed, unlikely-to-collide port — deliberately not TensorBoard's default 6006 so
     *  this never fights a TensorBoard the user may already have running themselves. */
    private static final int PORT = 6096;

    private static volatile Process activeProcess;

    /** Monotonic within a session so two exports in the same second still get distinct run
     *  folders (the timestamp alone can collide on a fast save/train burst). */
    private static final AtomicInteger RUN_SEQ = new AtomicInteger();
    private static final DateTimeFormatter RUN_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** The root that holds one folder per technique/ensemble; TensorBoard is launched here so
     *  every technique AND every run under it shows up as its own selectable series. */
    static Path rootLogDir() {
        return Path.of(System.getProperty("user.home"), ".suikai", "tb_logs");
    }

    static Path logDir(String techniqueId) {
        return rootLogDir().resolve(techniqueId);
    }

    /** A fresh, uniquely-named run folder under a technique's log dir, so each save/train is a
     *  distinct TensorBoard run rather than overwriting the previous one's event stream. Named
     *  {@code run-<timestamp>-<seq>} — matches the {@code run-*} convention the Python scripts
     *  use, so JVM and Python runs of the same technique sit side by side under one folder. */
    static Path newRunDir(String techniqueId) {
        String name = "run-" + LocalDateTime.now().format(RUN_STAMP)
                + "-" + String.format("%03d", RUN_SEQ.incrementAndGet());
        return logDir(techniqueId).resolve(name);
    }

    static boolean hasLogs(String techniqueId) {
        Path dir = logDir(techniqueId);
        if (!Files.isDirectory(dir)) return false;
        try (var stream = Files.walk(dir)) {
            return stream.anyMatch(p -> {
                String f = p.getFileName().toString();
                return f.startsWith("events.out.tfevents");
            });
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Pushes a saved run's progress curves ({@code graphs}: named float[] series such as
     * fitness / TD-loss / accuracy) into a FRESH, uniquely-named run folder under this
     * technique's TensorBoard log directory — so the SAME curves the control center streams
     * to its LiveCharts are viewable in TensorBoard for any technique that trains on the JVM
     * (evolution, imitation, DQN, learning ensembles), not just the two Python scripts, AND
     * every save is its own distinct run rather than overwriting the previous save's event
     * stream (TensorBoard's run picker keys off immediate-child-of-logdir folder names, so a
     * shared folder across saves silently collapsed every run into one indistinguishable
     * curve — the exact bug this run-subdirectory scheme fixes).
     *
     * <p>Beyond the raw series, also writes: a {@code run_info} text summary (technique,
     * slot, final score, timestamp, hyperparameters) so a run is identifiable without
     * cross-referencing the app, and a {@code summary/<tag>_final}/{@code _best} scalar pair
     * per series so runs can be compared at a glance in TensorBoard's scalar table without
     * opening every chart.
     *
     * <p>Runs entirely on a daemon thread and never throws: TensorBoard is a nice-to-have
     * viewer, so a missing venv or a Python hiccup is silently a no-op.
     */
    static void exportScalarsAsync(String techniqueId, int slot, double score,
                                    java.util.Map<String, Double> hparams,
                                    java.util.Map<String, float[]> graphs) {
        if (!PythonSetup.isReady()) return;
        boolean hasGraphs = graphs != null && !graphs.isEmpty();
        Thread t = new Thread(() -> {
            try {
                Path dir = newRunDir(techniqueId);
                Files.createDirectories(dir);
                StringBuilder py = new StringBuilder(
                        "from torch.utils.tensorboard import SummaryWriter\n" +
                        "w = SummaryWriter(r'" + dir.toAbsolutePath() + "')\n");
                py.append("w.add_text('run_info', ")
                  .append(pyStr(runInfoText(techniqueId, slot, score, hparams)))
                  .append(", 0)\n");
                if (hasGraphs) {
                    for (var e : graphs.entrySet()) {
                        float[] v = e.getValue();
                        if (v == null || v.length == 0) continue;
                        String tag = e.getKey().replaceAll("[^A-Za-z0-9_./-]", "_");
                        StringBuilder arr = new StringBuilder();
                        double best = Double.NEGATIVE_INFINITY;
                        for (int i = 0; i < v.length; i++) {
                            if (i > 0) arr.append(',');
                            arr.append(v[i]);
                            if (v[i] > best) best = v[i];
                        }
                        py.append("for i,x in enumerate([").append(arr).append("]):\n")
                          .append("    w.add_scalar('progress/").append(tag).append("', x, i)\n");
                        py.append("w.add_scalar('summary/").append(tag).append("_final', ")
                          .append(v[v.length - 1]).append(", 0)\n");
                        py.append("w.add_scalar('summary/").append(tag).append("_best', ")
                          .append(best).append(", 0)\n");
                    }
                }
                py.append("w.add_scalar('summary/score', ").append(score).append(", 0)\n");
                py.append("w.close()\n");
                Process p = new ProcessBuilder(PythonSetup.venvPython().toString(), "-c", py.toString())
                        .redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                p.waitFor();
            } catch (Exception ignored) { /* best-effort viewer export */ }
        }, "tb-scalar-export");
        t.setDaemon(true);
        t.start();
    }

    /** Human-readable run metadata written as a TensorBoard text summary — the "more info"
     *  a run needs to be identifiable on its own, without cross-referencing the app. */
    private static String runInfoText(String techniqueId, int slot, double score,
                                       java.util.Map<String, Double> hparams) {
        StringBuilder sb = new StringBuilder();
        sb.append("technique: ").append(techniqueId).append("  \n");
        sb.append("slot: ").append(slot).append("  \n");
        sb.append("score: ").append(score).append("  \n");
        sb.append("saved: ").append(java.time.LocalDateTime.now()).append("  \n");
        if (hparams != null) {
            for (var e : hparams.entrySet()) {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append("  \n");
            }
        }
        return sb.toString();
    }

    /** Renders a Java string as a single-quoted Python literal embedded in a generated
     *  script — escapes the handful of characters that would otherwise break out of the
     *  quotes (backslash, quote, newline are the only ones {@link #runInfoText} can produce). */
    private static String pyStr(String s) {
        String escaped = s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
        return "'" + escaped + "'";
    }

    private static void initializeTensorBoardLogs(String techniqueId) {
        try {
            Path dir = newRunDir(techniqueId);
            Files.createDirectories(dir);
            // FIX: Auto-instantiate a minimal TensorBoard logs run using the Python environment
            Process p = new ProcessBuilder(
                    PythonSetup.venvPython().toString(), "-c",
                    "from torch.utils.tensorboard import SummaryWriter; " +
                            "writer = SummaryWriter('" + dir.toAbsolutePath().toString().replace("\\", "/") + "'); " +
                            "writer.add_text('run_info', 'technique: " + techniqueId + "  \\nempty placeholder run', 0); " +
                            "writer.add_scalar('init/step', 0.0, 0); " +
                            "writer.close();"
            ).start();
            p.waitFor();
        } catch (Exception ignored) {}
    }

    /**
     * Starts (or reuses) a single TensorBoard server rooted at {@link #rootLogDir()} — ALL
     * techniques' logs, not just {@code techniqueId}'s — and opens it in the default browser
     * pre-filtered to that technique's runs. Synchronous — the short sleep waiting for the
     * server to bind is a deliberate, bounded wait, not a long-running block; called from a
     * UI click handler.
     *
     * <p>Rooting the one persistent server at every technique's logs (instead of restarting
     * it against a single technique's folder on every click, which the previous version did)
     * is what makes techniques/ensembles distinguishable in the first place: TensorBoard's
     * run picker keys off the immediate-child folder name under {@code --logdir}, so with a
     * server per technique every run under it was named just {@code run-<timestamp>} and
     * two techniques could never appear side by side. Rooted here, each run's full name is
     * {@code <techniqueId>/run-<timestamp>-<seq>} — technique AND run both distinguished, and
     * comparable across techniques in the same dashboard without restarting anything.
     *
     * @return a short status message for the panel — success, "not installed yet", or the
     *         failure reason, never throws.
     */
    static synchronized String launch(String techniqueId) {
        if (!PythonSetup.isReady()) return "Install AI GPU deps first (Settings -> AI ENVIRONMENT)";
        // Auto-initialize an empty run if none exists yet, so the OPEN button works even
        // before the first save/train writes scalars (JVM techniques now export their
        // progress curves here on save — see exportScalarsAsync).
        if (!hasLogs(techniqueId)) initializeTensorBoardLogs(techniqueId);
        try {
            boolean needsFreshServer = activeProcess == null || !activeProcess.isAlive();
            if (needsFreshServer) {
                Path root = rootLogDir();
                Files.createDirectories(root);
                activeProcess = new ProcessBuilder(
                        PythonSetup.venvPython().toString(), "-m", "tensorboard.main",
                        "--logdir", root.toString(),
                        "--port", Integer.toString(PORT),
                        "--reload_interval", "5")
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
                Thread.sleep(1200); // let the local server bind before the browser requests it
            }
            // Pre-filter the run picker to this technique via TensorBoard's regex run filter
            // (a supported deep-link query param) — still just one server, one browser tab,
            // switching technique only changes which runs the filter shows.
            String url = "http://localhost:" + PORT + "/#scalars&regexInput="
                    + java.net.URLEncoder.encode("^" + techniqueId + "/", java.nio.charset.StandardCharsets.UTF_8);
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(URI.create(url));
            }
            return "TensorBoard: http://localhost:" + PORT + " (filtered to " + techniqueId + ")";
        } catch (Exception e) {
            return "Couldn't launch TensorBoard: " + e.getMessage();
        }
    }
}
