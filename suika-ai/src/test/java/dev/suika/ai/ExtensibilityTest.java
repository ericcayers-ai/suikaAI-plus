package dev.suika.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExtensibilityTest {

    @Test
    void pluginRegistryLoadsAgents() {
        PluginRegistry reg = PluginRegistry.get();
        assertFalse(reg.agents().isEmpty(), "ServiceLoader must find at least one AgentPlugin");
    }

    @Test
    void pluginRegistryLoadsTrainers() {
        PluginRegistry reg = PluginRegistry.get();
        assertFalse(reg.trainers().isEmpty(), "ServiceLoader must find at least one TrainerPlugin");
    }

    @Test
    void pluginRegistryFindByIdRoundtrip() {
        PluginRegistry reg = PluginRegistry.get();
        AgentPlugin first = reg.agents().iterator().next();
        assertTrue(reg.findAgent(first.id()).isPresent());
    }

    @Test
    void pluginRegistryRuntimeRegistration() {
        PluginRegistry reg = PluginRegistry.get();
        AgentPlugin stub = new RandomAgent();
        reg.registerAgent(stub);
        assertTrue(reg.findAgent(stub.id()).isPresent());
    }

    @Test
    void benchmarkSuiteProducesEntry() {
        BenchmarkSuite suite = new BenchmarkSuite(List.of(42L), 1, 20);
        LeaderboardEntry entry = suite.evaluate(new RandomAgent());
        assertNotNull(entry);
        assertTrue(entry.meanScore() >= 0);
        assertEquals(1, entry.episodeCount());
    }

    @Test
    void leaderboardSortsAndRanks() {
        Leaderboard board = new Leaderboard();
        board.submit(new LeaderboardEntry("a", "Agent A", 100.0, 10.0, 5, List.of(), 1L));
        board.submit(new LeaderboardEntry("b", "Agent B", 200.0, 5.0, 5, List.of(), 2L));
        board.submit(new LeaderboardEntry("c", "Agent C", 50.0, 3.0, 5, List.of(), 3L));

        assertEquals(1, board.rankOf("b"), "Highest scorer must be rank 1");
        assertEquals(2, board.rankOf("a"));
        assertEquals(3, board.rankOf("c"));
    }

    @Test
    void leaderboardJsonlRoundtrip() {
        Leaderboard board = new Leaderboard();
        board.submit(new LeaderboardEntry("x", "Agent X", 42.5, 2.1, 3, List.of(1L), 999L));

        List<String> lines = board.toJsonLines();
        assertEquals(1, lines.size());

        Leaderboard restored = Leaderboard.fromJsonLines(lines);
        assertEquals(1, restored.size());
        assertEquals("x", restored.ranked().get(0).agentId());
        assertEquals(42.5, restored.ranked().get(0).meanScore(), 0.01);
    }
}
