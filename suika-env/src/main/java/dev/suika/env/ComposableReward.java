package dev.suika.env;

import dev.suika.core.MergeEvent;
import dev.suika.core.PhysicsConfig;
import dev.suika.core.StepResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Computes the shaped reward from a raw {@link StepResult}.
 *
 * <p>All reward terms are individually weighted and tracked so the dashboard
 * can display per-term contributions. Weights come from {@link RewardConfig}.
 */
public final class ComposableReward {

    private final RewardConfig cfg;

    public ComposableReward(RewardConfig cfg) { this.cfg = cfg; }

    /**
     * Compute the total reward and a breakdown of individual terms.
     *
     * @return {@link RewardBreakdown} with total and per-term contributions
     */
    public RewardBreakdown compute(StepResult raw) {
        Map<String, Double> terms = new LinkedHashMap<>();

        // Score delta
        double scoreDelta = raw.mergesThisStep().stream()
                .mapToLong(MergeEvent::scoreAwarded).sum();
        double scoreTerm = cfg.scoreDeltaWeight() * scoreDelta;
        terms.put("score_delta", scoreTerm);

        // Merge bonus (extra weight for high-tier merges)
        double mergeTerm = 0.0;
        for (MergeEvent e : raw.mergesThisStep()) {
            if (e.resultTier() != null) {
                mergeTerm += cfg.mergeBonusWeight() * e.resultTier().tier;
            }
        }
        terms.put("merge_bonus", mergeTerm);

        // Survival reward (flat per step, discourages reckless play)
        double survivalTerm = raw.terminated() ? 0.0 : cfg.survivalWeight();
        terms.put("survival", survivalTerm);

        // Dead-line proximity penalty
        double deadlineTerm = 0.0;
        if (!raw.observation().fruits().isEmpty()) {
            double maxY = raw.observation().maxFruitY();
            double proximity = Math.max(0.0,
                    (maxY - (PhysicsConfig.DEADLINE_Y - 2.0)) / 2.0);
            deadlineTerm = -cfg.deadlinePenaltyWeight() * Math.min(1.0, proximity);
        }
        terms.put("deadline_penalty", deadlineTerm);

        // Watermelon jackpot
        double jackpotTerm = raw.mergesThisStep().stream()
                .filter(e -> e.resultTier() == null) // double-watermelon
                .count() * cfg.watermelonJackpot();
        if (jackpotTerm > 0) terms.put("watermelon_jackpot", jackpotTerm);

        // Game-over penalty
        double gameOverTerm = raw.terminated() ? -cfg.gameOverPenalty() : 0.0;
        if (raw.terminated()) terms.put("game_over_penalty", gameOverTerm);

        double total = terms.values().stream().mapToDouble(Double::doubleValue).sum();
        return new RewardBreakdown(total, Map.copyOf(terms));
    }
}
