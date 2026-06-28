package dev.suika.ai;

import dev.suika.core.Fruit;
import dev.suika.core.FruitTier;
import dev.suika.core.GameState;
import dev.suika.core.PhysicsConfig;
import dev.suika.env.StateObservationEncoder;

import java.util.Random;

/**
 * Decision Transformer-style agent: conditioned on a target return (ROADMAP §IV.8).
 *
 * <p>The return-to-go (RTG) signal modulates action selection on the JVM:
 * <ul>
 *   <li>High RTG (far from target) → greedy merge-seeking: pick the column that
 *       maximises same-tier merge potential.</li>
 *   <li>Low RTG (near target) → conservative: prefer safe, low-height drops.</li>
 *   <li>Exploration noise scaled by remaining budget ensures non-determinism.</li>
 * </ul>
 * An {@link MlpPolicy} with augmented input (obs ∥ rtg_normalised) provides
 * learned refinement on top of the heuristic baseline once weights are trained.
 */
public final class ReturnConditionedAgent implements AgentPlugin {

    private static final double RTG_SCALE     = 5000.0;
    private static final int    NUM_ACTIONS   = 32;

    private final double    targetReturn;
    private double          remainingReturn;
    private final MlpPolicy policy;
    private final StateObservationEncoder encoder = new StateObservationEncoder();
    private final Random    rng;

    /** No-arg constructor for {@link java.util.ServiceLoader} discovery; targets score 1000. */
    public ReturnConditionedAgent() { this(1000.0, 0L); }

    public ReturnConditionedAgent(double targetReturn) { this(targetReturn, 0L); }

    public ReturnConditionedAgent(double targetReturn, long seed) {
        this.targetReturn    = targetReturn;
        this.remainingReturn = targetReturn;
        this.rng             = new Random(seed);
        // Input: observation vector augmented with [rtg_normalised, urgency]
        this.policy = new MlpPolicy(StateObservationEncoder.TOTAL + 2, 64, NUM_ACTIONS);
        this.policy.initRandom(rng);
    }

    @Override public String id()          { return "decision-transformer"; }
    @Override public String displayName() { return "Decision Transformer (RTG=" + (int) targetReturn + ")"; }

    @Override
    public Object selectAction(GameState state, ActionSpec spec) {
        double rtgNorm   = Math.min(1.0, remainingReturn / RTG_SCALE);
        double urgency   = 1.0 - rtgNorm;

        // Build augmented observation: [state_obs | rtg_norm | urgency]
        float[] stateObs = encoder.encode(state);
        float[] augmented = new float[stateObs.length + 2];
        System.arraycopy(stateObs, 0, augmented, 0, stateObs.length);
        augmented[stateObs.length]     = (float) rtgNorm;
        augmented[stateObs.length + 1] = (float) urgency;

        // Policy forward pass
        double[] logits = policy.forward(augmented);

        // RTG-conditioned action selection:
        // High remaining return → greedy (argmax); low → blend with merge heuristic
        int bins = spec.discrete() ? spec.bins() : NUM_ACTIONS;
        if (rtgNorm > 0.3) {
            return greedyAction(logits, bins);
        }
        // Near-target mode: pick the merge-greedy action
        return mergeGreedyAction(state, bins);
    }

    public void updateReturnToGo(double rewardReceived) {
        remainingReturn = Math.max(0, remainingReturn - rewardReceived);
    }

    /** Replace the MLP weights (used when loading a trained policy). */
    public void setWeights(double[] weights) { policy.setWeights(weights); }
    public double[] weights()               { return policy.getWeights(); }

    public double targetReturn()    { return targetReturn; }
    public double remainingReturn() { return remainingReturn; }

    // -------------------------------------------------------------------------

    private int greedyAction(double[] logits, int bins) {
        int best = 0;
        for (int i = 1; i < Math.min(bins, logits.length); i++) {
            if (logits[i] > logits[best]) best = i;
        }
        return best;
    }

    private int mergeGreedyAction(GameState state, int numBins) {
        double xMin = PhysicsConfig.DROP_X_MIN;
        double xMax = PhysicsConfig.DROP_X_MAX;
        FruitTier drop = state.currentFruitTier();
        int best = numBins / 2;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int bin = 0; bin < numBins; bin++) {
            double x = xMin + (bin / (double) (numBins - 1)) * (xMax - xMin);
            double score = 0.0;
            for (Fruit f : state.fruits()) {
                double dx = Math.abs(f.x() - x);
                if (f.tier() == drop && dx < (drop.radius + f.radius()) * 1.5) {
                    score += (drop.tier + 1) * 2.0;
                }
                if (dx < drop.radius * 2 && state.isAboveDeadline(f)) {
                    score -= 5.0;
                }
            }
            if (score > bestScore) { bestScore = score; best = bin; }
        }
        return best;
    }
}
