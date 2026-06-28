package dev.suika.dash;

import java.util.Arrays;

/**
 * Tracks drop-position frequency and builds a normalised action heatmap.
 * Visualised in the dashboard as "where does the agent prefer to drop?" (ROADMAP §VI.2).
 */
public final class ActionHeatmap {

    private final long[] counts;
    private final int    bins;

    public ActionHeatmap(int bins) {
        this.bins   = bins;
        this.counts = new long[bins];
    }

    public void record(int action) {
        if (action >= 0 && action < bins) counts[action]++;
    }

    /**
     * Returns a normalised heatmap (probabilities), or a uniform distribution
     * if no actions have been recorded yet.
     */
    public double[] normalised() {
        long total = 0;
        for (long c : counts) total += c;
        if (total == 0) {
            double[] uniform = new double[bins];
            Arrays.fill(uniform, 1.0 / bins);
            return uniform;
        }
        double[] h = new double[bins];
        for (int i = 0; i < bins; i++) h[i] = counts[i] / (double) total;
        return h;
    }

    public long   totalActions() { return Arrays.stream(counts).sum(); }
    public int    bins()         { return bins; }
    public long[] rawCounts()    { return Arrays.copyOf(counts, bins); }

    public void reset() { Arrays.fill(counts, 0L); }
}
