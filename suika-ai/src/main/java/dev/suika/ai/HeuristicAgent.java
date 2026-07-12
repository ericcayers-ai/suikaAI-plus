package dev.suika.ai;

import dev.suika.core.Fruit;
import dev.suika.core.FruitTier;
import dev.suika.core.GameState;
import dev.suika.core.PhysicsConfig;

/**
 * Hand-coded heuristic baseline (ROADMAP §IV.1).
 *
 * <p>Strategy: score every drop column by a cheap, no-simulation rule — reward landing
 * the current fruit next to a same-tier fruit (so it merges or is poised to), keep the
 * pile low and flat, and steer hard away from any column already stacked near the
 * dead-line. The best-scoring column wins. A calibrated benchmark every learned agent
 * must beat, and — because it stays alive by actively defending the top of the well
 * rather than merely chasing the nearest merge — a genuinely useful committee member
 * inside the ensembles rather than dead weight.
 *
 * <p>The column-scoring rule is exposed as {@link #bestColumn} so MCTS's rollout default
 * policy plays the exact same way — one shared notion of "a sensible drop" across the
 * planner and the baseline.
 */
public final class HeuristicAgent implements AgentPlugin {

    @Override public String id()          { return "heuristic"; }
    @Override public String displayName() { return "Heuristic (flat-stack + merge-seek)"; }

    @Override
    public Object selectAction(GameState state, ActionSpec spec) {
        int bins = spec.discrete() ? spec.bins() : 32;
        int best = bestColumn(state, bins);
        if (spec.discrete()) return best;
        double t = best / (double) (bins - 1);
        return t * 2.0 - 1.0;   // continuous: map column → [-1, 1]
    }

    /**
     * The shared column-scoring rule: returns the best drop column in {@code [0, bins)}
     * for {@code state}. Two-stage, no physics simulation:
     * <ol>
     *   <li><b>Merge-seek</b> — if any same-tier fruit sits safely below the dead-line
     *       danger band AND is reachable from above (nothing stacked directly over it),
     *       drop straight onto the one nearest the well's centre. Dropping onto an
     *       exposed same-tier fruit reliably merges; the reachability and dead-line
     *       guards are what the old "nearest same-tier" rule lacked, and skipping buried
     *       targets (which the falling fruit would just land on top of, no merge) is what
     *       keeps this from wasting drops.</li>
     *   <li><b>Flat-stack</b> — otherwise drop into the lowest column, keeping the pile
     *       low and flat so future same-tier draws have somewhere safe to land.</li>
     * </ol>
     * Cheap enough (near-linear in the ≤64 live fruit) to run inside every MCTS rollout
     * step as the default policy.
     */
    public static int bestColumn(GameState state, int bins) {
        FruitTier cur = state.currentFruitTier();
        double dangerY = PhysicsConfig.DEADLINE_Y - 1.5;
        double midX = (PhysicsConfig.DROP_X_MIN + PhysicsConfig.DROP_X_MAX) / 2.0;

        // Stage 1: merge-seek onto the nearest-to-centre reachable, safe same-tier fruit.
        Fruit target = null;
        double bestDist = Double.MAX_VALUE;
        for (Fruit f : state.fruits()) {
            if (f.tier() != cur) continue;
            if (f.y() + f.radius() > dangerY) continue;    // don't stack toward the line
            if (!reachable(state, f)) continue;            // buried → a drop wouldn't merge
            double dist = Math.abs(f.x() - midX);
            if (dist < bestDist) { bestDist = dist; target = f; }
        }
        double dropX = (target != null) ? target.x() : flatStackX(state);
        return xToBin(dropX, bins);
    }

    /** True when no other fruit sits directly above {@code f} in its column — i.e. a fruit
     *  dropped at {@code f.x} would actually reach {@code f} and merge, not land on a pile
     *  resting on top of it. */
    private static boolean reachable(GameState state, Fruit f) {
        for (Fruit o : state.fruits()) {
            if (o == f) continue;
            if (Math.abs(o.x() - f.x()) < f.radius() && o.y() > f.y() + f.radius() * 0.5) return false;
        }
        return true;
    }

    /** The x of the lowest-standing column — where a non-merging drop does least harm. */
    private static double flatStackX(GameState state) {
        double xMin = PhysicsConfig.DROP_X_MIN, xMax = PhysicsConfig.DROP_X_MAX;
        if (state.fruits().isEmpty()) return (xMin + xMax) / 2.0;
        int samples = 24;
        double bestX = (xMin + xMax) / 2.0, bestH = Double.MAX_VALUE;
        double colW = PhysicsConfig.CONTAINER_WIDTH / 8.0;
        for (int i = 0; i < samples; i++) {
            double x = xMin + i * (xMax - xMin) / (samples - 1.0);
            double h = 0.0;
            for (Fruit f : state.fruits()) {
                if (Math.abs(f.x() - x) < colW) h = Math.max(h, f.y() + f.radius());
            }
            if (h < bestH) { bestH = h; bestX = x; }
        }
        return bestX;
    }

    private static int xToBin(double x, int bins) {
        double t = (x - PhysicsConfig.DROP_X_MIN)
                / (PhysicsConfig.DROP_X_MAX - PhysicsConfig.DROP_X_MIN);
        int b = (int) Math.round(t * (bins - 1));
        return Math.max(0, Math.min(bins - 1, b));
    }
}
