package dev.suika.game;

import dev.suika.core.GameCore;
import dev.suika.core.PhysicsConfig;

import java.util.Random;

/**
 * Measures THIS machine's real simulation throughput so the quality {@link HardwarePresets}
 * scale accurately to the hardware instead of guessing from core count alone. The
 * benchmark runs the exact workload the AI does — headless {@link GameCore#dropAndSettle}
 * episodes — and reports sims/second, which every preset uses as a speed factor.
 *
 * <p>Presets are deliberately <b>unusable until calibrated</b> (per the product spec): the
 * playground/hotswap preset cyclers refuse to cycle and point the player at
 * Settings → PRESETS → "Calibrate", where a one-tap benchmark runs on a background thread
 * with live percentage progress. The result (and whether a CUDA GPU was detected) is
 * persisted so calibration is a one-time step per machine.
 */
final class PresetCalibration {

    private PresetCalibration() {}

    private static volatile boolean calibrated = false;
    private static volatile boolean running = false;
    private static volatile boolean gpu = false;
    private static volatile double  simsPerSec = 0;
    private static volatile int     progressPct = 0;

    static boolean calibrated() { return calibrated; }
    static boolean running()    { return running; }
    static int     progressPct(){ return progressPct; }
    static double  simsPerSec() { return simsPerSec; }
    static boolean gpuAtCalibration() { return gpu; }

    /** Machine speed factor vs a 500 sims/s reference, clamped to a sane band. 1.0 when
     *  uncalibrated so preset math stays well-defined even before the benchmark runs. */
    static double speedFactor() {
        if (!calibrated || simsPerSec <= 0) return 1.0;
        return Math.max(0.3, Math.min(6.0, simsPerSec / 500.0));
    }

    static String statusLabel() {
        if (running) return "Calibrating... " + progressPct + "%";
        if (!calibrated) return "Not calibrated — tap to run";
        return String.format("%.0f sims/s  ·  x%.2f%s", simsPerSec, speedFactor(),
                gpu ? "  ·  GPU ready" : "  ·  CPU");
    }

    /** Restore a persisted calibration (see {@link SettingsPersistence}). */
    static void restore(boolean cal, double sps) {
        calibrated = cal;
        simsPerSec = sps;
        if (cal) gpu = Boolean.TRUE.equals(GpuProbe.available());
    }

    /** Runs the benchmark on a daemon thread; safe to call again (no-op while running). */
    static void calibrateAsync() {
        if (running) return;
        running = true;
        progressPct = 0;
        Thread t = new Thread(() -> {
            try {
                runBatch(24, 999L);                 // warm-up (JIT), not timed
                final int batches = 8, perBatch = 32;
                int sims = 0;
                long t0 = System.nanoTime();
                for (int b = 0; b < batches; b++) {
                    sims += runBatch(perBatch, 1000L + b);
                    progressPct = (int) Math.round((b + 1) * 100.0 / batches);
                }
                double secs = (System.nanoTime() - t0) / 1e9;
                simsPerSec = secs > 1e-6 ? sims / secs : 0;
                gpu = Boolean.TRUE.equals(GpuProbe.available());
                calibrated = true;
                SettingsPersistence.saveCalibration(true, simsPerSec);
            } catch (Throwable ignored) {
                // A failed calibration just leaves the previous (or uncalibrated) state.
            } finally {
                progressPct = 100;
                running = false;
            }
        }, "preset-calibrate");
        t.setDaemon(true);
        t.start();
    }

    private static int runBatch(int n, long seed) {
        Random r = new Random(seed);
        GameCore core = new GameCore(seed);
        int done = 0;
        for (int i = 0; i < n; i++) {
            if (core.isGameOver()) core = new GameCore(seed + i);
            double x = PhysicsConfig.DROP_X_MIN
                    + r.nextDouble() * (PhysicsConfig.DROP_X_MAX - PhysicsConfig.DROP_X_MIN);
            core.dropAndSettle(x);
            done++;
        }
        return done;
    }
}
