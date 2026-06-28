package dev.suika.ai;

import dev.suika.core.GameState;
import dev.suika.env.StateObservationEncoder;

/**
 * Thin JVM interface for the policy-value network used by AlphaZero-style MCTS.
 *
 * <p>In production the implementation is either:
 * <ul>
 *   <li>A Python model called via the bridge (training), or</li>
 *   <li>An ONNX model loaded via DJL (inference at runtime for players).</li>
 * </ul>
 * The stub implementation here provides uniform priors + zero value so MCTS
 * degrades gracefully to vanilla Monte Carlo when no trained net is available.
 */
public interface PolicyValueNetwork {

    record Output(double[] policyLogits, double value) {}

    /**
     * Evaluate the board and return action-policy logits and a state-value estimate.
     *
     * @param state   current game state
     * @param numActions  number of discrete actions
     * @return {@link Output} with {@code policyLogits.length == numActions}
     */
    Output evaluate(GameState state, int numActions);

    // -------------------------------------------------------------------------
    // Uniform stub used when no trained net is available

    final class UniformStub implements PolicyValueNetwork {
        private final StateObservationEncoder encoder = new StateObservationEncoder();

        @Override
        public Output evaluate(GameState state, int numActions) {
            double[] logits = new double[numActions]; // all zeros → uniform softmax
            double value = 0.0;
            return new Output(logits, value);
        }
    }
}
