package dev.suika.game;

import java.io.IOException;
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

        // FIX: Automatically initialize empty TensorBoard runs if none exist yet
        if (!hasLogs(techniqueId)) {
            initializeTensorBoardLogs(techniqueId);
        }

        try {
            boolean needsFreshServer = activeProcess == null || !activeProcess.isAlive() || !techniqueId.equals(activeTechniqueId);
            if (needsFreshServer) {
                if (activeProcess != null && activeProcess.isAlive()) activeProcess.destroy();
                activeProcess = new ProcessBuilder(
                        PythonSetup.venvPython().toString(), "-c", "import tensorboard.main; import sys; sys.argv=['tensorboard', '--logdir', '" + logDir(techniqueId).toAbsolutePath().toString().replace("\\", "/") + "', '--port', '" + PORT + "', '--reload_interval', '5']; tensorboard.main.run_main()")
                        .redirectErrorStream(true)
                        .start();
                activeTechniqueId = techniqueId;
            }
            // Open browser
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(new java.net.URI("http://localhost:" + PORT));
            }
            return "TensorBoard opened at http://localhost:" + PORT;
        } catch (Exception e) {
            return "Failed to open: " + e.getMessage();
        }
    }
}