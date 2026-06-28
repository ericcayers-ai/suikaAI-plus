package dev.suika.ai;

import dev.suika.core.Fruit;
import dev.suika.core.FruitTier;
import dev.suika.core.GameState;
import dev.suika.core.PhysicsConfig;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Adversarial sequence-setter: chooses the next fruit to maximise difficulty
 * for the player agent (ROADMAP §IV.10).
 *
 * <p>Three strategies:
 * <ul>
 *   <li>{@code RANDOM} — uniformly random droppable tier.</li>
 *   <li>{@code GREEDY_WORST} — pick the tier with fewest matching fruits on the board
 *       (minimises immediate merge potential).</li>
 *   <li>{@code LEARNED} — geometry-aware minimax: evaluates each candidate tier by
 *       its reachable merge potential across all drop positions, then picks the tier
 *       whose best-response score is lowest. Produces genuinely adversarial
 *       curriculum without requiring a live GameCore during selection.</li>
 * </ul>
 */
public final class AdversarialSequenceSetter {

    public enum Strategy { RANDOM, GREEDY_WORST, LEARNED }

    private final Strategy strategy;
    private final Random   rng;

    private static final List<FruitTier> DROPPABLE = List.of(
            FruitTier.CHERRY, FruitTier.STRAWBERRY,
            FruitTier.GRAPE, FruitTier.DEKOPON, FruitTier.PERSIMMON
    );

    private static final int SCAN_BINS = 24;

    public AdversarialSequenceSetter(Strategy strategy, long seed) {
        this.strategy = strategy;
        this.rng      = new Random(seed);
    }

    /**
     * Choose the next fruit tier to give the player.
     *
     * @param playerState current board state of the player agent
     * @return the tier the adversary selects
     */
    public FruitTier chooseNextFruit(GameState playerState) {
        return switch (strategy) {
            case RANDOM       -> DROPPABLE.get(rng.nextInt(DROPPABLE.size()));
            case GREEDY_WORST -> worstFruitForPlayer(playerState);
            case LEARNED      -> adversarialLookAhead(playerState);
        };
    }

    // -------------------------------------------------------------------------

    /** Greedy heuristic: pick the tier with the least matching count on the board. */
    private FruitTier worstFruitForPlayer(GameState state) {
        int[] tierCounts = new int[12];
        for (var f : state.fruits()) tierCounts[f.tier().tier]++;
        FruitTier worst = DROPPABLE.get(0);
        int minCount = Integer.MAX_VALUE;
        for (FruitTier ft : DROPPABLE) {
            if (tierCounts[ft.tier] < minCount) { minCount = tierCounts[ft.tier]; worst = ft; }
        }
        return worst;
    }

    /**
     * Geometry-aware minimax adversary.
     *
     * <p>For each candidate tier, computes the maximum merge potential the player
     * could achieve by scanning all drop positions. The adversary picks the tier
     * whose best achievable merge potential is smallest — forcing the player to
     * make low-value moves.
     *
     * <p>Merge potential at drop position {@code x}:
     * {@code Σ (tier+1) × proximity × column_safety_weight}
     * where proximity is a Gaussian kernel centred on each matching fruit, and
     * column_safety_weight penalises positions with fruits already above the deadline.
     */
    private FruitTier adversarialLookAhead(GameState state) {
        // Pre-compute heights per column bucket for safety penalty
        double[] colHeight = computeColumnHeights(state);

        FruitTier worst  = DROPPABLE.get(0);
        double minBestMerge = Double.MAX_VALUE;

        for (FruitTier candidate : DROPPABLE) {
            double bestMerge = bestMergePotential(state, candidate, colHeight);
            if (bestMerge < minBestMerge) {
                minBestMerge = bestMerge;
                worst = candidate;
            }
        }
        return worst;
    }

    private double bestMergePotential(GameState state, FruitTier tier, double[] colHeight) {
        double xMin = PhysicsConfig.DROP_X_MIN;
        double xMax = PhysicsConfig.DROP_X_MAX;
        double best = Double.NEGATIVE_INFINITY;

        for (int bin = 0; bin < SCAN_BINS; bin++) {
            double x = xMin + (bin / (double) (SCAN_BINS - 1)) * (xMax - xMin);
            double potential = 0.0;

            for (Fruit f : state.fruits()) {
                if (f.tier() != tier) continue;
                double dx = Math.abs(f.x() - x);
                double sigma = tier.radius * 2.5;
                double proximity = Math.exp(-0.5 * (dx / sigma) * (dx / sigma));
                double safetyWeight = state.isAboveDeadline(f) ? 0.1 : 1.0;
                potential += (tier.tier + 1) * proximity * safetyWeight;
            }

            // Penalise columns that are already tall (dangerous for the player)
            int colBin = bin * SCAN_BINS / SCAN_BINS;
            double heightPenalty = (colBin < colHeight.length ? colHeight[colBin] : 0.0) * 0.3;
            potential -= heightPenalty;

            if (potential > best) best = potential;
        }
        return best;
    }

    private double[] computeColumnHeights(GameState state) {
        double xMin  = PhysicsConfig.DROP_X_MIN;
        double xMax  = PhysicsConfig.DROP_X_MAX;
        double[] heights = new double[SCAN_BINS];
        int[]    counts  = new int[SCAN_BINS];

        for (Fruit f : state.fruits()) {
            int bin = (int) Math.round(
                    ((f.x() - xMin) / (xMax - xMin)) * (SCAN_BINS - 1));
            bin = Math.max(0, Math.min(SCAN_BINS - 1, bin));
            heights[bin] = Math.max(heights[bin], f.y() / PhysicsConfig.CONTAINER_HEIGHT);
            counts[bin]++;
        }
        return heights;
    }
}
