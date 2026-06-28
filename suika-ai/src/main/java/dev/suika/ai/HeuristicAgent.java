package dev.suika.ai;

import dev.suika.core.Fruit;
import dev.suika.core.FruitTier;
import dev.suika.core.GameState;
import dev.suika.core.PhysicsConfig;

import java.util.Comparator;
import java.util.Optional;

/**
 * Hand-coded heuristic baseline (ROADMAP §IV.1).
 *
 * <p>Strategy: drop on top of the nearest fruit of the same tier as the
 * current fruit; if no same-tier fruit exists, drop in the lowest-height column
 * to keep the surface flat. A calibrated benchmark every learned agent must beat.
 */
public final class HeuristicAgent implements AgentPlugin {

    @Override public String id()          { return "heuristic"; }
    @Override public String displayName() { return "Heuristic (flat-stack + merge-seek)"; }

    @Override
    public Object selectAction(GameState state, ActionSpec spec) {
        FruitTier current = state.currentFruitTier();

        // Try to find the nearest same-tier fruit to merge with
        Optional<Fruit> target = state.fruits().stream()
                .filter(f -> f.tier() == current)
                .min(Comparator.comparingDouble(f -> Math.abs(f.x() - midX())));

        double dropX;
        if (target.isPresent()) {
            dropX = target.get().x();
        } else {
            // No same-tier fruit — find the x with minimum max-height (flat stacking)
            dropX = flatStackX(state);
        }

        return encodeAction(dropX, spec);
    }

    private double midX() {
        return (PhysicsConfig.LEFT_WALL_X + PhysicsConfig.RIGHT_WALL_X) / 2.0;
    }

    private double flatStackX(GameState state) {
        if (state.fruits().isEmpty()) return midX();

        // Sample several x positions and pick the one with minimum stack height
        int samples = 16;
        double bestX  = midX();
        double bestH  = Double.MAX_VALUE;

        for (int i = 0; i < samples; i++) {
            double x = PhysicsConfig.DROP_X_MIN
                    + i * (PhysicsConfig.DROP_X_MAX - PhysicsConfig.DROP_X_MIN) / (samples - 1.0);
            double column = columnHeight(state, x);
            if (column < bestH) { bestH = column; bestX = x; }
        }
        return bestX;
    }

    private double columnHeight(GameState state, double x) {
        double colWidth = PhysicsConfig.CONTAINER_WIDTH / 8.0;
        return state.fruits().stream()
                .filter(f -> Math.abs(f.x() - x) < colWidth)
                .mapToDouble(f -> f.y() + f.radius())
                .max()
                .orElse(0.0);
    }

    private Object encodeAction(double dropX, ActionSpec spec) {
        if (spec.discrete()) {
            double t = (dropX - PhysicsConfig.DROP_X_MIN)
                    / (PhysicsConfig.DROP_X_MAX - PhysicsConfig.DROP_X_MIN);
            return (int) Math.round(t * (spec.bins() - 1));
        }
        // Continuous: map [xMin, xMax] → [-1, 1]
        double t = (dropX - PhysicsConfig.DROP_X_MIN)
                / (PhysicsConfig.DROP_X_MAX - PhysicsConfig.DROP_X_MIN);
        return t * 2.0 - 1.0;
    }
}
