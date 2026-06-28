package dev.suika.ai;

import dev.suika.core.GameState;

import java.util.Map;

/**
 * Bring-Your-Own-Agent starter template (ROADMAP §XII).
 *
 * <p>To add a new agent:
 * <ol>
 *   <li>Extend this class (or implement {@link AgentPlugin} directly).</li>
 *   <li>Override {@link #id()} and {@link #displayName()} with unique values.</li>
 *   <li>Implement {@link #selectAction(GameState, ActionSpec)} — the only required method.</li>
 *   <li>Optionally override {@link #initialize(AgentConfig)} and
 *       {@link #onEpisodeEnd(long, long)}.</li>
 *   <li>Add a {@code META-INF/services/dev.suika.ai.AgentPlugin} file to your JAR
 *       listing your fully-qualified class name — it will appear in the algorithm picker.</li>
 * </ol>
 *
 * <p>Drop-x convention: discrete action {@code k} maps to x position via
 * {@link ActionSpec#toDropX(Object, double, double)}.
 *
 * <p>To declare hyperparameters for the Researcher settings panel, pair this agent
 * with a {@code HyperparamSchema} list in the suika-app layer.
 */
public abstract class ByoaTemplate implements AgentPlugin {

    protected AgentConfig config = new AgentConfig(id(), Map.of());

    @Override
    public void initialize(AgentConfig config) {
        this.config = config;
    }

    /** Convenience: read a typed hyperparameter, falling back to a default. */
    protected <T> T hp(String key, T defaultValue) {
        return config.get(key, defaultValue);
    }

    @Override
    public void onEpisodeEnd(long episodeScore, long episodeLength) {}
}
