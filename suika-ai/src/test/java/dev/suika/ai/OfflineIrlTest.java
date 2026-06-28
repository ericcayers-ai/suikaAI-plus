package dev.suika.ai;

import dev.suika.core.GameCore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OfflineIrlTest {

    @Test
    void preferenceCollectorTracksLabels() {
        PreferenceCollector pc = new PreferenceCollector();
        var pair = new PreferenceCollector.ClipPair(List.of(), List.of(), 100L, 200L);
        pc.preferB(pair);
        pc.preferA(pair);
        pc.preferEqual(pair);
        assertEquals(3, pc.size());
        assertEquals(1.0, pc.all().get(0).label(), 1e-9); // preferB
        assertEquals(0.0, pc.all().get(1).label(), 1e-9); // preferA
        assertEquals(0.5, pc.all().get(2).label(), 1e-9); // equal
    }

    @Test
    void inverseRlConfigMethodsAreCorrect() {
        assertEquals("gail", InverseRlConfig.gailDefaults().method());
        assertEquals("airl", InverseRlConfig.airlDefaults().method());
    }

    @Test
    void offlineRlConfigMethodsCorrect() {
        assertEquals("cql", OfflineRlConfig.cqlDefaults().method());
        assertEquals("decision_transformer", OfflineRlConfig.decisionTransformerDefaults().method());
    }

    @Test
    void returnConditionedAgentUpdatesRtg() {
        ReturnConditionedAgent agent = new ReturnConditionedAgent(1000.0);
        agent.updateReturnToGo(200.0);
        assertEquals(800.0, agent.remainingReturn(), 1e-9);
        agent.updateReturnToGo(900.0); // overshoots → clamped to 0
        assertEquals(0.0, agent.remainingReturn(), 1e-9);
    }

    @Test
    void returnConditionedAgentSelectsValidAction() {
        ReturnConditionedAgent agent = new ReturnConditionedAgent(500.0);
        GameCore core = new GameCore(1L);
        ActionSpec spec = ActionSpec.discrete(32);
        Object action = agent.selectAction(core.getState(), spec);
        int a = ((Number) action).intValue();
        assertTrue(a >= 0 && a < 32);
    }
}
