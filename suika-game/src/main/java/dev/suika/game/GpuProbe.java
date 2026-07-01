package dev.suika.game;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * App-wide, cached, non-blocking probe for whether a CUDA-capable GPU is visible to the
 * managed Python environment. Spawning a Python process to import torch takes real wall
 * time (hundreds of ms to a few seconds), so this runs once on a daemon thread — started
 * as early as possible from {@link SuikaGame#create()} — and every screen just reads the
 * cached result instead of probing itself.
 *
 * <p>Important honesty note: a detected GPU only means the <em>Python</em> stack
 * (PPO/DQN/SAC/Diffusion/... training, launched separately via {@code python -m
 * suika.train_*}) can use it. The JVM-native techniques (Planning, Evolution, Imitation)
 * run dyn4j physics and small hand-rolled MLPs on the CPU only — there is no JVM CUDA
 * binding in this project — so their "Parallelism" control always means CPU threads,
 * never the GPU, regardless of what this probe reports. Callers should only surface
 * {@link #available()} where it is actually true (see {@link AiTechnique#gpuAccelerable()}).
 */
final class GpuProbe {

    private GpuProbe() {}

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    /** null = still probing / not started; Boolean.TRUE/FALSE once resolved. */
    private static volatile Boolean available = null;
    private static volatile String deviceName = null;

    /** Idempotent — safe to call from any screen constructor; only the first call spawns the probe. */
    static void ensureStarted() {
        if (!STARTED.compareAndSet(false, true)) return;
        Thread t = new Thread(GpuProbe::probe, "gpu-probe");
        t.setDaemon(true);
        t.start();
    }

    /** {@code null} while unresolved, otherwise whether a CUDA GPU was detected. */
    static Boolean available() { return available; }

    /** Device name once resolved (e.g. "NVIDIA GeForce RTX 4060"), else {@code null}. */
    static String deviceName() { return deviceName; }

    private static void probe() {
        try {
            String python = PythonSetup.isReady() ? PythonSetup.venvPython().toString() : findSystemPython();
            if (python == null) { available = false; return; }
            Process p = new ProcessBuilder(python, "-c",
                    "import torch;" +
                    "print('cuda:'+str(torch.cuda.is_available())+':'+" +
                    "(torch.cuda.get_device_name(0) if torch.cuda.is_available() else ''))")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            int code = p.waitFor();
            if (code != 0) { available = false; return; }
            for (String line : out.split("\\R")) {
                if (!line.startsWith("cuda:")) continue;
                String[] parts = line.split(":", 3);
                boolean avail = parts.length > 1 && parts[1].equals("True");
                available = avail;
                deviceName = avail && parts.length > 2 ? parts[2] : null;
                return;
            }
            available = false;
        } catch (Exception e) {
            available = false;
        }
    }

    private static String findSystemPython() {
        for (String exe : new String[]{"python", "python3", "py"}) {
            try {
                Process p = new ProcessBuilder(exe, "--version").redirectErrorStream(true).start();
                if (p.waitFor() == 0) return exe;
            } catch (Exception ignored) { /* try next */ }
        }
        return null;
    }
}
