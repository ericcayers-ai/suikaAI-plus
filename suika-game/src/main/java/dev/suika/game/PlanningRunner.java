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
        s.add("games        " + gamesPlayed);
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

    @Override
    public String[] extendedStats() {
        java.util.List<String> s = new java.util.ArrayList<>();
        s.add("model        perfect simulator");
        s.add("action space " + cfg.actionBins + " drop columns");
        if (agent() instanceof MctsAgent) {
            s.add("budget       " + cfg.maxThinkMs + " ms / move");
            s.add("selection    UCB1 + heuristic rollouts");
        } else {
            s.add("policy       " + cfg.technique.kind);
        }
        s.add("doing now    " + cfg.technique.liveHint());
        return s.toArray(new String[0]);
    }

    @Override public LiveChart chart2()      { return gameScoreChart; }
    @Override public String    chart2Label() {
        return gameScoreChart.size() == 0 ? "game scores (game 1 in progress)"
                : "game scores  ·  last " + Math.round(gameScoreChart.latest());
    }
}
