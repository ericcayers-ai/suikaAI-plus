package dev.suika.ai;

import dev.suika.core.GameState;

/**
 * Decision Transformer-style agent: conditioned on a target return (ROADMAP §IV.8).
 *
 * <p>The idea: "play to reach score X" — the agent is given a desired cumulative return
 * as additional context and predicts actions that achieve it. This is a generative,
 * sequence-modeling approach to RL rather than a value-based one.
 *
 * <p>The actual sequence model runs in Python; this interface exposes the API
 * from the JVM side. The stub returns a random action until a real model is loaded.
 */
public final class ReturnConditionedAgent implements AgentPlugin {

    private final double targetReturn;
    private double       remainingReturn;
    private final java.util.Random rng = new java.util.Random();

    /**
     * @param targetReturn  the score this agent is trying to achieve
     */
    public ReturnConditionedAgent(double targetReturn) {
        this.targetReturn   = targetReturn;
        this.remainingReturn = targetReturn;
    }

    @Override public String id()          { return "decision-transformer"; }
    @Override public String displayName() { return "Decision Transformer (RTG=" + (int) targetReturn + ")"; }

    @Override
    public Object selectAction(GameState state, ActionSpec spec) {
        // Stub: random action — real model queries Python sidecar with
        // (obs_history, action_history, return_to_go) as context
        return spec.discrete() ? rng.nextInt(spec.bins()) : 0.0;
    }

    public void updateReturnToGo(double rewardReceived) {
        remainingReturn = Math.max(0, remainingReturn - rewardReceived);
    }

    public double targetReturn()    { return targetReturn; }
    public double remainingReturn() { return remainingReturn; }
}
