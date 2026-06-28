package dev.suika.env;

import java.util.Map;

/**
 * Per-step reward with individual term contributions for the dashboard.
 */
public record RewardBreakdown(
        double              total,
        Map<String, Double> terms
) {
    public double term(String name) {
        return terms.getOrDefault(name, 0.0);
    }
}
