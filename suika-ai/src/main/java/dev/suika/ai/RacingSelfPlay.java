package dev.suika.ai;

import dev.suika.core.GameCore;
import dev.suika.core.GameState;

/**
 * Racing self-play: two agents play the *same* seeded fruit sequence.
 * The winner is the agent with the higher final score (ROADMAP §IV.10).
 *
 * <p>Relative score difference becomes the reward signal, creating a zero-sum
 * competitive dynamic that drives robust skill improvement.
 */
public final class RacingSelfPlay {

    public record RaceResult(
            long   scoreA,
            long   scoreB,
            String winnerId
    ) {
        public double relativeRewardA() { return (scoreA - scoreB) / 100.0; }
        public double relativeRewardB() { return (scoreB - scoreA) / 100.0; }
    }

    private final int maxSteps;
    private final int actionBins;

    public RacingSelfPlay(int maxSteps, int actionBins) {
        this.maxSteps   = maxSteps;
        this.actionBins = actionBins;
    }

    /**
     * Run one race between {@code agentA} and {@code agentB} on the same seed.
     */
    public RaceResult race(AgentPlugin agentA, AgentPlugin agentB, long seed) {
        ActionSpec spec = ActionSpec.discrete(actionBins);
        GameCore coreA = new GameCore(seed);
        GameCore coreB = new GameCore(seed);

        for (int step = 0; step < maxSteps; step++) {
            boolean aOver = coreA.isGameOver();
            boolean bOver = coreB.isGameOver();
            if (aOver && bOver) break;

            if (!aOver) {
                GameState sa = coreA.getState();
                Object act = agentA.selectAction(sa, spec);
                coreA.dropAndSettle(spec.toDropX(act,
                        dev.suika.core.PhysicsConfig.DROP_X_MIN,
                        dev.suika.core.PhysicsConfig.DROP_X_MAX));
            }
            if (!bOver) {
                GameState sb = coreB.getState();
                Object act = agentB.selectAction(sb, spec);
                coreB.dropAndSettle(spec.toDropX(act,
                        dev.suika.core.PhysicsConfig.DROP_X_MIN,
                        dev.suika.core.PhysicsConfig.DROP_X_MAX));
            }
        }

        long sa = coreA.getScore(), sb = coreB.getScore();
        String winner = (sa > sb) ? agentA.id() : (sb > sa) ? agentB.id() : "draw";
        return new RaceResult(sa, sb, winner);
    }
}
