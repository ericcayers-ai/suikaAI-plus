package dev.suika.core;

import java.util.Collections;
import java.util.List;

/**
 * Immutable snapshot of the complete game state at a single instant.
 * Cheap to copy — physics bodies are read from dyn4j at snapshot time and stored here.
 */
public record GameState(
        List<Fruit>  fruits,
        FruitTier    currentFruitTier,
        FruitTier    nextFruitTier,
        long         score,
        long         bestScore,
        boolean      gameOver,
        double       timeAboveDeadline,
        long         stepCount,
        long         rngSeed
) {
    public GameState {
        fruits = Collections.unmodifiableList(fruits);
    }

    public double maxFruitY() {
        return fruits.stream().mapToDouble(Fruit::y).max().orElse(0.0);
    }

    public boolean isAboveDeadline(Fruit f) {
        return (f.y() + f.radius()) > PhysicsConfig.DEADLINE_Y;
    }

    public boolean anyFruitAboveDeadline() {
        return fruits.stream().anyMatch(this::isAboveDeadline);
    }
}
