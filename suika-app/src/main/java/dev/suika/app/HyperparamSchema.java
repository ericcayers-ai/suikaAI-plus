package dev.suika.app;

import java.util.List;

/**
 * Schema-driven hyperparameter descriptor (ROADMAP §VII.2).
 * The UI generates a settings panel from a list of these — add a new algorithm,
 * get its panel for free.
 */
public record HyperparamSchema(
        String  key,
        String  displayName,
        Type    type,
        Object  defaultValue,
        Object  min,
        Object  max,
        String  helpText,
        boolean requiresRestart
) {
    public enum Type { INT, DOUBLE, BOOLEAN, STRING, ENUM }

    // -------------------------------------------------------------------------
    // Factories

    public static HyperparamSchema intParam(String key, String name,
                                             int def, int min, int max, String help) {
        return new HyperparamSchema(key, name, Type.INT, def, min, max, help, false);
    }

    public static HyperparamSchema doubleParam(String key, String name,
                                                double def, double min, double max, String help) {
        return new HyperparamSchema(key, name, Type.DOUBLE, def, min, max, help, false);
    }

    public static HyperparamSchema boolParam(String key, String name, boolean def, String help) {
        return new HyperparamSchema(key, name, Type.BOOLEAN, def, null, null, help, false);
    }

    // -------------------------------------------------------------------------
    // Pre-built schemas for common algorithms

    public static List<HyperparamSchema> forGenetic() {
        return List.of(
                intParam("population_size", "Population size", 20, 4, 200,
                        "Number of agent copies evolved per generation."),
                intParam("elite_count", "Elite count", 4, 1, 20,
                        "Top N survivors kept unchanged each generation."),
                doubleParam("mutation_sigma", "Mutation strength", 0.1, 0.001, 1.0,
                        "Standard deviation of Gaussian noise applied to weights."),
                intParam("episodes_per_eval", "Episodes per evaluation", 3, 1, 20,
                        "Games played to estimate each genome's fitness.")
        );
    }

    public static List<HyperparamSchema> forMcts() {
        return List.of(
                intParam("rollouts", "Rollouts per move", 50, 5, 2000,
                        "More rollouts = stronger play, slower response."),
                doubleParam("exploration_c", "Exploration constant", Math.sqrt(2), 0.1, 5.0,
                        "UCB1 exploration weight (√2 is the standard)."),
                intParam("rollout_depth", "Rollout depth", 5, 1, 50,
                        "Max steps in each simulation rollout.")
        );
    }
}
