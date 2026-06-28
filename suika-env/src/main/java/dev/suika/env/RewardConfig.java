package dev.suika.env;

/**
 * Composable reward weights (all exposed in the Reward Studio).
 * Set a weight to 0 to disable that term.
 */
public record RewardConfig(
        double scoreDeltaWeight,
        double mergeBonusWeight,
        double survivalWeight,
        double deadlinePenaltyWeight,
        double gameOverPenalty,
        double watermelonJackpot
) {
    /** Sensible defaults matching a generic Suika optimisation objective. */
    public static RewardConfig defaultConfig() {
        return new RewardConfig(1.0, 0.2, 0.01, 0.5, 10.0, 50.0);
    }
}
