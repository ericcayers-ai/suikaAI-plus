package dev.suika.game;

import dev.suika.ai.AgentPlugin;
import dev.suika.ai.GenerativeModelBridge;
import dev.suika.ai.GreedyOnePlyAgent;
import dev.suika.ai.HeuristicAgent;
import dev.suika.ai.MctsAgent;
import dev.suika.ai.RandomAgent;
import dev.suika.ai.ReturnConditionedAgent;
import dev.suika.core.GameState;
import dev.suika.ai.ActionSpec;

/**
 * Builds a JVM-native {@link AgentPlugin} for a technique. For techniques whose
 * full training lives in Python (PPO/DQN/SAC/MuZero/Dreamer/GAIL/Offline) this
 * returns the closest JVM-native surrogate so the control center always has a live
 * policy to play and graph while the Python sidecar trains the real one.
 */
public final class Agents {

    private Agents() {}

    public static AgentPlugin build(PlaygroundConfig cfg) {
        int bins = cfg.actionBins;
        double c = Math.sqrt(2);
        return switch (cfg.technique) {
            case RANDOM     -> new RandomAgent();
            case HEURISTIC  -> new HeuristicAgent();
            case GREEDY     -> new GreedyOnePlyAgent(bins, cfg.evalThreads());
            case MCTS       -> new MctsAgent(cfg.rollouts, c, 6, bins);
            case ALPHAZERO, MUZERO, DREAMER, SELF_PLAY ->
                    new MctsAgent(Math.max(40, cfg.rollouts), c, 7, bins); // planning surrogate
            case DIFFUSION  -> new GenerativeAgent(GenerativeModelBridge.ModelType.DIFFUSION_POLICY);
            case FLOW       -> new GenerativeAgent(GenerativeModelBridge.ModelType.FLOW_MATCHING);
            case DECISION_TRANSFORMER, OFFLINE_RL -> new ReturnConditionedAgent(cfg.targetReturn);
            case PPO, DQN, SAC, GAIL -> new HeuristicAgent(); // JVM surrogate until ONNX is loaded

            // ---- Ensemble: composed agents (see EnsembleAgents.java) ----
            case ENS_MCTS_NET       -> new EnsembleAgents.NetGuidedMcts(cfg.rollouts, bins);
            case ENS_GREEDY_GUARD   -> new EnsembleAgents.GreedyGuardedPolicy(bins);
            case ENS_MCTS_TIEBREAK  -> new EnsembleAgents.McTsGreedyTiebreak(cfg.rollouts, bins);
            case ENS_VOTING         -> new EnsembleAgents.VotingCommittee(cfg.rollouts, bins);
            case ENS_EVOLVED_MCTS   -> new EnsembleAgents.EvolvedNetMcts(cfg.rollouts, bins);
            case ENS_IMITATION_MCTS -> new EnsembleAgents.ImitationBlendedMcts(cfg.rollouts, bins);
            case ENS_RTG_VERIFIED   -> new EnsembleAgents.ReturnConditionedVerified(cfg.targetReturn, bins);
            case ENS_GENERATIVE_GREEDY -> new EnsembleAgents.GenerativeGreedyFilter(bins);
            case ENS_ADAPTIVE_VOTE  -> new EnsembleAgents.AdaptiveVotingCommittee(cfg.rollouts, bins);
            case ENS_BANDIT         -> new EnsembleAgents.BanditMetaController(cfg.rollouts, bins);

            default -> new HeuristicAgent();
        };
    }

    /** Adapter exposing a {@link GenerativeModelBridge} as an {@link AgentPlugin}. */
    public static final class GenerativeAgent implements AgentPlugin {
        private final GenerativeModelBridge bridge;
        private volatile int lastBin = -1;
        private volatile int lastBins = 32;
        public GenerativeAgent(GenerativeModelBridge.ModelType type) {
            this.bridge = new GenerativeModelBridge(type, 7L);
        }
        @Override public String id()          { return "generative-" + bridge.type(); }
        @Override public String displayName() { return bridge.type().toString(); }
        @Override public Object selectAction(GameState state, ActionSpec spec) {
            int bins = spec.discrete() ? spec.bins() : 32;
            int bin = bridge.sampleAction(state, bins);
            lastBin = bin; lastBins = bins;
            return bin;
        }
        /** Last sampled drop column (−1 before the first sample). */
        public int lastBin()  { return lastBin; }
        public int lastBins() { return lastBins; }
        /** Number of denoise / flow steps the sampler genuinely refines over. */
        public int steps() { return bridge.steps(); }
        /** Every step's distribution from the last sample — see {@link GenerativeModelBridge#lastStepHistory()}. */
        public double[][] lastStepHistory() { return bridge.lastStepHistory(); }
    }
}
