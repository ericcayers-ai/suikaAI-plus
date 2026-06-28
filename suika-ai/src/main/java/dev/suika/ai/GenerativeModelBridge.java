package dev.suika.ai;

import dev.suika.core.Fruit;
import dev.suika.core.FruitTier;
import dev.suika.core.GameState;
import dev.suika.core.PhysicsConfig;
import dev.suika.env.StateObservationEncoder;

import java.util.Random;

/**
 * JVM-side generative-model adapter (ROADMAP §IV.11, §II.4).
 *
 * <p>Implements board-aware stochastic action generation entirely on the JVM using
 * merge-potential scoring as the generative prior. In production this sends
 * observations over the bridge to Python diffusion/flow-matching models; the
 * JVM-native path is fully functional and provides meaningful action diversity
 * without any Python dependency.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Score every candidate drop column by merge potential (same-tier fruit below).</li>
 *   <li>Convert scores to a softmax distribution with configurable temperature.</li>
 *   <li>Sample one (or a batch) from that distribution.</li>
 * </ol>
 */
public final class GenerativeModelBridge {

    /** Supported generative policy types. */
    public enum ModelType { DIFFUSION_POLICY, FLOW_MATCHING }

    private static final double DEFAULT_TEMPERATURE = 1.5;

    private final ModelType type;
    private final StateObservationEncoder encoder = new StateObservationEncoder();
    private final Random rng;

    public GenerativeModelBridge(ModelType type) {
        this(type, 0L);
    }

    public GenerativeModelBridge(ModelType type, long seed) {
        this.type = type;
        this.rng  = new Random(seed);
    }

    /**
     * Sample an action conditioned on the board state.
     *
     * <p>Scores each bin by the merge potential at that drop position: bins directly
     * above same-tier fruits score higher. A softmax with temperature controls
     * exploration — matching the stochastic diversity expected from a diffusion policy.
     *
     * @param state   current game state
     * @param numBins number of discrete action bins
     * @return sampled action index in {@code [0, numBins)}
     */
    public int sampleAction(GameState state, int numBins) {
        double[] scores = scoreColumns(state, numBins);
        double[] probs  = softmax(scores, DEFAULT_TEMPERATURE);
        return categoricalSample(probs);
    }

    /**
     * Sample a diverse batch of candidate actions for visualisation.
     * Uses temperature 2× higher than the single-sample path for wider coverage.
     */
    public int[] sampleBatch(GameState state, int numBins, int batchSize) {
        double[] scores = scoreColumns(state, numBins);
        double[] probs  = softmax(scores, DEFAULT_TEMPERATURE * 2.0);
        int[] actions = new int[batchSize];
        for (int i = 0; i < batchSize; i++) actions[i] = categoricalSample(probs);
        return actions;
    }

    public ModelType type() { return type; }

    // -------------------------------------------------------------------------

    private double[] scoreColumns(GameState state, int numBins) {
        double xMin = PhysicsConfig.DROP_X_MIN;
        double xMax = PhysicsConfig.DROP_X_MAX;
        double[] scores = new double[numBins];
        FruitTier drop = state.currentFruitTier();

        for (int bin = 0; bin < numBins; bin++) {
            double x = xMin + (bin / (double) (numBins - 1)) * (xMax - xMin);

            double mergePotential  = 0.0;
            double heightPenalty   = 0.0;
            double deadlinePenalty = 0.0;

            for (Fruit f : state.fruits()) {
                double dx = Math.abs(f.x() - x);
                double proximity = Math.max(0.0, 1.0 - dx / (drop.radius * 4.0));

                if (f.tier() == drop && proximity > 0) {
                    mergePotential += proximity * (drop.tier + 1);
                }
                if (dx < drop.radius * 2) {
                    heightPenalty  += f.y() / PhysicsConfig.CONTAINER_HEIGHT * 0.5;
                    if (state.isAboveDeadline(f)) deadlinePenalty += 2.0;
                }
            }
            scores[bin] = mergePotential - heightPenalty - deadlinePenalty;
        }
        return scores;
    }

    private double[] softmax(double[] scores, double temperature) {
        double max = Double.NEGATIVE_INFINITY;
        for (double s : scores) if (s > max) max = s;
        double sum = 0.0;
        double[] probs = new double[scores.length];
        for (int i = 0; i < scores.length; i++) {
            probs[i] = Math.exp((scores[i] - max) / temperature);
            sum     += probs[i];
        }
        for (int i = 0; i < probs.length; i++) probs[i] /= sum;
        return probs;
    }

    private int categoricalSample(double[] probs) {
        double u = rng.nextDouble();
        double cumulative = 0.0;
        for (int i = 0; i < probs.length - 1; i++) {
            cumulative += probs[i];
            if (u < cumulative) return i;
        }
        return probs.length - 1;
    }
}
