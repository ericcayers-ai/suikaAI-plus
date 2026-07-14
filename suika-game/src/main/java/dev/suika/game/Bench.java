package dev.suika.game;

import dev.suika.ai.AgentPlugin;
import dev.suika.ai.BenchmarkSuite;
import dev.suika.ai.LeaderboardEntry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Headless strength bench — plays fixed seeds for planning/ensemble techniques and
 * prints mean ± std. Invoked as documented in {@code docs/benchmarking.md}:
 *
 * <pre>
 *   ./gradlew :suika-app:installDist
 *   java -Dsteps=500 -Drollouts=80 -Deps=3 -Donly=MCTS,Greedy \
 *        -cp "suika-app/build/install/suika-app/lib/*" \
 *        dev.suika.game.Bench
 * </pre>
 */
public final class Bench {

    private Bench() {}

    public static void main(String[] args) {
        int steps    = Integer.getInteger("steps", 500);
        int rollouts = Integer.getInteger("rollouts", 80);
        int eps      = Integer.getInteger("eps", 3);
        String only  = System.getProperty("only", "");

        Set<String> filter = new LinkedHashSet<>();
        if (only != null && !only.isBlank()) {
            for (String p : only.split("[,;|]+")) {
                String t = p.trim().toLowerCase(Locale.ROOT);
                if (!t.isEmpty()) filter.add(t);
            }
        }

        List<AiTechnique> techniques = new ArrayList<>();
        for (AiTechnique t : AiTechnique.values()) {
            if (!isBenchable(t)) continue;
            if (!filter.isEmpty() && !matches(filter, t)) continue;
            techniques.add(t);
        }
        if (techniques.isEmpty()) {
            System.err.println("No techniques matched -Donly=" + only);
            System.exit(2);
        }

        BenchmarkSuite suite = new BenchmarkSuite(BenchmarkSuite.STANDARD_SEEDS, eps, steps);
        System.out.printf("Bench  steps=%d  rollouts=%d  eps=%d  seeds=%s%n",
                steps, rollouts, eps, BenchmarkSuite.STANDARD_SEEDS);

        for (AiTechnique tech : techniques) {
            PlaygroundConfig cfg = new PlaygroundConfig();
            cfg.technique = tech;
            cfg.rollouts = rollouts;
            cfg.actionBins = 32;
            cfg.maxThinkMs = 0; // full budget for headless strength measurement
            AgentPlugin agent = Agents.build(cfg);
            long t0 = System.nanoTime();
            LeaderboardEntry e = suite.evaluate(agent);
            double sec = (System.nanoTime() - t0) / 1e9;
            System.out.printf("%-32s  mean=%7.1f  ±%6.1f  (n=%d, %.1fs)%n",
                    tech.display, e.meanScore(), e.stdDev(), e.episodeCount(), sec);
            if (agent instanceof AutoCloseable c) {
                try { c.close(); } catch (Exception ignored) { }
            }
        }
    }

    /** Techniques with a real JVM planning/decision loop — skip pure trainers. */
    private static boolean isBenchable(AiTechnique t) {
        return switch (t) {
            case MCTS, ALPHAZERO, GREEDY, HEURISTIC,
                 ENS_ADAPTIVE_VOTE, ENS_BANDIT, ENS_MCTS_NET,
                 ENS_MCTS_TIEBREAK, ENS_RTG_VERIFIED,
                 DECISION_TRANSFORMER, MUZERO, PPO -> true;
            default -> false; // evolution/imitation/DQN need a trainer session
        };
    }

    private static boolean matches(Set<String> filter, AiTechnique t) {
        String id = t.id.toLowerCase(Locale.ROOT);
        String display = t.display.toLowerCase(Locale.ROOT);
        String compact = display.replaceAll("[^a-z0-9]+", "");
        for (String f : filter) {
            String fc = f.replaceAll("[^a-z0-9]+", "");
            if (id.equals(f) || id.contains(f) || display.contains(f) || compact.contains(fc))
                return true;
        }
        return false;
    }
}
