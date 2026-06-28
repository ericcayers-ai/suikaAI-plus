package dev.suika.ai;

import dev.suika.core.FruitTier;
import dev.suika.core.GameState;

import java.util.List;
import java.util.Random;

/**
 * Adversarial sequence-setter: an agent that *chooses the next fruit* to maximise
 * difficulty for the player agent (ROADMAP §IV.10).
 *
 * <p>This creates a minimax game where:
 * <ul>
 *   <li>The <em>player agent</em> tries to maximise score.</li>
 *   <li>The <em>adversary</em> tries to minimise score by picking bad fruits.</li>
 * </ul>
 * Solving this adversarial setup produces robust players that handle any sequence.
 * The adversary also doubles as an automatic curriculum generator.
 */
public final class AdversarialSequenceSetter {

    public enum Strategy { RANDOM, GREEDY_WORST, LEARNED }

    private final Strategy strategy;
    private final Random   rng;

    private static final List<FruitTier> DROPPABLE = List.of(
            FruitTier.CHERRY, FruitTier.STRAWBERRY,
            FruitTier.GRAPE, FruitTier.DEKOPON, FruitTier.PERSIMMON
    );

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
            case RANDOM -> DROPPABLE.get(rng.nextInt(DROPPABLE.size()));
            case GREEDY_WORST -> worstFruitForPlayer(playerState);
            case LEARNED -> DROPPABLE.get(rng.nextInt(DROPPABLE.size())); // stub for trained model
        };
    }

    /**
     * Greedy adversarial heuristic: pick the fruit tier that appears least
     * frequently on the board (hardest to merge with).
     */
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
}
