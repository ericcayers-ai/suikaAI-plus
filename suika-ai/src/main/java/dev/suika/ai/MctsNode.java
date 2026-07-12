package dev.suika.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One node in the MCTS tree. Tracks visit count, total value, and children.
 */
final class MctsNode {

    /**
     * Running min/max of the values backed up during a single search, used to normalise
     * the exploitation term of UCB1 into {@code [0,1]}.
     *
     * <p>Why this matters here: our rollout value (realized merges + board health, see
     * {@link BoardEval}) lives on an arbitrary, compressed scale — typically a spread of a
     * few tenths between columns. UCB1's exploration term with the textbook √2 constant is
     * ≈2–3 for a freshly-visited child, which utterly dwarfs those tenths. Without
     * normalisation the tree policy is therefore almost pure uniform exploration and the
     * final "most-visited" pick is little better than random — which is exactly why an
     * un-normalised MCTS scored <em>below</em> the one-ply greedy. Rescaling the mean value
     * to [0,1] against the observed range puts exploitation on equal footing with
     * exploration regardless of the reward scale (the MuZero/AlphaZero approach). */
    static final class MinMax {
        private double lo = Double.POSITIVE_INFINITY;
        private double hi = Double.NEGATIVE_INFINITY;

        void update(double v) {
            if (v < lo) lo = v;
            if (v > hi) hi = v;
        }

        /** Maps {@code q} into [0,1] against the observed range; 0.5 before any spread. */
        double norm(double q) {
            if (hi <= lo) return 0.5;
            double t = (q - lo) / (hi - lo);
            return t < 0 ? 0 : (t > 1 ? 1 : t);
        }
    }

    final int       action;          // action that led to this node (-1 for root)
    final MctsNode  parent;

    private int    visits = 0;
    private double totalValue = 0.0;

    private final List<MctsNode> children = new ArrayList<>();
    private boolean expanded = false;

    MctsNode(int action, MctsNode parent) {
        this.action = action;
        this.parent = parent;
    }

    // UCB1 score for tree-policy selection, with the exploitation term normalised to [0,1].
    double ucb1(double explorationC, MinMax stats) {
        if (visits == 0) return Double.MAX_VALUE;
        int parentVisits = (parent != null) ? parent.visits : visits;
        return stats.norm(totalValue / visits)
                + explorationC * Math.sqrt(Math.log(parentVisits) / visits);
    }

    void backup(double value) {
        visits++;
        totalValue += value;
        if (parent != null) parent.backup(value);
    }

    MctsNode selectChild(double explorationC, MinMax stats) {
        return children.stream()
                .max((a, b) -> Double.compare(a.ucb1(explorationC, stats), b.ucb1(explorationC, stats)))
                .orElseThrow();
    }

    void expand(List<Integer> actions) {
        if (expanded) return;
        for (int a : actions) children.add(new MctsNode(a, this));
        expanded = true;
    }

    boolean isExpanded()           { return expanded; }
    boolean isLeaf()               { return children.isEmpty(); }
    int     visits()               { return visits; }
    double  meanValue()            { return visits > 0 ? totalValue / visits : 0.0; }
    List<MctsNode> children()      { return Collections.unmodifiableList(children); }

    /**
     * The move to actually play: the most-visited child, tie-broken by mean value. This is
     * the standard robust choice and it works here because every root column is seeded with
     * a grounded one-ply evaluation before the rollouts run (see the agent), so visits are
     * concentrated by UCB onto genuinely good columns rather than scattered by exploration
     * noise. Falls back to column 0 for an empty root.
     */
    int bestAction() {
        MctsNode best = null;
        for (MctsNode c : children) {
            if (best == null
                    || c.visits > best.visits
                    || (c.visits == best.visits && c.meanValue() > best.meanValue())) {
                best = c;
            }
        }
        return best != null ? best.action : 0;
    }
}
