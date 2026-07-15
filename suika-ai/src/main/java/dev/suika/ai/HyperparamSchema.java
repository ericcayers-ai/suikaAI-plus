package dev.suika.ai;

import java.util.List;
import java.util.Map;

/**
 * Schema-driven hyperparameter descriptor for Researcher mode and the shared
 * technique configuration panel.
 *
 * <p>The UI generates a settings panel from a list of these — add a new algorithm,
 * get its panel for free. Option ladders shared with the LibGDX Playground /
 * Control Center cyclers live here so CLI presets and the windowed UI cannot drift.
 *
 * <p>Previously lived in {@code dev.suika.app}; relocated so {@code suika-game} can
 * drive the shared panel without depending on the app module.
 */
public record HyperparamSchema(
        String  key,
        String  displayName,
        Type    type,
        Object  defaultValue,
        Object  min,
        Object  max,
        String  helpText,
        boolean requiresRestart,
        Object[] enumOptions
) {
    public enum Type { INT, DOUBLE, BOOLEAN, STRING, ENUM }

    public HyperparamSchema {
        if (enumOptions == null) enumOptions = new Object[0];
    }

    /** Backward-compatible factory (no enum ladder). */
    public HyperparamSchema(String key, String displayName, Type type,
                            Object defaultValue, Object min, Object max,
                            String helpText, boolean requiresRestart) {
        this(key, displayName, type, defaultValue, min, max, helpText, requiresRestart, new Object[0]);
    }

    // -------------------------------------------------------------------------
    // Shared discrete ladders (Playground / Control Center cyclers)
    // -------------------------------------------------------------------------

    public static final int[]    ROLLOUTS = {40, 80, 150, 300, 600, 1200, 2400};
    public static final int[]    POPULATION = {16, 24, 40, 64, 128, 256, 512, 1000};
    public static final int[]    TARGET_RETURNS = {1000, 2000, 4000};
    public static final double[] LEARNING_RATES = {1e-3, 3e-3, 1e-2};
    public static final int[]    SIMS_PER_GEN = {1, 2, 3, 5, 8, 16};
    public static final int[]    GHOST_CULL = {1, 2, 3, 5, 8, 12};
    public static final int[]    ELITE_VIEWS = {1, 2, 3, 4, 6, 8, 10, 12, 16};
    public static final double[] MUTATION_SIGMA = {0.02, 0.05, 0.10, 0.20, 0.30};
    public static final double[] NET_WEIGHT = {0.1, 0.3, 0.5, 0.7, 0.9};
    public static final double[] TIE_THRESHOLD = {0.70, 0.85, 0.95};
    public static final double[] UCB_C = {0.7, 1.4, 2.0};
    public static final double[] ADAPT_LR = {0.02, 0.08, 0.20};

    // -------------------------------------------------------------------------
    // Factories

    public static HyperparamSchema intParam(String key, String name,
                                             int def, int min, int max, String help) {
        return new HyperparamSchema(key, name, Type.INT, def, min, max, help, false);
    }

    public static HyperparamSchema intLadder(String key, String name,
                                              int def, int[] options, String help) {
        Object[] opts = new Object[options.length];
        for (int i = 0; i < options.length; i++) opts[i] = options[i];
        int lo = options[0], hi = options[0];
        for (int v : options) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
        return new HyperparamSchema(key, name, Type.ENUM, def, lo, hi, help, false, opts);
    }

    public static HyperparamSchema doubleParam(String key, String name,
                                                double def, double min, double max, String help) {
        return new HyperparamSchema(key, name, Type.DOUBLE, def, min, max, help, false);
    }

    public static HyperparamSchema doubleLadder(String key, String name,
                                                 double def, double[] options, String help) {
        Object[] opts = new Object[options.length];
        for (int i = 0; i < options.length; i++) opts[i] = options[i];
        double lo = options[0], hi = options[0];
        for (double v : options) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
        return new HyperparamSchema(key, name, Type.ENUM, def, lo, hi, help, false, opts);
    }

    public static HyperparamSchema boolParam(String key, String name, boolean def, String help) {
        return new HyperparamSchema(key, name, Type.BOOLEAN, def, null, null, help, false);
    }

    // -------------------------------------------------------------------------
    // Pre-built schemas for common algorithms

    public static List<HyperparamSchema> forGenetic() {
        return List.of(
                intLadder("population_size", "Population size", 24, POPULATION,
                        "Number of agent copies evolved per generation."),
                intParam("elite_count", "Elite count", 4, 1, 20,
                        "Top N survivors kept unchanged each generation."),
                doubleLadder("mutation_sigma", "Mutation strength", 0.10, MUTATION_SIGMA,
                        "Standard deviation of Gaussian noise applied to weights."),
                intLadder("episodes_per_eval", "Sims per genome", 1, SIMS_PER_GEN,
                        "Independent games averaged into each genome's fitness.")
        );
    }

    public static List<HyperparamSchema> forMcts() {
        return List.of(
                intLadder("rollouts", "Rollouts per move", 80, ROLLOUTS,
                        "More rollouts = stronger play, slower response."),
                doubleLadder("exploration_c", "Exploration constant", 1.4, UCB_C,
                        "UCB1 exploration weight (√2-ish is the standard)."),
                intParam("rollout_depth", "Rollout depth", 5, 1, 50,
                        "Max steps in each simulation rollout.")
        );
    }

    public static List<HyperparamSchema> forDecisionTransformer() {
        return List.of(
                intLadder("target_return", "Target return", 2000, TARGET_RETURNS,
                        "Conditioned return the policy aims to achieve.")
        );
    }

    public static List<HyperparamSchema> forImitation() {
        return List.of(
                doubleLadder("learning_rate", "Learning rate", 1e-3, LEARNING_RATES,
                        "Step size for behavioural cloning / DAgger updates.")
        );
    }

    public static List<HyperparamSchema> forDqn() {
        return forImitation();
    }

    /** Common launch knobs shared across techniques (speed / parallelism are UI-only). */
    public static List<HyperparamSchema> forEvolutionLaunch() {
        return List.of(
                intLadder("sims_per_gen", "Sims/generation", 1, SIMS_PER_GEN,
                        "Games averaged per genome each generation."),
                intLadder("ghost_cull_gens", "Ghost lineage", 2, GHOST_CULL,
                        "Generations before oldest ghost boards are culled."),
                intLadder("elite_views", "Elite views", 4, ELITE_VIEWS,
                        "How many live boards (champion + elites) to show.")
        );
    }

    /**
     * Lookup schema list by technique id / family key used in the Playground matrix.
     * Unknown ids return an empty list (panel renders no technique-specific rows).
     */
    public static List<HyperparamSchema> forTechniqueId(String id) {
        if (id == null) return List.of();
        // Ids match AiTechnique.id in suika-game (kebab-case).
        return switch (id) {
            case "mcts", "alphazero", "ens-mcts-net", "ens-mcts-greedy-tiebreak",
                 "ens-adaptive-vote", "ens-bandit-meta" -> forMcts();
            case "neuroevo", "cma-es", "pbt" -> forGenetic();
            case "dt", "ens-rtg-verified" -> forDecisionTransformer();
            case "dagger", "bc", "dqn" -> forImitation();
            default -> List.of();
        };
    }

    /** Flatten every built-in schema keyed by hyperparameter name (CLI / export). */
    public static Map<String, HyperparamSchema> catalogue() {
        java.util.LinkedHashMap<String, HyperparamSchema> out = new java.util.LinkedHashMap<>();
        for (List<HyperparamSchema> group : List.of(
                forGenetic(), forMcts(), forDecisionTransformer(),
                forImitation(), forEvolutionLaunch())) {
            for (HyperparamSchema s : group) out.putIfAbsent(s.key(), s);
        }
        return Map.copyOf(out);
    }
}
