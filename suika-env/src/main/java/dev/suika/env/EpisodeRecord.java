package dev.suika.env;

import dev.suika.core.GameState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An in-memory episode record: one row per timestep.
 * Used by the imitation-learning data pipeline (Phase 5) and the replay viewer (Phase 6).
 * Serialise to Parquet/Arrow in the bridge module for Python consumers.
 */
public final class EpisodeRecord {

    public record Transition(
            float[] observation,
            double  action,
            double  reward,
            float[] nextObservation,
            boolean terminated,
            boolean truncated,
            RewardBreakdown rewardBreakdown
    ) {}

    private final long            seed;
    private final List<Transition> transitions = new ArrayList<>();

    public EpisodeRecord(long seed) { this.seed = seed; }

    public void add(Transition t)                 { transitions.add(t); }
    public long             seed()                { return seed; }
    public List<Transition> transitions()         { return Collections.unmodifiableList(transitions); }
    public int              length()              { return transitions.size(); }
    public boolean          isTerminated()        { return !transitions.isEmpty()
                                                    && transitions.getLast().terminated(); }
    public double           totalReward()         {
        return transitions.stream().mapToDouble(Transition::reward).sum();
    }
}
