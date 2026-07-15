package dev.suika.game;

import dev.suika.ai.AgentPlugin;
import dev.suika.ai.BenchmarkSuite;
import dev.suika.ai.Leaderboard;
import dev.suika.ai.LeaderboardEntry;
import dev.suika.ai.RunHistoryStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * In-app bounded benchmark runner — same engines as {@link Bench} / CLI, shorter
 * defaults so the Lab Hub stays interactive. Results feed the durable leaderboard.
 */
public final class InAppBench {

    public record Result(String technique, double mean, double std, int n, double seconds) {}

    private InAppBench() {}

    /**
     * Runs a bounded suite (2 seeds × 1 episode × 80 steps by default) for one technique.
     */
    public static Result runBounded(AiTechnique tech, int seeds, int episodes, int steps, int rollouts) {
        java.util.List<Long> seedArr = truncSeeds(seeds);
        BenchmarkSuite suite = new BenchmarkSuite(seedArr, episodes, steps);
        PlaygroundConfig cfg = new PlaygroundConfig();
        cfg.technique = tech;
        cfg.rollouts = rollouts;
        cfg.actionBins = 32;
        cfg.maxThinkMs = 0;
        AgentPlugin agent = Agents.build(cfg);
        long t0 = System.nanoTime();
        LeaderboardEntry e = suite.evaluate(agent);
        double sec = (System.nanoTime() - t0) / 1e9;
        if (agent instanceof AutoCloseable c) {
            try { c.close(); } catch (Exception ignored) { }
        }
        try {
            Leaderboard board = RunHistoryStore.loadLeaderboard();
            board.submit(e);
            RunHistoryStore.saveLeaderboard(board);
        } catch (Exception ignored) { }
        return new Result(tech.display, e.meanScore(), e.stdDev(), e.episodeCount(), sec);
    }

    public static List<Result> runDefaults() {
        List<Result> out = new ArrayList<>();
        for (AiTechnique t : new AiTechnique[]{AiTechnique.GREEDY, AiTechnique.HEURISTIC, AiTechnique.MCTS}) {
            if (t.isDemonstrationSurrogate()) continue;
            out.add(runBounded(t, 2, 1, 80, 40));
        }
        return out;
    }

    public static String format(Result r) {
        return String.format(Locale.ROOT, "%-28s  mean=%6.1f ±%5.1f  (n=%d, %.1fs)",
                r.technique(), r.mean(), r.std(), r.n(), r.seconds());
    }

    private static java.util.List<Long> truncSeeds(int n) {
        java.util.List<Long> all = BenchmarkSuite.STANDARD_SEEDS;
        int k = Math.max(1, Math.min(n, all.size()));
        return all.subList(0, k);
    }
}
