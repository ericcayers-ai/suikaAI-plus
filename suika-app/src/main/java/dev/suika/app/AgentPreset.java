package dev.suika.app;

import dev.suika.ai.AgentConfig;

import java.util.Map;

/**
 * Friendly presets for Explorer mode (ROADMAP §VII.1).
 * Each preset hides the full algorithm config behind a human-readable name.
 */
public enum AgentPreset {

    QUICK_LEARNER(
            "Quick Learner",
            "Trains a small neural network by trying lots of random games — good to watch while you grab a coffee.",
            new AgentConfig("genetic", Map.of(
                    "population_size",    20,
                    "mutation_sigma",     0.1,
                    "episodes_per_eval",  2,
                    "elite_count",        4
            ))
    ),

    IMITATE_ME(
            "Imitate Me",
            "Records your games and trains an AI to play just like you.",
            new AgentConfig("behavioral-cloning", Map.of(
                    "learning_rate", 1e-3,
                    "batch_size",    64
            ))
    ),

    MASTER_PLANNER(
            "Master Planner",
            "Thinks ahead using a built-in game simulator — slower per move but much stronger.",
            new AgentConfig("mcts", Map.of(
                    "rollouts",       50,
                    "exploration_c",  1.4,
                    "rollout_depth",  5,
                    "action_bins",    32
            ))
    );

    public final String     displayName;
    public final String     description;
    public final AgentConfig config;

    AgentPreset(String displayName, String description, AgentConfig config) {
        this.displayName = displayName;
        this.description = description;
        this.config      = config;
    }
}
