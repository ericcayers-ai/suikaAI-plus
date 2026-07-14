package dev.suika.ai;

import dev.suika.core.GameCore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlanningTest {

    @Test
    void greedyOnePlySelectsValidAction() {
        GreedyOnePlyAgent agent = new GreedyOnePlyAgent(8);
        GameCore core = new GameCore(5L);
        Object action = agent.selectAction(core, ActionSpec.discrete(8));
        int a = ((Number) action).intValue();
        assertTrue(a >= 0 && a < 8, "Action must be in [0, bins)");
    }

    @Test
    void greedyOnePlyDoesNotCrashOnNonEmptyBoard() {
        GreedyOnePlyAgent agent = new GreedyOnePlyAgent(16);
        GameCore core = new GameCore(22L);
        for (int i = 0; i < 5; i++) core.dropAndSettle(5.0);
        Object action = agent.selectAction(core, ActionSpec.discrete(16));
        assertNotNull(action);
    }

    @Test
    void greedyContinuousActionIsNormalizedNotWorldX() {
        GreedyOnePlyAgent agent = new GreedyOnePlyAgent(8);
        GameCore core = new GameCore(5L);
        Object action = agent.selectAction(core, ActionSpec.continuous(-1, 1));
        double a = ((Number) action).doubleValue();
        assertTrue(a >= -1.0 && a <= 1.0, "continuous action must be in [-1,1]; was " + a);
    }

    @Test
    void mctsSelectsValidActionOnFreshBoard() {
        MctsAgent agent = new MctsAgent(5, Math.sqrt(2), 3, 8);
        GameCore core = new GameCore(9L);
        Object action = agent.selectAction(core, ActionSpec.discrete(8));
        int a = ((Number) action).intValue();
        assertTrue(a >= 0 && a < 8);
    }

    @Test
    void policyValueStubReturnsCorrectShape() {
        PolicyValueNetwork net = new PolicyValueNetwork.UniformPrior();
        GameCore core = new GameCore(1L);
        PolicyValueNetwork.Output out = net.evaluate(core.getState(), 32);
        assertEquals(32, out.policyLogits().length);
        assertTrue(Double.isFinite(out.value()));
    }

    @Test
    void alphaZeroConfigDefaultsAreConsistent() {
        AlphaZeroConfig cfg = AlphaZeroConfig.defaults();
        assertTrue(cfg.rolloutsPerMove() > 0);
        assertTrue(cfg.explorationC() > 0);
        assertTrue(cfg.actionBins() > 0);
    }
}
