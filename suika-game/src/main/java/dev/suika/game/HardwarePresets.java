package dev.suika.game;

/**
 * Five quality presets that scale every technique's compute budget (search rollouts,
 * population size, sims/generation, think-time) to THIS machine's measured throughput.
 *
 * <p>Unlike the old coarse core-count tiers, the scaling comes from a real benchmark:
 * {@link PresetCalibration} times headless sims/second on this hardware and exposes a
 * speed factor, so "Balanced" on a fast box genuinely does more work than on a slow one.
 * Presets are unusable until that calibration has run (Settings → PRESETS → Calibrate).
 *
 * <p>Naming is quality-first: MAX_QUALITY is the slowest/deepest, FASTEST is the snappiest.
 */
public enum HardwarePresets {

    MAX_QUALITY("Max Quality", "slowest, deepest",  4.0, 4000),
    HIGH       ("High",        "high quality",      2.2, 1500),
    BALANCED   ("Balanced",    "balanced",          1.0, 450),
    FAST       ("Fast",        "snappy",            0.55, 220),
    FASTEST    ("Fastest",     "instant feedback",  0.30, 120);

    public final String label, hint;
    /** Base compute multiplier for this preset (before the machine speed factor). */
    private final double baseScale;
    /** Per-move think-time budget (ms) for search techniques at this preset. */
    private final int thinkMs;

    HardwarePresets(String label, String hint, double baseScale, int thinkMs) {
        this.label = label; this.hint = hint; this.baseScale = baseScale; this.thinkMs = thinkMs;
    }

    /** Human-readable summary of what the calibration detected, for the UI. */
    static String hardwareLabel() {
        String gpu = Boolean.TRUE.equals(GpuProbe.available()) ? " + GPU" : "";
        int cores = Runtime.getRuntime().availableProcessors();
        return cores + " cores" + gpu + (PresetCalibration.calibrated()
                ? "  ·  " + Math.round(PresetCalibration.simsPerSec()) + " sims/s"
                : "  ·  uncalibrated");
    }

    private static int nearest(int[] options, double target) {
        int best = options[0];
        for (int o : options) if (Math.abs(o - target) < Math.abs(best - target)) best = o;
        return best;
    }

    private static int indexOfNearest(int[] options, double target) {
        int bestIdx = 0;
        for (int i = 0; i < options.length; i++)
            if (Math.abs(options[i] - target) < Math.abs(options[bestIdx] - target)) bestIdx = i;
        return bestIdx;
    }

    /**
     * Applies this preset to {@code cfg} for its currently-selected technique, scaling the
     * relevant budgets by {@code baseScale × machineSpeedFactor}. Parallelism stays on Auto
     * — presets trade depth for time, not thread count, so CPU/GPU utilisation stays
     * maximal at every preset.
     */
    void applyTo(PlaygroundConfig cfg) {
        double scale = baseScale * PresetCalibration.speedFactor();
        AiTechnique t = cfg.technique;

        cfg.rollouts   = nearest(new int[]{40, 80, 150, 300, 600, 1200, 2400}, 150 * scale);
        cfg.maxThinkMs = thinkMs;

        if (t.family == AiTechnique.Family.EVOLUTION) {
            cfg.populationSize  = nearest(new int[]{16, 24, 40, 64, 128, 256, 512, 1000}, 40 * scale);
            cfg.simsPerGenIndex = indexOfNearest(PlaygroundConfig.SIMS_PER_GEN_OPTIONS,
                    Math.max(1, Math.round(2 * scale)));
        }
        if (t == AiTechnique.DAGGER) {
            // Deeper presets adapt more gently (lower LR); snappy presets learn faster.
            cfg.learningRate = scale >= 2.0 ? 1e-3 : scale >= 0.9 ? 3e-3 : 1e-2;
        }
        if (t == AiTechnique.DECISION_TRANSFORMER || t == AiTechnique.ENS_RTG_VERIFIED) {
            cfg.targetReturn = scale >= 2.0 ? 4000 : scale >= 0.9 ? 2000 : 1000;
        }
        cfg.preset = this;
    }

    public String cyclerLabel() {
        if (!PresetCalibration.calibrated()) return label + " · calibrate first";
        return label + " · " + hint;
    }
}
