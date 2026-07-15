package dev.suika.env;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Pure-logic Reward Studio model on {@link ComposableReward} / {@link RewardConfig}:
 * editable weights, validation, preset import/export, and replay rescoring helpers.
 */
public final class RewardStudioModel {

    public static final String HEADER = "# suika-ai-plus reward-preset v1";

    private RewardConfig config = RewardConfig.defaultConfig();

    public RewardConfig config() { return config; }

    public void setConfig(RewardConfig cfg) {
        this.config = cfg == null ? RewardConfig.defaultConfig() : cfg;
    }

    public void setWeight(String term, double value) {
        double v = clamp(value);
        config = switch (term) {
            case "score_delta" -> new RewardConfig(v, config.mergeBonusWeight(), config.survivalWeight(),
                    config.deadlinePenaltyWeight(), config.gameOverPenalty(), config.watermelonJackpot());
            case "merge_bonus" -> new RewardConfig(config.scoreDeltaWeight(), v, config.survivalWeight(),
                    config.deadlinePenaltyWeight(), config.gameOverPenalty(), config.watermelonJackpot());
            case "survival" -> new RewardConfig(config.scoreDeltaWeight(), config.mergeBonusWeight(), v,
                    config.deadlinePenaltyWeight(), config.gameOverPenalty(), config.watermelonJackpot());
            case "deadline_penalty" -> new RewardConfig(config.scoreDeltaWeight(), config.mergeBonusWeight(),
                    config.survivalWeight(), v, config.gameOverPenalty(), config.watermelonJackpot());
            case "game_over_penalty" -> new RewardConfig(config.scoreDeltaWeight(), config.mergeBonusWeight(),
                    config.survivalWeight(), config.deadlinePenaltyWeight(), v, config.watermelonJackpot());
            case "watermelon_jackpot" -> new RewardConfig(config.scoreDeltaWeight(), config.mergeBonusWeight(),
                    config.survivalWeight(), config.deadlinePenaltyWeight(), config.gameOverPenalty(), v);
            default -> config;
        };
    }

    public double weight(String term) {
        return switch (term) {
            case "score_delta" -> config.scoreDeltaWeight();
            case "merge_bonus" -> config.mergeBonusWeight();
            case "survival" -> config.survivalWeight();
            case "deadline_penalty" -> config.deadlinePenaltyWeight();
            case "game_over_penalty" -> config.gameOverPenalty();
            case "watermelon_jackpot" -> config.watermelonJackpot();
            default -> Double.NaN;
        };
    }

    public static String[] terms() {
        return new String[]{
                "score_delta", "merge_bonus", "survival",
                "deadline_penalty", "game_over_penalty", "watermelon_jackpot"
        };
    }

    /** Returns null when valid; otherwise a human error. */
    public String validate() {
        for (String t : terms()) {
            double w = weight(t);
            if (Double.isNaN(w) || Double.isInfinite(w)) return "Invalid weight: " + t;
            if (w < 0) return "Weights must be ≥ 0: " + t;
            if (w > 10_000) return "Weight too large: " + t;
        }
        return null;
    }

    public String exportText() {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (String t : terms()) {
            sb.append(t).append('=').append(String.format(Locale.ROOT, "%.6f", weight(t))).append('\n');
        }
        return sb.toString();
    }

    /** Applies a preset blob; returns error or null. */
    public String importText(String text) {
        if (text == null || text.isBlank()) return "Empty preset";
        Map<String, Double> map = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            try {
                map.put(line.substring(0, eq).trim(), Double.parseDouble(line.substring(eq + 1).trim()));
            } catch (NumberFormatException e) {
                return "Bad number in: " + line;
            }
        }
        if (map.isEmpty()) return "No weights found";
        RewardConfig d = RewardConfig.defaultConfig();
        setConfig(new RewardConfig(
                map.getOrDefault("score_delta", d.scoreDeltaWeight()),
                map.getOrDefault("merge_bonus", d.mergeBonusWeight()),
                map.getOrDefault("survival", d.survivalWeight()),
                map.getOrDefault("deadline_penalty", d.deadlinePenaltyWeight()),
                map.getOrDefault("game_over_penalty", d.gameOverPenalty()),
                map.getOrDefault("watermelon_jackpot", d.watermelonJackpot())));
        return validate();
    }

    public void resetDefaults() { config = RewardConfig.defaultConfig(); }

    /** Rescore a single step with the current weights. */
    public RewardBreakdown score(dev.suika.core.StepResult raw) {
        return new ComposableReward(config).compute(raw);
    }

    private static double clamp(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0;
        return Math.max(0, Math.min(10_000, v));
    }
}
