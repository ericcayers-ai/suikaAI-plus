package dev.suika.game;

/**
 * Runtime configuration chosen in the {@link AiPlaygroundScreen} for one technique:
 * the technique itself plus the knobs that apply to it (speed, parallelism, and the
 * family-specific hyper-parameter the control center exposes).
 */
public final class PlaygroundConfig {

    public AiTechnique technique = AiTechnique.MCTS;

    /** Playback / training speed multiplier — 0.5× (slow-mo) up to 1024× (turbo). */
    public static final float[] SPEEDS = {0.5f, 1f, 2f, 4f, 8f, 16f, 32f, 64f, 128f, 256f, 512f, 1024f};
    public int speedIndex = 1;   // default 1×

    /**
     * Worker threads for parallel evaluation / rollouts (where applicable).
     * {@code 0} means "Auto" — let the runtime pick (all available cores), which is the
     * fastest fan-out setting. A positive value pins it to that many threads.
     */
    public int parallelism = 0;   // 0 = Auto (all cores)

    /** Resolve {@link #parallelism} to an actual thread count (Auto → all cores). */
    public int evalThreads() {
        return parallelism > 0 ? parallelism : Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    /**
     * Display label for the Parallelism cycler. Honest about compute: only
     * {@link AiTechnique#gpuCapableTraining()} (currently just PPO's real
     * {@code --device} flag) shows "GPU" when {@link GpuProbe} has detected one — every
     * other technique's parallel work (evolution eval pool, root-parallel MCTS,
     * column-parallel Greedy) runs on the JVM with dyn4j physics, which has no CUDA
     * path, so those always read as a CPU thread/core count regardless of GPU presence.
     */
    public String parallelismLabel() {
        if (parallelism > 0) return parallelism + (technique.gpuCapableTraining() ? " envs" : " threads");
        if (technique.gpuCapableTraining()) {
            Boolean gpu = GpuProbe.available();
            if (gpu == null) return "Auto (probing GPU…)";
            return gpu ? "Auto (GPU" + (GpuProbe.deviceName() != null ? ": " + shortDevice() : "") + ")"
                       : "Auto (" + evalThreads() + " cores)";
        }
        return "Auto (" + evalThreads() + " cores)";
    }

    private String shortDevice() {
        String d = GpuProbe.deviceName();
        return d.length() > 18 ? d.substring(0, 17) + "…" : d;
    }

    public int actionBins = 32;

    // Family-specific knobs (sensible defaults; tuned per technique on select()).
    public int    rollouts       = 80;     // MCTS / AlphaZero
    public int    populationSize = 24;     // GA / CMA-ES / PBT
    public double mutationSigma  = 0.10;   // GA
    public double targetReturn   = 2000;   // Decision Transformer
    public double learningRate   = 1e-3;   // BC / DAgger

    /**
     * Per-move think-time budget for MCTS (milliseconds). Prevents high-rollout configs
     * from stalling the game at fast physics speeds. 0 = unlimited.
     */
    public long maxThinkMs = 450;

    /**
     * When true, evolution runners show top-3 agents as translucent ghost boards
     * overlaid on the champion's live game, so you can see the population divergence.
     */
    public boolean ghostView = false;

    // ---- Evolution-only launch config (Neuroevo / CMA-ES / PBT) ----

    /**
     * Simulations (independent game-overs) averaged per genome each generation. More
     * sims = less noisy fitness, but more compute. They run <em>simultaneously</em>,
     * not one after another — see {@link dev.suika.ai.GeneticTrainer}.
     */
    public static final int[] SIMS_PER_GEN_OPTIONS = {1, 2, 3, 5, 8, 16};
    public int simsPerGenIndex = 0;       // default 1

    /**
     * How many generations of elites are kept alive as on-screen "ghost" boards before
     * the oldest are culled and restarted on the freshest elite. Higher = watch more of
     * the population's lineage diverge before it's replaced.
     */
    public static final int[] GHOST_CULL_OPTIONS = {1, 2, 3, 5, 8, 12};
    public int ghostCullIndex = 1;        // default 2 generations

    /**
     * How many live boards to show at once — the champion plus its top elites (GA) or
     * this generation's top offspring (CMA-ES). The grid auto-arranges into
     * {@code ceil(sqrt(n))} columns for whatever count is chosen.
     */
    public static final int[] ELITE_VIEW_OPTIONS = {1, 2, 3, 4, 6, 8, 10, 12, 16};
    public int eliteViewIndex = 3;        // default 4 (existing champion + 3 elites)

    public int simsPerGen()     { return SIMS_PER_GEN_OPTIONS[simsPerGenIndex]; }
    public int ghostCullGens()  { return GHOST_CULL_OPTIONS[ghostCullIndex]; }
    public int eliteViewCount() { return ELITE_VIEW_OPTIONS[eliteViewIndex]; }

    public float speed() { return SPEEDS[Math.floorMod(speedIndex, SPEEDS.length)]; }

    public String speedLabel() {
        float s = speed();
        return (s == (int)s ? Integer.toString((int)s) : Float.toString(s)) + "×";
    }

    /** Reset family knobs to good defaults for the newly-selected technique. */
    public void selectDefaultsFor(AiTechnique t) {
        this.technique = t;
        switch (t) {
            case MCTS, ALPHAZERO,
                 ENS_MCTS_NET, ENS_MCTS_TIEBREAK, ENS_VOTING, ENS_EVOLVED_MCTS,
                 ENS_IMITATION_MCTS, ENS_ADAPTIVE_VOTE, ENS_BANDIT -> { rollouts = 80; maxThinkMs = 450; }
            case NEUROEVO, PBT   -> { populationSize = 24; mutationSigma = 0.10; }
            case CMA_ES          -> populationSize = 16;
            case BC, DAGGER      -> learningRate = 1e-3;
            case DECISION_TRANSFORMER, OFFLINE_RL, ENS_RTG_VERIFIED -> targetReturn = 2000;
            default -> { }
        }
    }
}
