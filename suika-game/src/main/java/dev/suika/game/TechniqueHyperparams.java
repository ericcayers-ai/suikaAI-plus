package dev.suika.game;

import dev.suika.ai.HyperparamSchema;

import java.util.Set;

/**
 * Consolidated technique hyperparameter tables and cycler logic shared by
 * {@link AiPlaygroundScreen} and {@link ControlCenterScreen}. Ladder arrays are
 * sourced from {@link HyperparamSchema} so CLI schemas and the windowed UI stay
 * aligned.
 */
public final class TechniqueHyperparams {

    private TechniqueHyperparams() {}

    public static final int[]    ROLLOUTS = HyperparamSchema.ROLLOUTS;
    public static final int[]    POP      = HyperparamSchema.POPULATION;
    public static final int[]    RETURNS  = HyperparamSchema.TARGET_RETURNS;
    public static final double[] LRS      = HyperparamSchema.LEARNING_RATES;

    /** Techniques whose primary cycler is "Rollouts". */
    public static final Set<AiTechnique> ROLLOUT_PARAM_TECHS = Set.of(
            AiTechnique.MCTS, AiTechnique.ALPHAZERO, AiTechnique.ENS_MCTS_NET,
            AiTechnique.ENS_MCTS_TIEBREAK, AiTechnique.ENS_ADAPTIVE_VOTE, AiTechnique.ENS_BANDIT);

    public static boolean paramApplicable(AiTechnique t) {
        if (ROLLOUT_PARAM_TECHS.contains(t)) return true;
        return switch (t) {
            case NEUROEVO, CMA_ES, PBT, DECISION_TRANSFORMER, DAGGER, BC, DQN, ENS_RTG_VERIFIED -> true;
            default -> false;
        };
    }

    public static String paramLabel(AiTechnique t) {
        if (ROLLOUT_PARAM_TECHS.contains(t)) return "Rollouts";
        return switch (t) {
            case NEUROEVO, CMA_ES, PBT                  -> "Population";
            case DECISION_TRANSFORMER, ENS_RTG_VERIFIED -> "Target return";
            case DAGGER, BC, DQN                        -> "Learning rate";
            default                                     -> "—";
        };
    }

    /** Shorter labels for the hotswap modal's tighter columns. */
    public static String paramLabelShort(AiTechnique t) {
        if (ROLLOUT_PARAM_TECHS.contains(t)) return "Rollouts";
        return switch (t) {
            case NEUROEVO, CMA_ES, PBT                  -> "Population";
            case DECISION_TRANSFORMER, ENS_RTG_VERIFIED -> "Return";
            case DAGGER, BC, DQN                        -> "LR";
            default                                     -> "—";
        };
    }

    public static String paramValue(PlaygroundConfig cfg) {
        AiTechnique t = cfg.technique;
        if (ROLLOUT_PARAM_TECHS.contains(t)) return Integer.toString(cfg.rollouts);
        return switch (t) {
            case NEUROEVO, CMA_ES, PBT                  -> Integer.toString(cfg.populationSize);
            case DECISION_TRANSFORMER, ENS_RTG_VERIFIED -> Integer.toString((int) cfg.targetReturn);
            case DAGGER, BC, DQN                        -> String.format("%.0e", cfg.learningRate);
            default                                     -> "—";
        };
    }

    public static void cycleParam(PlaygroundConfig cfg, int dir) {
        AiTechnique t = cfg.technique;
        if (ROLLOUT_PARAM_TECHS.contains(t)) {
            cfg.rollouts = cycleInt(ROLLOUTS, cfg.rollouts, dir);
            return;
        }
        switch (t) {
            case NEUROEVO, CMA_ES, PBT ->
                    cfg.populationSize = cycleInt(POP, cfg.populationSize, dir);
            case DECISION_TRANSFORMER, ENS_RTG_VERIFIED ->
                    cfg.targetReturn = cycleInt(RETURNS, (int) cfg.targetReturn, dir);
            case DAGGER, BC, DQN ->
                    cfg.learningRate = cycleDouble(LRS, cfg.learningRate, dir);
            default -> { }
        }
    }

    public static boolean evolutionApplicable(AiTechnique t) {
        return t.family == AiTechnique.Family.EVOLUTION;
    }

    public static int cycleInt(int[] opts, int cur, int d) {
        int idx = 0;
        for (int i = 0; i < opts.length; i++) if (opts[i] == cur) idx = i;
        return opts[Math.floorMod(idx + d, opts.length)];
    }

    public static double cycleDouble(double[] opts, double cur, int d) {
        int idx = 0;
        for (int i = 0; i < opts.length; i++) if (Math.abs(opts[i] - cur) < 1e-9) idx = i;
        return opts[Math.floorMod(idx + d, opts.length)];
    }

    /** Schema list for the selected technique — source for {@link TechniqueConfigPanel}. */
    public static java.util.List<HyperparamSchema> schemaFor(AiTechnique t) {
        return HyperparamSchema.forTechniqueId(t.id);
    }
}
