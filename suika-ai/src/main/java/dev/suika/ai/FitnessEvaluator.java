package dev.suika.ai;

import dev.suika.core.GameCore;
import dev.suika.core.StepResult;
import dev.suika.env.ActionSpace;

/**
 * Evaluates an agent's fitness by running it for multiple seeded episodes.
 * Parallelism is handled by the caller (e.g. {@link GeneticTrainer} uses a thread pool).
 */
public final class FitnessEvaluator {

    private final int         episodesPerEval;
    private final int         maxStepsPerEpisode;
    private final ActionSpec  actionSpec;

    public FitnessEvaluator(int episodesPerEval, int maxStepsPerEpisode, int actionBins) {
        this.episodesPerEval    = episodesPerEval;
        this.maxStepsPerEpisode = maxStepsPerEpisode;
        this.actionSpec         = ActionSpec.discrete(actionBins);
    }

    /**
     * Run {@code agent} for {@code episodesPerEval} games starting at {@code baseSeed}.
     *
     * @return mean score across all episodes
     */
    public double evaluate(AgentPlugin agent, long baseSeed) {
        long totalScore = 0;
        for (int ep = 0; ep < episodesPerEval; ep++) {
            totalScore += runEpisode(agent, baseSeed + ep);
        }
        return (double) totalScore / episodesPerEval;
    }

    /** Run a single seeded episode and return its score. Public so callers can fan
     *  individual episodes out across a thread pool (simultaneous sims). */
    public long runSingleEpisode(AgentPlugin agent, long seed) {
        return runEpisode(agent, seed);
    }

    private long runEpisode(AgentPlugin agent, long seed) {
        GameCore core = new GameCore(seed);
        for (int step = 0; step < maxStepsPerEpisode && !core.isGameOver(); step++) {
            Object action = agent.selectAction(core.getState(), actionSpec);
            double dropX = actionSpec.toDropX(action,
                    dev.suika.core.PhysicsConfig.DROP_X_MIN,
                    dev.suika.core.PhysicsConfig.DROP_X_MAX);
            core.dropAndSettle(dropX);
        }
        agent.onEpisodeEnd(core.getScore(), core.getState().stepCount());
        return core.getScore();
    }
}
