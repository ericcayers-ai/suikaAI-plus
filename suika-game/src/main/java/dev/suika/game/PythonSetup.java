package dev.suika.game;

import java.io.*;
import java.nio.file.*;
import java.util.function.Consumer;

/**
 * Manages a per-user Python virtual environment with PyTorch for AI techniques
 * that require Python (PPO, DQN, SAC, etc.).
 *
 * <p>The venv lives at {@code ~/.suikai/venv} and persists across reinstalls.
 * GPU support targets CUDA 12.1; PyTorch falls back to CPU automatically if no GPU.
 */
public final class PythonSetup {

    /** Persistent venv directory, shared across app versions. */
    public static final Path VENV_DIR = Path.of(
            System.getProperty("user.home"), ".suikai", "venv");

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").startsWith("Windows");

    private PythonSetup() {}

    /** True if the venv Python executable is present (install has run). */
    public static boolean isReady() {
        return Files.exists(venvPython());
    }

    public static Path venvPython() {
        return WINDOWS ? VENV_DIR.resolve("Scripts/python.exe")
                       : VENV_DIR.resolve("bin/python3");
    }

    private static Path venvPip() {
        return WINDOWS ? VENV_DIR.resolve("Scripts/pip.exe")
                       : VENV_DIR.resolve("bin/pip");
    }

    /**
     * Finds the first usable system Python 3 binary.
     * Returns null if Python is not installed.
     */
    public static String findSystemPython() {
        for (String cmd : new String[]{"python", "python3"}) {
            try {
                Process p = new ProcessBuilder(cmd, "--version")
                        .redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes()).trim();
                if (p.waitFor() == 0 && out.contains("Python 3")) return cmd;
            } catch (Exception ignored) {}
        }
        // fallback: accept any Python
        for (String cmd : new String[]{"python", "python3"}) {
            try {
                Process p = new ProcessBuilder(cmd, "--version")
                        .redirectErrorStream(true).start();
                if (p.waitFor() == 0) return cmd;
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * Asynchronously creates the venv and installs PyTorch (CUDA 12.1 + CPU fallback).
     *
     * @param status callback invoked on every progress line; runs on a daemon thread —
     *               do NOT update LibGDX scene-graph from it; just update a volatile field.
     */
    public static void installAsync(Consumer<String> status) {
        Thread t = new Thread(() -> {
            try {
                status.accept("Searching for Python 3…");
                String python = findSystemPython();
                if (python == null) {
                    status.accept("Python not found. Install Python 3.10+ from python.org then retry.");
                    return;
                }

                if (!Files.exists(VENV_DIR)) {
                    status.accept("Creating virtual environment at ~/.suikai/venv …");
                    Files.createDirectories(VENV_DIR.getParent());
                    run(status, python, "-m", "venv", VENV_DIR.toString());
                } else {
                    status.accept("Virtual environment already exists, updating packages…");
                }

                // Upgrade pip first for reliability
                status.accept("Upgrading pip…");
                run(status, venvPip().toString(), "install", "--upgrade", "pip", "--quiet");

                // Install PyTorch with CUDA 12.1 support (falls back to CPU if no GPU)
                status.accept("Installing PyTorch + CUDA 12.1 (this can take 5-15 min on first run)…");
                run(status,
                    venvPip().toString(), "install",
                    "torch", "torchvision", "torchaudio",
                    "--index-url", "https://download.pytorch.org/whl/cu121",
                    "--quiet"
                );

                // Install gym-style extras used by the Python runners
                status.accept("Installing gym, stable-baselines3, numpy…");
                run(status,
                    venvPip().toString(), "install",
                    "gymnasium", "stable-baselines3", "numpy",
                    "--quiet"
                );

                if (isReady()) {
                    status.accept("Done  ·  Python environment ready at ~/.suikai/venv");
                } else {
                    status.accept("Warning: venv may be incomplete — check output");
                }
            } catch (Exception e) {
                status.accept("Error: " + e.getMessage());
            }
        }, "python-setup");
        t.setDaemon(true);
        t.start();
    }

    private static void run(Consumer<String> out, String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.isBlank()) out.accept(line);
            }
        }
        p.waitFor();
    }
}
