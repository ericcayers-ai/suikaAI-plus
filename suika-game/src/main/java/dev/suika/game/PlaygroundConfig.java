package dev.suika.game;

/**
 * Runtime configuration chosen in the {@link AiPlaygroundScreen} for one technique:
 * the technique itself plus the knobs that apply to it (speed, parallelism, and the
 * family-specific hyper-parameter the control center exposes).
 */
public final class PlaygroundConfig {

    public AiTechnique technique = AiTechnique.MCTS;

    /** Playback / training speed multiplier (physics + cadence). */
    public static final float[] SPEEDS = {0.5f, 1f, 2f, 4f, 8f};
    public int speedIndex = 1;

    /** Worker threads for parallel evaluation / rollouts (where applicable). */
    public int parallelism = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);

    public int actionBins = 32;

    // Family-specific knobs (sensible defaults; tuned per technique on select()).
    public int    rollouts       = 80;    // MCTS / AlphaZero
    public int    populationSize = 24;    // GA / CMA-ES / PBT
    public double mutationSigma  = 0.10;  // GA
    public double targetReturn   = 2000;  // Decision Transformer
    public double learningRate   = 1e-3;  // BC / DAgger

    public float speed() { return SPEEDS[Math.floorMod(speedIndex, SPEEDS.length)]; }

    public String speedLabel() { return speed() + "×"; }

    /** Reset family knobs to good defaults for the newly-selected technique. */
    public void selectDefaultsFor(AiTechnique t) {
        this.technique = t;
        switch (t) {
            case MCTS, ALPHAZERO -> rollouts = 80;
            case NEUROEVO, PBT   -> { populationSize = 24; mutationSigma = 0.10; }
            case CMA_ES          -> populationSize = 16;
            case BC, DAGGER      -> learningRate = 1e-3;
            case DECISION_TRANSFORMER, OFFLINE_RL -> targetReturn = 2000;
            default -> { }
        }
    }
}
