package dev.suika.dash;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe, in-memory metrics store for one training run.
 * The dashboard reads from this; trainers write to it.
 */
public final class RunMetrics {

    /** One timestep's worth of logged scalars. */
    public record Step(
            int             step,
            Instant         timestamp,
            Map<String, Double> scalars
    ) {}

    private final String id;
    private final String algorithmName;
    private final Instant startTime;
    private final List<Step> steps = new CopyOnWriteArrayList<>();

    public RunMetrics(String id, String algorithmName) {
        this.id            = id;
        this.algorithmName = algorithmName;
        this.startTime     = Instant.now();
    }

    /** Record a scalar map at the current step number. */
    public void record(int step, Map<String, Double> scalars) {
        steps.add(new Step(step, Instant.now(), Map.copyOf(scalars)));
    }

    /** Convenience: record a single scalar. */
    public void record(int step, String key, double value) {
        record(step, Map.of(key, value));
    }

    public String       id()            { return id; }
    public String       algorithmName() { return algorithmName; }
    public Instant      startTime()     { return startTime; }
    public List<Step>   steps()         { return Collections.unmodifiableList(steps); }
    public int          stepCount()     { return steps.size(); }

    /** Latest value of a scalar key, or NaN if not yet recorded. */
    public double latest(String key) {
        for (int i = steps.size() - 1; i >= 0; i--) {
            Double v = steps.get(i).scalars().get(key);
            if (v != null) return v;
        }
        return Double.NaN;
    }
}
