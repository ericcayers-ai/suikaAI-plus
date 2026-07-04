package dev.suika.game;

import com.badlogic.gdx.Gdx;

import java.util.ArrayList;
import java.util.List;

/**
 * Best-effort self-restart of the running app — used after the GPU dependencies finish
 * installing so the whole stack (GPU probe, inference routing) comes up fresh with CUDA
 * available, as the product spec asks ("when done, restart the app").
 *
 * <p>Relaunches the exact same JVM command that started this process (via
 * {@link ProcessHandle}) and then exits. If the platform doesn't expose the command line
 * (rare), it falls back to just exiting — the GPU has already been re-probed live by then,
 * so a manual relaunch is the only thing lost.
 */
final class AppRestart {

    private AppRestart() {}

    static void restart() {
        try {
            ProcessHandle.Info info = ProcessHandle.current().info();
            String cmd = info.command().orElse(null);
            if (cmd != null) {
                List<String> full = new ArrayList<>();
                full.add(cmd);
                info.arguments().ifPresent(args -> {
                    for (String a : args) full.add(a);
                });
                new ProcessBuilder(full)
                        .directory(new java.io.File(System.getProperty("user.dir", ".")))
                        .inheritIO()
                        .start();
            }
        } catch (Exception ignored) {
            // Couldn't relaunch — fall through to a clean exit; the user reopens the app.
        }
        if (Gdx.app != null) Gdx.app.exit(); else System.exit(0);
    }
}
