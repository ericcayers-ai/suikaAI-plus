package dev.suika.game;

import dev.suika.ai.AgentPlugin;
import dev.suika.ai.GreedyOnePlyAgent;
import dev.suika.ai.HeuristicAgent;
import dev.suika.ai.MctsAgent;
import dev.suika.ai.ReturnConditionedAgent;

/**
 * Builds a JVM-native {@link AgentPlugin} for a technique. For techniques whose
 * full training lives in Python (PPO/MuZero) this returns the closest JVM-native
 * surrogate so the control center always has a live policy to play and graph while
 * the Python sidecar trains the real one.
 */
public final class Agents {

    private Agents() {}

    public static AgentPlugin build(PlaygroundConfig cfg) {
        int bins = cfg.actionBins;
        double c = Math.sqrt(2);
        return switch (cfg.technique) {
            case HEURISTIC  -> new HeuristicAgent();
            case GREEDY     -> new GreedyOnePlyAgent(bins, cfg.evalThreads());
            case MCTS       -> new MctsAgent(cfg.rollouts, c, 6, bins);
            case ALPHAZERO, MUZERO ->
                    new MctsAgent(Math.max(40, cfg.rollouts), c, 7, bins); // planning surrogate
            case DECISION_TRANSFORMER -> new ReturnConditionedAgent(cfg.targetReturn);
            case PPO        -> new HeuristicAgent(); // JVM surrogate until ONNX is loaded

            // ---- Ensembles: composed agents (see EnsembleAgents.java), each wired to
            // its PlaygroundConfig customization knobs ----
            case ENS_MCTS_NET       -> new EnsembleAgents.NetGuidedMcts(
                    cfg.rollouts, bins, cfg.ensembleDonor(), cfg.ensembleNetWeight());
            case ENS_MCTS_TIEBREAK  -> new EnsembleAgents.McTsGreedyTiebreak(
                    cfg.rollouts, bins, cfg.ensembleTieThreshold());
            case ENS_RTG_VERIFIED   -> new EnsembleAgents.ReturnConditionedVerified(cfg.targetReturn, bins);
            case ENS_ADAPTIVE_VOTE  -> new EnsembleAgents.AdaptiveVotingCommittee(
                    cfg.rollouts, bins, cfg.ensembleAdaptLr());
            case ENS_BANDIT         -> new EnsembleAgents.BanditMetaController(
                    cfg.rollouts, bins, cfg.ensembleUcbC());

            default -> new HeuristicAgent();
        };
    }
}
