package dev.suika.game;

import dev.suika.ai.MctsAgent;

/**
 * Control center for the baseline & planning techniques (Random, Heuristic, Greedy,
 * MCTS, AlphaZero search). The agent plays live; diagnostics expose move latency and,
 * for MCTS, the rollout budget and visit distribution.
 */
public final class PlanningRunner extends AgentRunner {

    public PlanningRunner(SuikaGame game, PlaygroundConfig cfg) { super(game, cfg); }

    @Override public String title()    { return cfg.technique.display; }
    @Override public String subtitle() {
        return cfg.technique.category + "  ·  " + cfg.technique.envBadge()
                + (thinking() ? "  ·  thinking..." : "  ·  playing");
    }

    @Override
    public String[] stats() {
        java.util.List<String> s = new java.util.ArrayList<>();
        s.add("score        " + core.getScore());
        s.add("best         " + bestScore());
        s.add("drops        " + drops);
        s.add("think        " + thinkMs() + " ms");
        s.add("columns      " + cfg.actionBins);
        if (agent() instanceof MctsAgent) {
            s.add("rollouts     " + cfg.rollouts + " / move");
            s.add("planner      perfect-model MCTS");
        }
        s.add("speed        " + cfg.speedLabel());
        return s.toArray(new String[0]);
    }

    @Override public String chart2Label() { return null; }
}
