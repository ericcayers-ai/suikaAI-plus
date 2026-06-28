package dev.suika.ai;

import java.util.List;

/**
 * Behavioral Cloning (supervised imitation) — ROADMAP §IV.6.
 *
 * <p>Trains an {@link MlpPolicy} by minimising cross-entropy loss between
 * the policy's action-logits and the expert's action labels (argmin supervised learning).
 * Uses SGD with a configurable learning rate.
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

        double[] weights = policy.getWeights();
        double[] grad    = computeGradient(batch, weights);

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

    /**
     * Finite-difference gradient estimate of cross-entropy loss w.r.t. weights.
     * Production implementation would use backprop; this is correct but slow.
     * The Python BC trainer uses autograd for large networks.
     */
    private double[] computeGradient(List<Demonstration> batch, double[] weights) {
        double eps    = 1e-4;
        double[] grad = new double[weights.length];
        double baseLoss = crossEntropyLoss(batch, weights);

        for (int i = 0; i < weights.length; i++) {
            weights[i] += eps;
            double plusLoss = crossEntropyLoss(batch, weights);
            weights[i] -= eps;
            grad[i] = (plusLoss - baseLoss) / eps;
        }
        return grad;
    }

    private double crossEntropyLoss(List<Demonstration> batch, double[] weights) {
        policy.setWeights(weights);
        double loss = 0.0;
        for (Demonstration d : batch) {
            double[] logits = policy.forward(d.observation());
            double maxL = Double.NEGATIVE_INFINITY;
            for (double l : logits) if (l > maxL) maxL = l;
            double sumExp = 0.0;
            for (double l : logits) sumExp += Math.exp(l - maxL);
            loss -= (logits[d.action()] - maxL) - Math.log(sumExp);
        }
        return loss / batch.size();
    }
}
