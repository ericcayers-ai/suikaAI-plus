package dev.suika.ai;

import dev.suika.core.GameState;
import dev.suika.env.ObservationMode;
import dev.suika.env.StateObservationEncoder;

/**
 * Agent backed by an {@link MlpPolicy}, encoding state with {@link StateObservationEncoder}.
 * Used by neuroevolution trainers as a genome-phenotype pairing.
 */
public final class NeuralAgent implements AgentPlugin {

    private final MlpPolicy          policy;
    private final StateObservationEncoder encoder = new StateObservationEncoder();

    public NeuralAgent(MlpPolicy policy) { this.policy = policy; }

    @Override public String id()          { return "neural-mlp"; }
    @Override public String displayName() { return "Neural MLP"; }

    @Override
    public Object selectAction(GameState state, ActionSpec spec) {
        float[] obs = encoder.encode(state);
        if (spec.discrete()) return policy.greedyAction(obs);
        // Continuous: squash output[0] to [-1, 1]
        return Math.tanh(policy.forward(obs)[0]);
    }

    public MlpPolicy policy() { return policy; }
}
