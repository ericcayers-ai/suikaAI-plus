package dev.suika.ai;

import java.util.List;

/**
 * Behavioral Cloning (supervised imitation) — ROADMAP §IV.6.
 *
 * <p>Trains an {@link MlpPolicy} by minimising cross-entropy loss between
 * the policy's action-logits and the expert's action labels (argmin supervised learning).
 * Uses SGD (analytical backprop gradient — see {@link MlpPolicy#backpropCrossEntropyGradient})
 * with a configurable learning rate.
 *
 * <p>This is the JVM-native "train an AI on my playstyle" trainer.
 * A gradient-based deep-network version runs in Python (see {@code python/suika/bc.py}).
 */
public final class BehavioralCloningTrainer implements TrainerPlugin {

    private static final int INPUT_SIZE  = dev.suika.env.StateObservationEncoder.TOTAL;
    private static final int HIDDEN_SIZE = 64;
    private static final int OUTPUT_BINS = 32;

    private final MlpPolicy  policy;
    private final DemoDataset dataset;
    private final double     learningRate;
    private final int        batchSize;
    private int              updateCount = 0;

    /** No-arg constructor for {@link java.util.ServiceLoader} discovery; uses an empty dataset and default hyperparams. */
    public BehavioralCloningTrainer() { this(new DemoDataset(), 1e-3, 32); }

    public BehavioralCloningTrainer(DemoDataset dataset, double learningRate, int batchSize) {
        this.policy       = new MlpPolicy(INPUT_SIZE, HIDDEN_SIZE, OUTPUT_BINS);
        this.dataset      = dataset;
        this.learningRate = learningRate;
        this.batchSize    = batchSize;
        policy.initRandom(new java.util.Random(42L));
    }

    @Override public String id() { return "behavioral-cloning"; }

    @Override
    public void observe(dev.suika.core.StepResult transition) {
        // BC trains from the offline dataset, not online transitions
    }

    @Override
    public void update() {
        if (dataset.size() == 0) return;
        List<Demonstration> batch = dataset.sample(batchSize);

        float[][] inputs  = new float[batch.size()][];
        int[]     targets = new int[batch.size()];
        for (int i = 0; i < batch.size(); i++) {
            inputs[i]  = batch.get(i).observation();
            targets[i] = batch.get(i).action();
        }

        double[] weights = policy.getWeights();
        double[] grad    = policy.backpropCrossEntropyGradient(inputs, targets);

        // SGD step
        for (int i = 0; i < weights.length; i++) {
            weights[i] -= learningRate * grad[i];
        }
        policy.setWeights(weights);
        updateCount++;
    }

    /** Returns the trained agent ready for evaluation. */
    public NeuralAgent trainedAgent() { return new NeuralAgent(policy); }
    public MlpPolicy   policy()        { return policy; }
    public int         updateCount()   { return updateCount; }
}
