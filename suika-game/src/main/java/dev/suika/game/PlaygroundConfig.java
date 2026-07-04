package dev.suika.game;

/**
 * Runtime configuration chosen in the {@link AiPlaygroundScreen} for one technique:
 * the technique itself plus the knobs that apply to it (speed, parallelism, quality
 * preset, the family-specific hyper-parameter, and the per-ensemble customization
 * knobs the control center exposes).
 */
public final class PlaygroundConfig {

    public AiTechnique technique = AiTechnique.MCTS;

    /** Hardware-aware quality preset last applied (display only — individual knobs
     *  can still be overridden afterwards; see {@link HardwarePresets#applyTo}). */
    public HardwarePresets preset = HardwarePresets.NORMAL;

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
     * column-parallel Greedy, the ensembles' inner-agent pool) runs on the JVM with
     * dyn4j physics, which has no CUDA path, so those always read as a CPU
     * thread/core count regardless of GPU presence.
     */
    public String parallelismLabel() {
        // A technique shows a GPU label when it can actually use one: PPO's real
        // --device flag, or ANY Python-backed technique once the "Prefer GPU" setting is
        // on and a CUDA device was detected (see GpuProbe#gpuUsableFor).
        boolean gpuRoute = technique.gpuCapableTraining() || GpuProbe.gpuUsableFor(technique);
        if (parallelism > 0) return parallelism + (gpuRoute ? " envs" : " threads");
        if (gpuRoute) {
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
    public int    rollouts       = 80;     // MCTS / AlphaZero / MCTS-based ensembles
    public int    populationSize = 24;     // GA / CMA-ES
    public double mutationSigma  = 0.10;   // GA
    public double targetReturn   = 2000;   // Decision Transformer / RTG ensemble
    public double learningRate   = 1e-3;   // DAgger

    /**
     * Per-move think-time budget for MCTS (milliseconds). Prevents high-rollout configs
     * from stalling the game at fast physics speeds. 0 = unlimited.
     */
    public long maxThinkMs = 450;

    // ---- Ensemble customization (see EnsembleAgents) ----

    /** Which trained save the MCTS + Policy Net ensemble sources its donor net from. */
    public static final AiTechnique[] ENSEMBLE_DONORS =
            { AiTechnique.NEUROEVO, AiTechnique.CMA_ES, AiTechnique.DAGGER };
    public int ensembleDonorIndex = 0;
    public AiTechnique ensembleDonor() { return ENSEMBLE_DONORS[ensembleDonorIndex]; }

    /** Which of the donor technique's save slots to source the net from. 0 = "Auto"
     *  (first present slot); 1..SLOT_COUNT pins a specific slot so you can compare
     *  differently-trained donor nets. */
    public int ensembleDonorSlot = 0;   // 0 = auto (first present)
    public String ensembleDonorSlotLabel() { return ensembleDonorSlot == 0 ? "Auto" : "Slot " + ensembleDonorSlot; }

    /** How much of the MCTS + Policy Net final score comes from the net (vs search visits). */
    public static final double[] NET_WEIGHT_OPTIONS = {0.1, 0.3, 0.5, 0.7, 0.9};
    public int netWeightIndex = 1;   // default 0.3
    public double ensembleNetWeight() { return NET_WEIGHT_OPTIONS[netWeightIndex]; }

    /** Visit-share threshold below the top that still counts as "tied" for the tiebreak. */
    public static final double[] TIE_THRESHOLD_OPTIONS = {0.70, 0.85, 0.95};
    public int tieThresholdIndex = 1;   // default 0.85
    public double ensembleTieThreshold() { return TIE_THRESHOLD_OPTIONS[tieThresholdIndex]; }

    /** UCB1 exploration constant for the bandit meta-controller. */
    public static final double[] UCB_C_OPTIONS = {0.7, 1.4, 2.0};
    public int ucbCIndex = 1;   // default 1.4 (the textbook √2-ish value)
    public double ensembleUcbC() { return UCB_C_OPTIONS[ucbCIndex]; }

    /** Multiplicative-weights learning rate for the adaptive voting committee. */
    public static final double[] ADAPT_LR_OPTIONS = {0.02, 0.08, 0.20};
    public int adaptLrIndex = 1;   // default 0.08
    public double ensembleAdaptLr() { return ADAPT_LR_OPTIONS[adaptLrIndex]; }

    /** True when the selected technique reads any of the ensemble knobs above. */
    public boolean ensembleKnobsApplicable() { return technique.isEnsemble(); }

    // ---- Evolution selection mathematics (see GeneticTrainer.Selection) ----

    /** GA parent-selection strategy: tournament (classic), rank-proportional roulette,
     *  or Boltzmann/softmax fitness-proportional — "guided by top mathematical
     *  probability" as the spec puts it. Ignored by CMA-ES (its sampling IS the math). */
    public int selectionIndex = 0;
    public dev.suika.ai.GeneticTrainer.Selection selection() {
        return dev.suika.ai.GeneticTrainer.Selection.values()[selectionIndex];
    }

    /** Uniform crossover between two selected parents (real recombination) vs
     *  mutation-only asexual reproduction. */
    public boolean crossover = true;

    /** Anneal mutation σ downward as generations pass (σ · 0.995^gen, floored),
     *  focusing search as the population converges. */
    public boolean sigmaAnneal = false;

    public static final double[] MUTATION_SIGMA_OPTIONS = {0.02, 0.05, 0.10, 0.20, 0.30};
    public int mutationSigmaIndex = 2;

    /**
     * When true, evolution runners show top-3 agents as translucent ghost boards
     * overlaid on the champion's live game, so you can see the population divergence.
     */
    public boolean ghostView = false;

    // ---- Evolution-only launch config (Neuroevo / CMA-ES) ----

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

    /** Reset family knobs to good defaults for the newly-selected technique, then
     *  apply the hardware-derived NORMAL preset so every technique starts at settings
     *  this machine can actually sustain. */
    public void selectDefaultsFor(AiTechnique t) {
        this.technique = t;
        switch (t) {
            case MCTS, ALPHAZERO,
                 ENS_MCTS_NET, ENS_MCTS_TIEBREAK, ENS_ADAPTIVE_VOTE, ENS_BANDIT -> { rollouts = 80; maxThinkMs = 450; }
            case NEUROEVO        -> { populationSize = 24; mutationSigmaIndex = 2; mutationSigma = 0.10; }
            case CMA_ES          -> populationSize = 16;
            case DAGGER          -> learningRate = 1e-3;
            case DECISION_TRANSFORMER, ENS_RTG_VERIFIED -> targetReturn = 2000;
            default -> { }
        }
        HardwarePresets.NORMAL.applyTo(this);
    }
}
