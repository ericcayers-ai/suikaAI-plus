package dev.suika.game;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

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
    private static volatile String activeTechniqueId;

    static Path logDir(String techniqueId) {
        return Path.of(System.getProperty("user.home"), ".suikai", "tb_logs", techniqueId);
    }

    static boolean hasLogs(String techniqueId) {
        Path dir = logDir(techniqueId);
        if (!Files.isDirectory(dir)) return false;
        try (var stream = Files.list(dir)) {
            return stream.findAny().isPresent();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Pushes a saved run's progress curves ({@code graphs}: named float[] series such as
     * fitness / TD-loss / accuracy) into this technique's TensorBoard log directory as
     * scalar summaries — one step per array index — so the SAME curves the control center
     * streams to its LiveCharts are viewable in TensorBoard for any technique that trains
     * on the JVM (evolution, imitation, DQN, learning ensembles), not just the two Python
     * scripts. Runs entirely on a daemon thread and never throws: TensorBoard is a nice-to-
     * have viewer, so a missing venv or a Python hiccup is silently a no-op.
     */
    static void exportScalarsAsync(String techniqueId, java.util.Map<String, float[]> graphs) {
        if (graphs == null || graphs.isEmpty() || !PythonSetup.isReady()) return;
        Thread t = new Thread(() -> {
            try {
                Path dir = logDir(techniqueId);
                Files.createDirectories(dir);
                StringBuilder py = new StringBuilder(
                        "from torch.utils.tensorboard import SummaryWriter\n" +
                        "w = SummaryWriter(r'" + dir.toAbsolutePath() + "')\n");
                for (var e : graphs.entrySet()) {
                    float[] v = e.getValue();
                    if (v == null || v.length == 0) continue;
                    String tag = e.getKey().replaceAll("[^A-Za-z0-9_./-]", "_");
                    StringBuilder arr = new StringBuilder();
                    for (int i = 0; i < v.length; i++) { if (i > 0) arr.append(','); arr.append(v[i]); }
                    py.append("for i,x in enumerate([").append(arr).append("]):\n")
                      .append("    w.add_scalar('progress/").append(tag).append("', x, i)\n");
                }
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

    private static void initializeTensorBoardLogs(String techniqueId) {
        try {
            Path dir = logDir(techniqueId);
            Files.createDirectories(dir);
            // FIX: Auto-instantiate a minimal TensorBoard logs run using the Python environment
            Process p = new ProcessBuilder(
                    PythonSetup.venvPython().toString(), "-c",
                    "from torch.utils.tensorboard import SummaryWriter; " +
                            "writer = SummaryWriter('" + dir.toAbsolutePath().toString().replace("\\", "/") + "'); " +
                            "writer.add_scalar('init/step', 0.0, 0); " +
                            "writer.close();"
            ).start();
            p.waitFor();
        } catch (Exception ignored) {}
    }

    /**
     * Starts (or reuses) a TensorBoard server for {@code techniqueId} and opens it in the
     * default browser. Synchronous — the short sleep waiting for the server to bind is a
     * deliberate, bounded wait, not a long-running block; called from a UI click handler.
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
            boolean needsFreshServer = activeProcess == null || !activeProcess.isAlive()
                    || !techniqueId.equals(activeTechniqueId);
            if (needsFreshServer) {
                if (activeProcess != null && activeProcess.isAlive()) activeProcess.destroy();
                activeProcess = new ProcessBuilder(
                        PythonSetup.venvPython().toString(), "-m", "tensorboard.main",
                        "--logdir", logDir(techniqueId).toString(),
                        "--port", Integer.toString(PORT),
                        "--reload_interval", "5")
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
                activeTechniqueId = techniqueId;
                Thread.sleep(1200); // let the local server bind before the browser requests it
            }
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(URI.create("http://localhost:" + PORT + "/"));
            }
            return "TensorBoard: http://localhost:" + PORT;
        } catch (Exception e) {
            return "Couldn't launch TensorBoard: " + e.getMessage();
        }
    }
}
