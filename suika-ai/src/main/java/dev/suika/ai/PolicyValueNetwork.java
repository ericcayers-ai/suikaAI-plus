package dev.suika.ai;

import dev.suika.core.GameState;
import dev.suika.env.StateObservationEncoder;

import java.util.Random;

/**
 * Thin JVM interface for the policy-value network used by AlphaZero-style MCTS.
 *
 * <p>In production the implementation is either:
 * <ul>
 *   <li>A Python model called via the bridge (training), or</li>
 *   <li>An ONNX model loaded via DJL (inference at runtime for players).</li>
 * </ul>
 * {@link MlpPolicyValueNetwork} provides a fully functional JVM-native implementation
 * that is trainable via neuroevolution. {@link UniformPrior} is the zero-parameter
 * fallback that makes MCTS degrade to plain Monte Carlo rollouts.
 */
public interface PolicyValueNetwork {

    record Output(double[] policyLogits, double value) {}

    /**
     * Evaluate the board and return action-policy logits and a state-value estimate.
     *
     * @param state      current game state
     * @param numActions number of discrete actions
     * @return {@link Output} with {@code policyLogits.length == numActions}
     */
    Output evaluate(GameState state, int numActions);

    // -------------------------------------------------------------------------

    /**
     * JVM-native policy-value network backed by an {@link MlpPolicy}.
     *
     * <p>Architecture: {@code StateObservationEncoder → hidden → [policy logits | value]}.
     * The last output neuron is used as the value head; the first {@code numActions}
     * neurons are the policy logits. Weights are initialised randomly and are
     * trainable via {@link GeneticTrainer} or {@link CmaEsTrainer}.
     */
    final class MlpPolicyValueNetwork implements PolicyValueNetwork {

        private static final int HIDDEN = 128;

        private final StateObservationEncoder encoder = new StateObservationEncoder();
        private final int    numActions;
        private final MlpPolicy policy;

        public MlpPolicyValueNetwork(int numActions, long seed) {
            this.numActions = numActions;
            this.policy = new MlpPolicy(StateObservationEncoder.TOTAL, HIDDEN, numActions + 1);
            this.policy.initRandom(new Random(seed));
        }

        public MlpPolicyValueNetwork(int numActions, double[] weights) {
            this.numActions = numActions;
            this.policy = new MlpPolicy(StateObservationEncoder.TOTAL, HIDDEN, numActions + 1);
            this.policy.setWeights(weights);
        }

        @Override
        public Output evaluate(GameState state, int numActions) {
            float[] obs    = encoder.encode(state);
            double[] out   = policy.forward(obs);
            double[] logits = new double[this.numActions];
            System.arraycopy(out, 0, logits, 0, this.numActions);
            double value = out[this.numActions];
            return new Output(logits, value);
        }

        public double[] weights() { return policy.getWeights(); }
        public void setWeights(double[] w) { policy.setWeights(w); }
    }

    /**
     * Uniform-prior fallback: all-zeros logits and zero value.
     * Makes MCTS fall back to plain Monte Carlo when no trained net is available.
     */
    final class UniformPrior implements PolicyValueNetwork {
        @Override
        public Output evaluate(GameState state, int numActions) {
            return new Output(new double[numActions], 0.0);
        }
    }
}
