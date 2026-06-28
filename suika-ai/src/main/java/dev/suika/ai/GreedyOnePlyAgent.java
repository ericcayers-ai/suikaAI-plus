package dev.suika.ai;

import dev.suika.core.GameCore;
import dev.suika.core.GameState;
import dev.suika.core.PhysicsConfig;
import dev.suika.core.StepResult;

/**
 * Greedy one-ply search: try every discretised drop position, simulate to settle,
 * pick the drop that maximises immediate score delta (ROADMAP §IV.1).
 *
 * <p>This is the first agent that exploits {@link GameCore#snapshot()} and sets
 * the bar for learned agents.
 */
public final class GreedyOnePlyAgent implements AgentPlugin {

    private final int actionBins;

    public GreedyOnePlyAgent(int actionBins) { this.actionBins = actionBins; }

    @Override public String id()          { return "greedy-1ply"; }
    @Override public String displayName() { return "Greedy One-Ply"; }

    @Override
    public Object selectAction(GameState state, ActionSpec spec) {
        // The caller must supply an active GameCore; we get the state from it.
        // Since AgentPlugin only receives GameState, we rebuild a core here.
        // A production wiring would pass the live core via a context object.
        GameCore liveCore = reconstructCore(state);

        int    bestAction = 0;
        double bestScore  = Double.NEGATIVE_INFINITY;

        for (int a = 0; a < actionBins; a++) {
            GameCore fork = liveCore.snapshot();
            double x = binToX(a);
            StepResult r = fork.dropAndSettle(x);
            double value = r.observation().score() - state.score();
            if (r.terminated()) value -= 10.0; // heavy penalty for game-over
            if (value > bestScore) { bestScore = value; bestAction = a; }
        }

        return spec.discrete() ? bestAction : binToX(bestAction);
    }

    private double binToX(int a) {
        return PhysicsConfig.DROP_X_MIN
                + a / (double) (actionBins - 1)
                * (PhysicsConfig.DROP_X_MAX - PhysicsConfig.DROP_X_MIN);
    }

    private GameCore reconstructCore(GameState state) {
        // Approximation: replay the state's stepCount worth of drops from seed
        GameCore core = new GameCore(state.rngSeed());
        java.util.Random r = new java.util.Random(state.rngSeed());
        for (long s = 0; s < state.stepCount() && !core.isGameOver(); s++) {
            double x = PhysicsConfig.DROP_X_MIN
                    + r.nextInt(actionBins) / (double) (actionBins - 1)
                    * (PhysicsConfig.DROP_X_MAX - PhysicsConfig.DROP_X_MIN);
            core.dropAndSettle(x);
        }
        return core;
    }
}
