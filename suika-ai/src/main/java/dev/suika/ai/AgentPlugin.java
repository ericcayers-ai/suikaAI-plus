package dev.suika.ai;

import dev.suika.core.GameState;

/**
 * Plugin SPI for agents (ROADMAP §XII).
 * Discovered via {@link java.util.ServiceLoader}.
 *
 * <p>A minimal implementation needs only {@link #selectAction(GameState, ActionSpec)}.
 * Training is handled by the paired {@link TrainerPlugin}.
 */
public interface AgentPlugin {

    /** Unique plugin identifier used in configs and the UI. */
    String id();

    /** Human-readable name shown in the algorithm picker. */
    String displayName();

    /**
     * Choose an action for the given observation.
     *
     * @param state      current game state
     * @param spec       action space specification
     * @return           action value (int for discrete, double for continuous)
     */
    Object selectAction(GameState state, ActionSpec spec);

    /** Called once before the first episode. */
    default void initialize(AgentConfig config) {}

    /** Called after each episode ends. */
    default void onEpisodeEnd(long episodeScore, long episodeLength) {}
}
