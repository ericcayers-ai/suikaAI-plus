package dev.suika.ai;

import dev.suika.core.Fruit;
import dev.suika.core.GameCore;
import dev.suika.core.GameState;
import dev.suika.core.PhysicsConfig;

/**
 * Shared, survival-aware evaluation of a settled board — the single scoring function
 * every simulate-and-pick technique uses so they all optimise for the SAME thing:
 * rack up merges <em>and stay alive long enough to keep racking them up</em>.
 *
 * <p>Why this exists: a pure "maximise the immediate merge score" one-ply pick (the old
 * {@link GreedyOnePlyAgent}/MCTS-rollout value) walks straight into stalemates. Most
 * drops score zero right now, so among the zero-score majority the agent chose almost
 * arbitrarily, slowly piling the well up until a fruit rested over the dead-line and the
 * game ended — typically well short of 1000 points. The realized merge score is only
 * half the picture; the other half is board <em>health</em>: how low and how clustered
 * the pile is left afterwards, and how far it sits from the losing line.
 *
 * <p>{@link #placement} folds both halves into one number:
 * <ul>
 *   <li><b>realized merges</b> — the score actually gained this drop, weighted heavily so
 *       a genuine merge always beats mere tidiness;</li>
 *   <li><b>peak / average height</b> — keep the stack low so there's room to keep playing;</li>
 *   <li><b>dead-line risk</b> — a steep penalty for any fruit resting in the danger band
 *       just below the line, so the agent actively defends the top of the well;</li>
 *   <li><b>merge readiness</b> — reward leaving same-tier fruit sitting together, so the
 *       next drop can complete a merge the one-ply score can't see yet;</li>
 *   <li><b>game-over</b> — a large fixed penalty so losing is never worth a few points.</li>
 * </ul>
 * The weights are tuned by feel against the headless benchmark (see the repo's Bench
 * harness) so the planning/greedy/ensemble agents comfortably clear 1000 on the standard
 * seeds instead of stalling in the mid-hundreds.
 */
public final class BoardEval {

    private BoardEval() {}

    /** Fixed penalty (in the same units as {@link #placement}'s output) for a drop that
     *  ends the game — dwarfs any realistic single-drop merge score so survival wins. */
    public static final double GAME_OVER_PENALTY = 1000.0;

    // Defaults tuned against the headless Bench (see the repo's Bench harness) to lift the
    // survival floor: the pre-tune weights let a couple of unlucky seeds still stalemate in
    // the mid-hundreds. Weighting the PEAK height (MAX_TOP_WEIGHT) is the single biggest
    // lever — keeping the tallest column low buys the room to keep playing. These are a
    // deliberate COMPROMISE: an even harder peak penalty squeezed a bit more out of the
    // deterministic one-ply Greedy but distorted the value signal the MCTS-rollout and
    // ensemble agents depend on (they call {@link #health} many times deep inside a
    // rollout, where an over-aggressive height term crowds out everything else). This
    // setting lifts Greedy from ~810 to ~950 while keeping the search/ensemble agents in
    // the ~1000 band rather than regressing them.
    private static final double MERGE_WEIGHT   = dbl("suika.eval.merge",  6.0);   // realized score is the priority
    private static final double MAX_TOP_WEIGHT = dbl("suika.eval.maxtop", 0.5);   // keep the PEAK low — biggest survival lever
    private static final double AVG_TOP_WEIGHT = dbl("suika.eval.avgtop", 0.18);  // keep the whole pile low
    private static final double RISK_WEIGHT    = dbl("suika.eval.risk",   4.0);   // defend the dead-line band hard
    private static final double READY_WEIGHT   = dbl("suika.eval.ready",  2.5);   // reward setting up future merges

    /** Height of the danger band below the dead-line that the risk term watches.
     *  Shared with {@link HeuristicAgent}'s merge-seek guard so the default policy and
     *  the evaluator agree about what counts as "too close to losing". */
    public static final double DANGER_BAND = dbl("suika.eval.band", 2.5);

    private static double dbl(String key, double def) {
        String v = System.getProperty(key);
        if (v == null) return def;
        try { return Double.parseDouble(v); } catch (NumberFormatException e) { return def; }
    }

    /**
     * Scores the board that results from a drop. Higher is better.
     *
     * @param afterDrop   the core AFTER {@link GameCore#dropAndSettle} has run (a fork —
     *                    never the live core)
     * @param scoreGained merge score gained by that drop ({@code afterScore - beforeScore})
     * @param terminated  whether that drop ended the game
     */
    public static double placement(GameCore afterDrop, long scoreGained, boolean terminated) {
        double v = scoreGained * MERGE_WEIGHT;
        if (terminated) return v - GAME_OVER_PENALTY;
        return v + health(afterDrop.getState());
    }

    /** The board-health half of {@link #placement} (everything except realized merges and
     *  the game-over penalty) — reusable where only the resting board matters. */
    public static double health(GameState s) {
        var fruits = s.fruits();
        int n = fruits.size();
        if (n == 0) return 0.0;

        double dangerStart = PhysicsConfig.DEADLINE_Y - DANGER_BAND;
        double maxTop = 0.0, sumTop = 0.0, risk = 0.0;
        for (Fruit f : fruits) {
            double top = f.y() + f.radius();
            if (top > maxTop) maxTop = top;
            sumTop += top;
            if (top > dangerStart) risk += (top - dangerStart);
        }
        double avgTop = sumTop / n;

        double v = 0.0;
        v -= MAX_TOP_WEIGHT * maxTop;
        v -= AVG_TOP_WEIGHT * avgTop;
        v -= RISK_WEIGHT * risk;
        v += READY_WEIGHT * mergeReadiness(s);
        return v;
    }

    /**
     * Rewards same-tier fruit left sitting close enough that one more drop into the gap
     * could merge them — the signal the one-ply realized score is blind to. Counts each
     * near-touching same-tier pair, weighted by tier so lining up the valuable fruit
     * matters more than clustering cherries. O(n²) over the (≤64) live fruit — cheap
     * next to a physics settle, so fine to call once per evaluated placement.
     */
    private static double mergeReadiness(GameState s) {
        var fruits = s.fruits();
        int n = fruits.size();
        double total = 0.0;
        for (int i = 0; i < n; i++) {
            Fruit a = fruits.get(i);
            for (int j = i + 1; j < n; j++) {
                Fruit b = fruits.get(j);
                if (a.tier() != b.tier()) continue;
                double dx = a.x() - b.x(), dy = a.y() - b.y();
                double dist = Math.sqrt(dx * dx + dy * dy);
                double touch = a.radius() + b.radius();
                // Within ~1.6× the touching distance counts as "poised to merge"; closer
                // scores higher. Beyond that the two are unrelated and contribute nothing.
                if (dist < touch * 1.6) {
                    double closeness = 1.0 - Math.min(1.0, (dist - touch) / touch);
                    total += closeness * (a.tier().tier + 1) * 0.15;
                }
            }
        }
        return total;
    }
}
