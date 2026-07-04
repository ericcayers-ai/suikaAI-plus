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
public final class PresetCalibration {

    private PresetCalibration() {}

    private static volatile boolean calibrated = false;
    private static volatile boolean running = false;
    private static volatile boolean gpu = false;
    private static volatile double  simsPerSec = 0;
    private static volatile int     progressPct = 0;

    public static boolean calibrated() { return calibrated; }
    public static boolean running()    { return running; }
    public static int     progressPct(){ return progressPct; }
    public static double  simsPerSec() { return simsPerSec; }
    public static boolean gpuAtCalibration() { return gpu; }

    /** Machine speed factor vs a 500 sims/s reference, clamped to a sane band. 1.0 when
     *  uncalibrated so preset math stays well-defined even before the benchmark runs. */
    public static double speedFactor() {
        if (!calibrated || simsPerSec <= 0) return 1.0;
        return Math.max(0.3, Math.min(6.0, simsPerSec / 500.0));
    }

    public static String statusLabel() {
        if (running) return "Calibrating... " + progressPct + "%";
        if (!calibrated) return "Not calibrated — tap to run";
        return String.format("%.0f sims/s  ·  x%.2f%s", simsPerSec, speedFactor(),
                gpu ? "  ·  GPU ready" : "  ·  CPU");
    }

    /** Restore a persisted calibration (see {@link SettingsPersistence}). */
    public static void restore(boolean cal, double sps) {
        calibrated = cal;
        simsPerSec = sps;
        if (cal) gpu = Boolean.TRUE.equals(GpuProbe.available());
    }

    /**
     * FIX: Calibrate using multiple independent runs, filtering out outliers to counter
     * CPU thermal throttling and OS process scheduling jitter.
     */
    public static void calibrateAsync() {
        if (running) return;
        running = true;
        progressPct = 0;
        Thread t = new Thread(() -> {
            try {
                // Warm up JVM JIT compiler to ensure we are benchmarking compiled native loops
                for (int w = 0; w < 3; w++) {
                    runBatch(32, 999L + w);
                }

                int trials = 5;
                double[] trialResults = new double[trials];

                for (int tIdx = 0; tIdx < trials; tIdx++) {
                    int perBatch = 64;
                    long t0 = System.nanoTime();
                    int sims = runBatch(perBatch, 1000L + tIdx * 100L);
                    long t1 = System.nanoTime();

                    double secs = (t1 - t0) / 1e9;
                    trialResults[tIdx] = secs > 1e-6 ? sims / secs : 0;

                    progressPct = (int) Math.round((tIdx + 1) * 100.0 / trials);
                    // Slight sleep to allow core temperatures to stabilize and minimize thermal bias
                    Thread.sleep(100);
                }

                // Filter outliers by sorting and taking the trimmed mean of the middle 3 runs
                java.util.Arrays.sort(trialResults);

                double sum = 0;
                int count = 0;
                for (int i = 1; i < trials - 1; i++) {
                    if (trialResults[i] > 0) {
                        sum += trialResults[i];
                        count++;
                    }
                }

                simsPerSec = count > 0 ? sum / count : trialResults[2];
                gpu = Boolean.TRUE.equals(GpuProbe.available());
                calibrated = true;
                SettingsPersistence.saveCalibration(true, simsPerSec);
            } catch (Throwable ignored) {
                // A failed calibration leaves the previous (or uncalibrated) state intact.
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