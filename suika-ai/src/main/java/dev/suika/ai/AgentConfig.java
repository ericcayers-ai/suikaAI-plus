package dev.suika.ai;

import java.util.Map;

/**
 * Schema-driven hyperparameter bag for an agent.
 * The UI generates settings panels from this at runtime (ROADMAP §VII.2).
 */
public record AgentConfig(
        String              agentId,
        Map<String, Object> hyperparameters
) {
    @SuppressWarnings("unchecked")
    public <T> T get(String key, T defaultValue) {
        Object v = hyperparameters.get(key);
        return v != null ? (T) v : defaultValue;
    }
}
