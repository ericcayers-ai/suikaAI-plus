package dev.suika.game;

import dev.suika.core.FruitTier;

/**
 * Fired when a merge occurs, carrying the data the HUD/animator needs.
 */
public record ScoreEvent(
        FruitTier resultTier,
        double    worldX,
        double    worldY,
        int       points
) {}
