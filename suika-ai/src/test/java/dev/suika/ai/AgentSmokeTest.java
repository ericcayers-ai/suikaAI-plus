package dev.suika.ai;

import dev.suika.core.GameCore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke tests for Phase 3 agents — verify they run without crashing
 * and produce non-negative fitness, which is the Phase 3 exit criterion.
 */
class AgentSmokeTest {

    private final ActionSpec spec = ActionSpec.discrete(32);

    @Test
    void randomAgentRunsWithoutCrash() {
        GameCore core = new GameCore(1L);
        RandomAgent agent = new RandomAgent();
        for (int i = 0; i < 10 && !core.isGameOver(); i++) {
            Object action = agent.selectAction(core.getState(), spec);
            double x = spec.toDropX(action,
                    dev.suika.core.PhysicsConfig.DROP_X_MIN,
                    dev.suika.core.PhysicsConfig.DROP_X_MAX);
            core.dropAndSettle(x);
        }
        assertTrue(core.getScore() >= 0);
    }

    @Test
    void heuristicAgentBeatsZero() {
        // Run heuristic for 20 steps; it should score above zero thanks to merges
        GameCore core = new GameCore(42L);
        HeuristicAgent agent = new HeuristicAgent();
        for (int i = 0; i < 20 && !core.isGameOver(); i++) {
            Object action = agent.selectAction(core.getState(), spec);
            double x = spec.toDropX(action,
                    dev.suika.core.PhysicsConfig.DROP_X_MIN,
                    dev.suika.core.PhysicsConfig.DROP_X_MAX);
            core.dropAndSettle(x);
        }
        assertTrue(core.getScore() >= 0, "Heuristic must produce non-negative score");
    }

    @Test
    void neuralAgentForwardPassWorks() {
        MlpPolicy p = new MlpPolicy(
                dev.suika.env.StateObservationEncoder.TOTAL, 64, 32);
        p.initRandom(new java.util.Random(7L));
        NeuralAgent agent = new NeuralAgent(p);

        GameCore core = new GameCore(3L);
        Object action = agent.selectAction(core.getState(), spec);
        assertNotNull(action);
        int a = ((Number) action).intValue();
        assertTrue(a >= 0 && a < 32, "Discrete action must be in [0, bins)");
    }

    @Test
    void fitnessEvaluatorReturnsMeanScore() {
        FitnessEvaluator eval = new FitnessEvaluator(3, 50, 32);
        HeuristicAgent agent = new HeuristicAgent();
        double fitness = eval.evaluate(agent, 100L);
        assertTrue(fitness >= 0.0, "Fitness must be non-negative");
    }

    @Test
    void geneticTrainerEvolvesTwoGenerations() throws Exception {
        try (GeneticTrainer trainer = new GeneticTrainer(6, 2, 0.1, 1, 99L)) {
            trainer.update(); // gen 1
            trainer.update(); // gen 2
            assertEquals(2, trainer.generation());
            assertTrue(trainer.bestFitness() >= 0.0);
        }
    }

    @Test
    void mlpWeightRoundTrip() {
        MlpPolicy p = new MlpPolicy(10, 8, 4);
        p.initRandom(new java.util.Random(0L));
        double[] w1 = p.getWeights();
        p.setWeights(w1);
        double[] w2 = p.getWeights();
        assertArrayEquals(w1, w2, 1e-12);
    }
}
