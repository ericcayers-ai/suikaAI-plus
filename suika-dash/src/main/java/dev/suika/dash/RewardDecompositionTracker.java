package dev.suika.dash;

import dev.suika.env.RewardBreakdown;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects per-step reward breakdowns for the Reward Studio visualisation (ROADMAP §VI.2).
 * Stores the last N steps for real-time chart rendering.
 */
public final class RewardDecompositionTracker {

    private static final int HISTORY_SIZE = 500;

    private final List<RewardBreakdown> history = new ArrayList<>(HISTORY_SIZE);

    public void record(RewardBreakdown rb) {
        if (history.size() >= HISTORY_SIZE) history.removeFirst();
        history.add(rb);
    }

    /** Mean contribution of each reward term over the stored history. */
    public Map<String, Double> meanTerms() {
        if (history.isEmpty()) return Map.of();
        Map<String, Double> sums = new LinkedHashMap<>();
        for (RewardBreakdown rb : history) {
            rb.terms().forEach((k, v) -> sums.merge(k, v, Double::sum));
        }
        double n = history.size();
        sums.replaceAll((k, v) -> v / n);
        return Map.copyOf(sums);
    }

    /** Total reward over stored history (useful for live return chart). */
    public double totalReward() {
        return history.stream().mapToDouble(RewardBreakdown::total).sum();
    }

    public int size() { return history.size(); }
}
