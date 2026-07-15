package dev.suika.game;

import dev.suika.ai.AgentPlugin;
import dev.suika.ai.GreedyOnePlyAgent;
import dev.suika.ai.HeuristicAgent;
import dev.suika.ai.MctsAgent;
import dev.suika.ai.ReturnConditionedAgent;

/**
 * Builds a JVM-native {@link AgentPlugin} for a technique. For techniques whose
 * full training lives in Python (PPO/MuZero) this prefers a slot {@code model.onnx}
 * via {@link OnnxAgent} (no Python at play time); otherwise returns an honest
 * JVM surrogate so the control center still has something to watch while training.
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
            case PPO        -> loadOnnxOrHeuristic(cfg, bins);

            // ---- Ensembles: composed agents (see EnsembleAgents.java), each wired to
            // its PlaygroundConfig customization knobs ----
            case ENS_MCTS_NET       -> new EnsembleAgents.NetGuidedMcts(
                    cfg.rollouts, bins, cfg.ensembleDonor(), cfg.ensembleDonorSlot, cfg.ensembleNetWeight());
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

    /**
     * PPO play path: first present {@code model.onnx} in the technique's slots, else
     * {@link HeuristicAgent} (surrogate — training still requires Python).
     */
    private static AgentPlugin loadOnnxOrHeuristic(PlaygroundConfig cfg, int bins) {
        int slot = ModelSlots.firstOnnxSlot(cfg.technique.id);
        if (slot >= 1) {
            OnnxAgent onnx = ModelSlots.tryLoadOnnxAgent(cfg.technique.id, slot, bins);
            if (onnx != null) return onnx;
        }
        return new HeuristicAgent();
    }
}
