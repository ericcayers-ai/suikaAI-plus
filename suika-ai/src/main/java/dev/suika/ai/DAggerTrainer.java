package dev.suika.ai;

import dev.suika.core.GameCore;
import dev.suika.core.GameState;
import dev.suika.env.StateObservationEncoder;

import java.util.ArrayList;
import java.util.List;

/**
 * DAgger (Dataset Aggregation) imitation learning — ROADMAP §IV.6.
 *
 * <p>Iteratively:
 * <ol>
 *   <li>Roll out the current policy in the game environment.</li>
 *   <li>Query the expert (MCTS or human) to label the visited states.</li>
 *   <li>Add labeled transitions to the aggregated dataset.</li>
 *   <li>Retrain via Behavioral Cloning on the full aggregated dataset.</li>
 * </ol>
 * Using MCTS-as-expert eliminates the need for ongoing human labelling once a
 * strong planning agent is available.
 */
public final class DAggerTrainer {

    private final DemoDataset              aggregatedDataset;
    private final BehavioralCloningTrainer bcTrainer;
    private final AgentPlugin              expert;
    private final int                      actionBins;
    private final int                      rolloutLength;
    private final int                      bcStepsPerIter;
    private final StateObservationEncoder  encoder = new StateObservationEncoder();

    private int iteration = 0;

    /**
     * @param expert         the expert policy (e.g., {@link MctsAgent} or {@link HeuristicAgent})
     * @param actionBins     number of discrete drop positions
     * @param rolloutLength  steps rolled out per DAgger iteration
     * @param bcSteps        BC update steps per DAgger iteration
     * @param learningRate   BC learning rate
     */
    public DAggerTrainer(AgentPlugin expert, int actionBins,
                         int rolloutLength, int bcSteps, double learningRate) {
        this.expert           = expert;
        this.actionBins       = actionBins;
        this.rolloutLength    = rolloutLength;
        this.bcStepsPerIter   = bcSteps;
        this.aggregatedDataset = new DemoDataset(42L);
        this.bcTrainer        = new BehavioralCloningTrainer(aggregatedDataset, learningRate, 32);
    }

    /**
     * Run one DAgger iteration: rollout → expert label → BC update.
     *
     * @param seed  seed for this iteration's game
     */
    public void runIteration(long seed) {
        ActionSpec spec = ActionSpec.discrete(actionBins);
        NeuralAgent currentPolicy = bcTrainer.trainedAgent();

        GameCore core = new GameCore(seed);
        List<Demonstration> newDemos = new ArrayList<>();

        for (int step = 0; step < rolloutLength && !core.isGameOver(); step++) {
            GameState state = core.getState();
            float[] obs = encoder.encode(state);

            // Expert labels this state regardless of which action we take
            Object expertAction = expert.selectAction(state, spec);
            int expertA = ((Number) expertAction).intValue();

            // Roll out using the current learner policy (to collect on-policy states)
            Object learnerAction = currentPolicy.selectAction(state, spec);
            double x = spec.toDropX(learnerAction,
                    dev.suika.core.PhysicsConfig.DROP_X_MIN,
                    dev.suika.core.PhysicsConfig.DROP_X_MAX);

            var result = core.dropAndSettle(x);
            double reward = result.mergesThisStep().stream()
                    .mapToLong(dev.suika.core.MergeEvent::scoreAwarded).sum();

            newDemos.add(new Demonstration(obs, expertA, reward, result.terminated()));
        }

        // Aggregate
        aggregatedDataset.addAll(newDemos);

        // BC update
        for (int i = 0; i < bcStepsPerIter; i++) bcTrainer.update();
        iteration++;
    }

    public NeuralAgent bestAgent()     { return bcTrainer.trainedAgent(); }
    public int         iteration()     { return iteration; }
    public int         datasetSize()   { return aggregatedDataset.size(); }
}
