package dev.suika.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collects pairwise human preferences for RLHF-style reward learning (ROADMAP §IV.7).
 *
 * <p>A preference is: given two short agent clips (A and B), which does the player prefer?
 * These preferences are used to train a reward model that captures subjective "style"
 * that no hand-written reward formula can express.
 */
public final class PreferenceCollector {

    /** A pair of episode segments shown to the human rater. */
    public record ClipPair(
            List<float[]> clipA,        // sequence of observations
            List<float[]> clipB,
            long          returnA,
            long          returnB
    ) {}

    /** A labelled preference: 0 = prefer A, 1 = prefer B, 0.5 = equal. */
    public record Preference(
            ClipPair clips,
            double   label
    ) {}

    private final List<Preference> preferences = new ArrayList<>();

    /** Record that the human preferred clip A over clip B. */
    public void preferA(ClipPair pair)     { preferences.add(new Preference(pair, 0.0)); }
    /** Record that the human preferred clip B over clip A. */
    public void preferB(ClipPair pair)     { preferences.add(new Preference(pair, 1.0)); }
    /** Record that the human found both clips equally good. */
    public void preferEqual(ClipPair pair) { preferences.add(new Preference(pair, 0.5)); }

    public List<Preference> all()  { return Collections.unmodifiableList(preferences); }
    public int              size() { return preferences.size(); }
    public void             clear(){ preferences.clear(); }
}
