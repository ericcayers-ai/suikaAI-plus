package dev.suika.ai;

import dev.suika.core.GameCore;
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

    /**
     * Choose an action when a <em>live, forkable</em> core is available (AI-watch and
     * planning). Defaults to the state-only path; planning agents (Greedy, MCTS)
     * override this to {@link GameCore#snapshot()} the real core instead of
     * approximating it from a {@link GameState}, which is both faster and exact.
     *
     * @param core live game core (caller retains ownership; implementations must only
     *             ever step {@link GameCore#snapshot() snapshots}, never the live core)
     * @param spec action space specification
     */
    default Object selectAction(GameCore core, ActionSpec spec) {
        return selectAction(core.getState(), spec);
    }

    /** Called once before the first episode. */
    default void initialize(AgentConfig config) {}

    /** Called after each episode ends. */
    default void onEpisodeEnd(long episodeScore, long episodeLength) {}
}
