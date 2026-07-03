package dev.suika.game;

/**
 * Derives per-technique quality presets from THIS machine's actual resources —
 * CPU core count, usable memory, and (asynchronously, via {@link GpuProbe}) whether
 * a CUDA GPU is visible to the Python stack.
 *
 * <p>Three presets, quality-first naming per the product spec:
 * <ul>
 *   <li><b>SLOW</b> — best quality: deepest search / largest populations / most sims
 *       per genome. Moves and generations take visibly longer.</li>
 *   <li><b>NORMAL</b> — balanced defaults for this hardware tier.</li>
 *   <li><b>HIGH</b> — high speed: shallow-but-snappy settings for quick feedback.</li>
 * </ul>
 *
 * <p>The hardware tier scales the whole table: an 8-core/16 GB machine's NORMAL is
 * roughly a 4-core laptop's SLOW. Everything here is a plain deterministic function
 * of probed numbers — no timing benchmarks, so applying a preset is instant.
 */
public enum HardwarePresets {

    SLOW("Slow", "best quality"),
    NORMAL("Normal", "balanced"),
    HIGH("High", "fastest");

    public final String label, hint;

    HardwarePresets(String label, String hint) { this.label = label; this.hint = hint; }

    // ---- hardware probe (static, cheap, cached) ----

    /** 0 = low (≤4 cores or <8 GB), 1 = mid, 2 = high (≥12 cores and ≥24 GB). */
    static int hardwareTier() {
        int cores = Runtime.getRuntime().availableProcessors();
        long memGb = totalMemoryGb();
        if (cores >= 12 && memGb >= 24) return 2;
        if (cores <= 4 || memGb < 8)    return 0;
        return 1;
    }

    static long totalMemoryGb() {
        try {
            var os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
                return Math.max(1, sun.getTotalMemorySize() / (1024L * 1024 * 1024));
            }
        } catch (Throwable ignored) { /* fall through to JVM heap heuristic */ }
        return Math.max(1, Runtime.getRuntime().maxMemory() * 4 / (1024L * 1024 * 1024));
    }

    /** Human-readable summary of what the preset engine detected, for the UI. */
    static String hardwareLabel() {
        String tier = switch (hardwareTier()) { case 2 -> "high-end"; case 1 -> "mid"; default -> "low"; };
        String gpu = Boolean.TRUE.equals(GpuProbe.available()) ? " + GPU" : "";
        return Runtime.getRuntime().availableProcessors() + " cores · " + totalMemoryGb() + " GB · " + tier + gpu;
    }

    // ---- per-preset scaling ----

    /** Quality multiplier applied to search/population budgets. */
    private double budgetScale() {
        return switch (this) { case SLOW -> 3.0; case NORMAL -> 1.0; case HIGH -> 0.5; };
    }

    private static int nearest(int[] options, double target) {
        int best = options[0];
        for (int o : options) if (Math.abs(o - target) < Math.abs(best - target)) best = o;
        return best;
    }

    /**
     * Applies this preset to {@code cfg} for its currently-selected technique.
     * Only touches the knobs that technique actually reads; parallelism is left on
     * Auto (all cores) — the preset trades depth for time, not thread count, so
     * CPU/GPU utilization stays maximal at every preset.
     */
    void applyTo(PlaygroundConfig cfg) {
        int tier = hardwareTier();
        double scale = budgetScale() * switch (tier) { case 2 -> 2.0; case 1 -> 1.0; default -> 0.5; };
        AiTechnique t = cfg.technique;

        // Search-based techniques (plain or inside an ensemble): rollouts + think budget.
        cfg.rollouts   = nearest(new int[]{40, 80, 150, 300, 600, 1200, 2400}, 150 * scale);
        cfg.maxThinkMs = switch (this) { case SLOW -> 2000; case NORMAL -> 450; case HIGH -> 200; };

        if (t.family == AiTechnique.Family.EVOLUTION) {
            cfg.populationSize  = nearest(new int[]{16, 24, 40, 64, 128, 256, 512, 1000}, 40 * scale);
            cfg.simsPerGenIndex = indexOfNearest(PlaygroundConfig.SIMS_PER_GEN_OPTIONS,
                    switch (this) { case SLOW -> 5; case NORMAL -> 2; case HIGH -> 1; });
        }
        if (t == AiTechnique.DAGGER) {
            cfg.learningRate = switch (this) { case SLOW -> 1e-3; case NORMAL -> 3e-3; case HIGH -> 1e-2; };
        }
        if (t == AiTechnique.DECISION_TRANSFORMER || t == AiTechnique.ENS_RTG_VERIFIED) {
            cfg.targetReturn = switch (this) { case SLOW -> 4000; case NORMAL -> 2000; case HIGH -> 1000; };
        }
        cfg.preset = this;
    }

    private static int indexOfNearest(int[] options, int target) {
        int bestIdx = 0;
        for (int i = 0; i < options.length; i++)
            if (Math.abs(options[i] - target) < Math.abs(options[bestIdx] - target)) bestIdx = i;
        return bestIdx;
    }

    public String cyclerLabel() { return label + " · " + hint; }
}
