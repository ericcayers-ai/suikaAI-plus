package dev.suika.ai;

import dev.suika.core.StepResult;

/**
 * Plugin SPI for learning algorithms (ROADMAP §XII).
 * Receives transitions and updates the paired {@link AgentPlugin}.
 */
public interface TrainerPlugin {

    String id();

    void observe(StepResult transition);

    void update();

    default void onTrainingStart() {}

    default void onTrainingEnd() {}
}
