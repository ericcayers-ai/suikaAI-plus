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

    /** Discrete rollout / population ladders the presets snap onto (mirror the UI cyclers). */
    private static final int[] ROLLOUT_STEPS = {40, 80, 150, 300, 600, 1200, 2400};
    private static final int[] POP_STEPS      = {16, 24, 40, 64, 128, 256, 512, 1000};

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

        // Search depth + think budget scale with the preset for every search technique
        // (MCTS / AlphaZero and the MCTS-based ensembles). Parallelism stays on Auto so
        // CPU/GPU utilisation is maximal at every preset — presets trade depth for time,
        // never thread count.
        cfg.rollouts   = nearest(ROLLOUT_STEPS, 150 * scale);
        cfg.maxThinkMs = thinkMs;

        if (t.family == AiTechnique.Family.EVOLUTION) {
            // Bigger population + more sims/genome at higher quality: a larger λ explores
            // more directions per generation, and more sims cut the fitness-estimate noise
            // (on top of the Common-Random-Numbers fix in the trainers) — together giving
            // steadier, faster progress. Lower presets trade that for snappier generations.
            cfg.populationSize  = nearest(POP_STEPS, 40 * scale);
            cfg.simsPerGenIndex = indexOfNearest(PlaygroundConfig.SIMS_PER_GEN_OPTIONS,
                    Math.max(1, (int) Math.round(3 * scale)));
            // GA-only recombination knobs (ignored by CMA-ES, whose sampling IS the math):
            // anneal σ at higher quality (broad early, fine late) and keep uniform crossover
            // on for real recombination; snappy presets hold a fixed, slightly larger σ so
            // each generation visibly moves.
            cfg.mutationSigmaIndex = scale >= 2.0 ? 1 : scale >= 0.9 ? 2 : 3;   // 0.05 / 0.10 / 0.20
            cfg.mutationSigma      = PlaygroundConfig.MUTATION_SIGMA_OPTIONS[cfg.mutationSigmaIndex];
            cfg.crossover          = true;
            cfg.sigmaAnneal        = scale >= 2.0;
        }
        // Gradient learners: a lower LR is steadier (higher quality), a higher LR learns
        // faster but noisier (snappy) — the standard depth/stability trade. DQN's TD error
        // is Huber-clipped in the trainer, so it tolerates the same ladder as BC/DAgger.
        if (t == AiTechnique.DAGGER || t == AiTechnique.BC || t == AiTechnique.DQN) {
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
