package dev.suika.core;

import java.util.List;

/**
 * Result returned after {@link GameCore#dropAndSettle(double)}.
 * Mirrors the Gymnasium (obs, reward, terminated, truncated, info) contract.
 */
public record StepResult(
        GameState     observation,
        double        reward,
        boolean       terminated,
        boolean       truncated,
        List<MergeEvent> mergesThisStep
) {}
