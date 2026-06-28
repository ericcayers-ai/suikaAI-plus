package dev.suika.env;

import dev.suika.core.StepResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SuikaEnvTest {

    private SuikaEnv makeEnv(ObservationMode mode) {
        return new SuikaEnv(mode,
                new ActionSpace.Discrete(32),
                RewardConfig.defaultConfig());
    }

    @Test
    void stateEncoderProducesCorrectSize() {
        SuikaEnv env = makeEnv(ObservationMode.STATE);
        env.reset(1L);
        float[] obs = env.encode(env.observation());
        assertEquals(StateObservationEncoder.TOTAL, obs.length);
    }

    @Test
    void hybridEncoderProducesCorrectSize() {
        SuikaEnv env = makeEnv(ObservationMode.HYBRID);
        env.reset(2L);
        float[] obs = env.encode(env.observation());
        int expected = HybridObservationEncoder.CHANNELS
                * HybridObservationEncoder.GRID_H
                * HybridObservationEncoder.GRID_W;
        assertEquals(expected, obs.length);
    }

    @Test
    void stepReturnsValidReward() {
        SuikaEnv env = makeEnv(ObservationMode.STATE);
        env.reset(42L);
        for (int i = 0; i < 10; i++) {
            if (env.isGameOver()) break;
            StepResult r = env.step(16); // middle bin
            assertTrue(Double.isFinite(r.reward()), "Reward must be finite");
        }
    }

    @Test
    void discreteActionMapsToValidX() {
        SuikaEnv env = makeEnv(ObservationMode.STATE);
        env.reset(3L);
        // action 0 → leftmost, action 31 → rightmost — neither should crash
        env.step(0);
        env.reset(3L);
        env.step(31);
    }

    @Test
    void forkIsIndependent() {
        SuikaEnv original = makeEnv(ObservationMode.STATE);
        original.reset(10L);
        original.step(10);

        SuikaEnv fork = original.fork();
        fork.step(5);
        original.step(25);

        // Both should remain valid after diverging
        assertNotNull(original.observation());
        assertNotNull(fork.observation());
    }

    @Test
    void vectorEnvRunsParallel() throws Exception {
        var vec = new VectorEnv(8, ObservationMode.STATE,
                new ActionSpace.Discrete(32), RewardConfig.defaultConfig());
        float[][] obs = vec.reset(i -> 100L + i);
        assertEquals(8, obs.length);
        for (float[] o : obs) assertEquals(StateObservationEncoder.TOTAL, o.length);

        double[] actions = new double[8];
        for (int i = 0; i < 8; i++) actions[i] = 16;
        VectorEnv.VecStepResult res = vec.step(actions);
        assertEquals(8, res.numEnvs());
        vec.close();
    }

    @Test
    void composableRewardBreakdownSumsToTotal() {
        var reward = new ComposableReward(RewardConfig.defaultConfig());
        SuikaEnv env = makeEnv(ObservationMode.STATE);
        env.reset(7L);
        // Simulate a few steps using the core directly
        var core = new dev.suika.core.GameCore(7L);
        for (int i = 0; i < 5; i++) {
            if (core.isGameOver()) break;
            var raw = core.dropAndSettle(5.0);
            var rb  = reward.compute(raw);
            double sumTerms = rb.terms().values().stream().mapToDouble(Double::doubleValue).sum();
            assertEquals(rb.total(), sumTerms, 1e-9, "Terms must sum to total");
        }
    }
}
