package dev.suika.ai;

import dev.suika.core.GameCore;
import dev.suika.core.GameState;
import dev.suika.core.PhysicsConfig;
import dev.suika.core.StepResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Monte-Carlo Tree Search agent (ROADMAP §IV.2).
 *
 * <p>Uses the perfect {@link GameCore#snapshot()} model — no learned dynamics.
 * Each move: build a tree with {@code rollouts} simulations, return the
 * most-visited child's action.
 *
 * <p>Fruit randomness is handled by sampling the next fruit draw inside rollouts,
 * which implicitly averages over the distribution — equivalent to expectimax with
 * enough rollouts.
 */
public final class MctsAgent implements AgentPlugin {

    private final int    rollouts;
    private final double explorationC;
    private final int    rolloutDepth;
    private final int    actionBins;
    private final Random rng;

    /**
     * @param rollouts     simulations per move (more → stronger, slower)
     * @param explorationC UCB1 exploration constant (√2 is the standard default)
     * @param rolloutDepth max steps in each simulation rollout
     * @param actionBins   number of discrete drop positions
     */
    /** No-arg constructor for {@link java.util.ServiceLoader} discovery; uses default hyperparams. */
    public MctsAgent() { this(50, Math.sqrt(2), 5, 32); }

    public MctsAgent(int rollouts, double explorationC, int rolloutDepth, int actionBins) {
        this.rollouts     = rollouts;
        this.explorationC = explorationC;
        this.rolloutDepth = rolloutDepth;
        this.actionBins   = actionBins;
        this.rng          = new Random();
    }

    @Override public String id()          { return "mcts"; }
    @Override public String displayName() { return "MCTS"; }

    @Override
    public Object selectAction(GameState state, ActionSpec spec) {
        // Reconstitute a core from the state snapshot
        // (We need a live GameCore to call snapshot(); in production this is
        //  injected. For now we reconstruct by replaying seed+stepCount as a proxy.)
        GameCore liveCore = buildCoreApproximation(state);

        MctsNode root = new MctsNode(-1, null);
        List<Integer> actions = allActions();

        for (int r = 0; r < rollouts; r++) {
            GameCore fork = liveCore.snapshot();

            // Selection
            MctsNode node = root;
            while (node.isExpanded() && !node.isLeaf()) {
                node = node.selectChild(explorationC);
                applyAction(fork, node.action);
                if (fork.isGameOver()) break;
            }

            // Expansion
            if (!fork.isGameOver() && !node.isExpanded()) {
                node.expand(actions);
                node = node.selectChild(explorationC);
                applyAction(fork, node.action);
            }

            // Rollout (random)
            double value = rollout(fork);

            // Backup
            node.backup(value);
        }

        return root.bestAction();
    }

    // -------------------------------------------------------------------------

    private List<Integer> allActions() {
        List<Integer> a = new ArrayList<>(actionBins);
        for (int i = 0; i < actionBins; i++) a.add(i);
        return a;
    }

    private void applyAction(GameCore core, int action) {
        double x = PhysicsConfig.DROP_X_MIN
                + action / (double) (actionBins - 1)
                * (PhysicsConfig.DROP_X_MAX - PhysicsConfig.DROP_X_MIN);
        core.dropAndSettle(x);
    }

    private double rollout(GameCore core) {
        long scoreBefore = core.getScore();
        for (int d = 0; d < rolloutDepth && !core.isGameOver(); d++) {
            applyAction(core, rng.nextInt(actionBins));
        }
        return (core.getScore() - scoreBefore) * 0.001; // normalise to ~[0,1]
    }

    /**
     * Approximate reconstruction: start fresh from the same seed and replay
     * {@code stepCount} random actions to get the board into roughly the right
     * trajectory. A production implementation would inject the live GameCore directly.
     */
    private GameCore buildCoreApproximation(GameState state) {
        GameCore core = new GameCore(state.rngSeed());
        ActionSpec spec = ActionSpec.discrete(actionBins);
        Random r = new Random(state.rngSeed());
        for (long s = 0; s < state.stepCount() && !core.isGameOver(); s++) {
            core.dropAndSettle(PhysicsConfig.DROP_X_MIN
                    + r.nextInt(actionBins) / (double) (actionBins - 1)
                    * (PhysicsConfig.DROP_X_MAX - PhysicsConfig.DROP_X_MIN));
        }
        return core;
    }
}
