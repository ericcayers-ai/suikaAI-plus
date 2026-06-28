package dev.suika.core;

/**
 * Records a merge that occurred during a physics step.
 */
public record MergeEvent(
        int idA,
        int idB,
        FruitTier resultTier,
        double spawnX,
        double spawnY,
        int scoreAwarded
) {}
