package dev.suika.game;

import com.badlogic.gdx.Gdx;
import java.util.ArrayList;
import java.util.List;

/**
 * Best-effort self-restart of the running app — used after the GPU dependencies finish
 * installing so the whole stack (GPU probe, inference routing) comes up fresh with CUDA
 * available, as the product spec asks ("when done, restart the app").
 *
 * <p>Relaunches the exact same JVM command that started this process and then exits.
 */
final class AppRestart {

    private AppRestart() {}

    static void restart() {
        try {
            String javaHome = System.getProperty("java.home");
            String javaBin = javaHome + java.io.File.separator + "bin" + java.io.File.separator + "java";
            if (System.getProperty("os.name", "").startsWith("Windows")) {
                javaBin += ".exe";
            }

            List<String> command = new ArrayList<>();
            command.add(javaBin);

            // FIX: Pull JVM startup flags directly from RuntimeMXBean (handles Windows empty-arguments bug)
            command.addAll(java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());

            // Add standard classpath
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));

            // FIX: Query the JVM system properties to extract the main class and parameters
            String sunJavaCmd = System.getProperty("sun.java.command");
            if (sunJavaCmd != null && !sunJavaCmd.isEmpty()) {
                String[] parts = sunJavaCmd.split("\\s+");
                for (String part : parts) {
                    command.add(part);
                }
            } else {
                command.add("dev.suika.app.SuikaApplication");
            }

            new ProcessBuilder(command)
                    .directory(new java.io.File(System.getProperty("user.dir", ".")))
                    .inheritIO()
                    .start();
        } catch (Exception e) {
            System.err.println("Self-restart process failed: " + e.getMessage());
        }
        if (Gdx.app != null) Gdx.app.exit(); else System.exit(0);
    }
}