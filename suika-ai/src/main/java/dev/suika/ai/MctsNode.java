package dev.suika.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One node in the MCTS tree. Tracks visit count, total value, and children.
 */
final class MctsNode {

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

    // UCB1 score for tree-policy selection
    double ucb1(double explorationC) {
        if (visits == 0) return Double.MAX_VALUE;
        int parentVisits = (parent != null) ? parent.visits : visits;
        return (totalValue / visits)
                + explorationC * Math.sqrt(Math.log(parentVisits) / visits);
    }

    void backup(double value) {
        visits++;
        totalValue += value;
        if (parent != null) parent.backup(value);
    }

    MctsNode selectChild(double explorationC) {
        return children.stream()
                .max((a, b) -> Double.compare(a.ucb1(explorationC), b.ucb1(explorationC)))
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

    int bestAction() {
        return children.stream()
                .max((a, b) -> Integer.compare(a.visits, b.visits))
                .map(c -> c.action)
                .orElse(0);
    }
}
