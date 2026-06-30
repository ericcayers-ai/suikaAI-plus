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
     * fastest, GPU-like fan-out setting. A positive value pins it to that many threads.
     */
    public int parallelism = 0;   // 0 = Auto (all cores)

    /** Resolve {@link #parallelism} to an actual thread count (Auto → all cores). */
    public int evalThreads() {
        return parallelism > 0 ? parallelism : Math.max(1, Runtime.getRuntime().availableProcessors());
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

    public float speed() { return SPEEDS[Math.floorMod(speedIndex, SPEEDS.length)]; }

    public String speedLabel() {
        float s = speed();
        return (s == (int)s ? Integer.toString((int)s) : Float.toString(s)) + "×";
    }

    /** Reset family knobs to good defaults for the newly-selected technique. */
    public void selectDefaultsFor(AiTechnique t) {
        this.technique = t;
        switch (t) {
            case MCTS, ALPHAZERO -> { rollouts = 80; maxThinkMs = 450; }
            case NEUROEVO, PBT   -> { populationSize = 24; mutationSigma = 0.10; }
            case CMA_ES          -> populationSize = 16;
            case BC, DAGGER      -> learningRate = 1e-3;
            case DECISION_TRANSFORMER, OFFLINE_RL -> targetReturn = 2000;
            default -> { }
        }
    }
}
