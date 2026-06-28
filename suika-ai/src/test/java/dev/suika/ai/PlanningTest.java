package dev.suika.ai;

import dev.suika.core.GameCore;
import dev.suika.core.GameState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlanningTest {

    @Test
    void greedyOnePlySelectsValidAction() {
        GreedyOnePlyAgent agent = new GreedyOnePlyAgent(8);
        GameCore core = new GameCore(5L);
        GameState state = core.getState();
        Object action = agent.selectAction(state, ActionSpec.discrete(8));
        int a = ((Number) action).intValue();
        assertTrue(a >= 0 && a < 8, "Action must be in [0, bins)");
    }

    @Test
    void greedyOnePlyDoesNotCrashOnNonEmptyBoard() {
        GreedyOnePlyAgent agent = new GreedyOnePlyAgent(16);
        GameCore core = new GameCore(22L);
        for (int i = 0; i < 5; i++) core.dropAndSettle(5.0);
        // Now board has fruits — greedy agent should still work
        Object action = agent.selectAction(core.getState(), ActionSpec.discrete(16));
        assertNotNull(action);
    }

    @Test
    void mctsSelectsValidActionOnFreshBoard() {
        // Small rollout count to keep test fast
        MctsAgent agent = new MctsAgent(5, Math.sqrt(2), 3, 8);
        GameCore core = new GameCore(9L);
        Object action = agent.selectAction(core.getState(), ActionSpec.discrete(8));
        int a = ((Number) action).intValue();
        assertTrue(a >= 0 && a < 8);
    }

    @Test
    void policyValueStubReturnsCorrectShape() {
        PolicyValueNetwork net = new PolicyValueNetwork.UniformStub();
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
