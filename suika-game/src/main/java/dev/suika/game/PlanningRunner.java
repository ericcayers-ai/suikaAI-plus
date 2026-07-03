package dev.suika.game;

import dev.suika.ai.GreedyOnePlyAgent;
import dev.suika.ai.MctsAgent;

/**
 * Control center for the baseline & planning techniques (Random, Heuristic, Greedy,
 * MCTS, AlphaZero search). The agent plays live; diagnostics expose move latency and,
 * for MCTS/Greedy, the parallel search fan-out and visit/score distribution.
 */
public final class PlanningRunner extends AgentRunner {

    public PlanningRunner(SuikaGame game, PlaygroundConfig cfg) { super(game, cfg); }

    @Override public String title()    { return cfg.technique.display; }
    @Override public String subtitle() {
        return cfg.technique.category + "  ·  " + cfg.technique.envBadge()
                + (thinking() ? "  ·  thinking..." : "  ·  playing");
    }

    private double dropsPerMin() {
        double mins = elapsedSeconds() / 60.0;
        return mins > 0.01 ? drops / mins : 0;
    }

    @Override
    public String[] stats() {
        java.util.List<String> s = new java.util.ArrayList<>();
        s.add("score        " + core.getScore());
        s.add("best         " + bestScore());
        s.add("games        " + gamesPlayed);
        s.add("drops        " + drops + "  (" + String.format("%.0f", dropsPerMin()) + "/min)");
        s.add("think        " + thinkMs() + " ms");
        s.add("columns      " + cfg.actionBins);
        if (agent() instanceof MctsAgent) {
            s.add("rollouts     " + cfg.rollouts + " / move"
                    + (parallelWorkers() > 1 ? " x " + parallelWorkers() + " trees" : "  ·  1 tree"));
        } else if (agent() instanceof GreedyOnePlyAgent g && g.threads() > 1) {
            s.add("eval         " + cfg.actionBins + " columns / " + g.threads() + " threads");
        }
        s.add("speed        " + cfg.speedLabel());
        return s.toArray(new String[0]);
    }

    /** Actual rollouts executed for the last move — from the visit-count bars, which sum
     *  to exactly the number of simulations run (each rollout increments one leaf's
     *  count on backup). Can be less than {@code cfg.rollouts} if the ms budget cut the
     *  search short, or more (x trees) when root-parallel search combined several trees. */
    private long actualSimulatedFutures() {
        int[] bars = columnBars();
        if (bars == null) return 0;
        long sum = 0;
        for (int v : bars) sum += v;
        return sum;
    }

    // The landscape panel (ControlCenterScreen.panelBounds()) scrolls with the mouse
    // wheel once stats() + extendedStats() exceed its visible height, so there's no
    // hard line cap any more — MCTS/AlphaZero's explainer block below runs well past
    // the panel's ~22-line unscrolled window and that's fine, it just scrolls.
    @Override
    public String[] extendedStats() {
        java.util.List<String> s = new java.util.ArrayList<>();
        s.add("model        perfect simulator (exact physics, not learned)");
        s.add("action space " + cfg.actionBins + " drop columns");
        if (agent() instanceof MctsAgent m) {
            int trees = Math.max(1, parallelWorkers());
            s.add("budget       " + cfg.maxThinkMs + " ms/move — a time cutoff, not a rollout");
            s.add("             count; a slow move just completes fewer of them");
            s.add("simulated    " + actualSimulatedFutures() + " futures this move ("
                    + cfg.rollouts + " rollouts x " + trees + (trees == 1 ? " tree" : " trees") + ")");
            s.add("selection    UCB1 — mostly the best column so far, sometimes");
            s.add("             an under-explored one, " + m.rolloutDepth() + "-step heuristic rollouts");
            s.add("why rollouts more = better moves but slower — a real trade-off");
            s.add("             (cycle Rollouts in SETUP, watch think-time move)");
            s.add("compute      100% CPU — no GPU path here (only Python techniques");
            s.add("             like PPO can actually use one)");
        } else {
            s.add("policy       " + cfg.technique.kind);
        }
        // Ensembles: an explicit member manifest plus, for the learning ones
        // (adaptive committee / bandit), their live trust statistics.
        if (cfg.technique.isEnsemble()) {
            String[] members = cfg.technique.ensembleMembers();
            for (int i = 0; i < members.length; i++) {
                s.add((i == 0 ? "uses         " : "             ") + members[i]);
            }
            if (agent() instanceof EnsembleAgents.HasLearnedState h) {
                java.util.Collections.addAll(s, h.learnedStateLines());
            }
            if (agent() instanceof EnsembleAgents.NetGuidedMcts n) {
                s.add("donor        " + n.donor.display + (n.donorTrained ? " (trained save)" : " (untrained — train it first)"));
                s.add("net weight   " + Math.round(cfg.ensembleNetWeight() * 100) + "% of the final blend");
            }
        }
        s.add("doing now    " + cfg.technique.liveHint());
        s.add("tendency     " + tendencyLabel());
        return s.toArray(new String[0]);
    }

    @Override public LiveChart chart2()      { return gameScoreChart; }
    @Override public String    chart2Label() {
        return gameScoreChart.size() == 0 ? "game scores (game 1 in progress)"
                : "game scores  ·  last " + Math.round(gameScoreChart.latest());
    }
}
