package dev.suika.ai;

import dev.suika.core.Fruit;
import dev.suika.core.FruitTier;
import dev.suika.core.GameCore;
import dev.suika.core.GameState;
import dev.suika.core.PhysicsConfig;
import dev.suika.core.StepResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Monte-Carlo Tree Search agent (ROADMAP §IV.2).
 *
 * <p>Uses the perfect {@link GameCore#snapshot()} model — no learned dynamics.
 * Each move: build a tree with {@code rollouts} simulations, return the
 * most-visited child's action.
 *
 * <p>Fruit randomness is handled by sampling the next fruit draw inside rollouts,
 * which implicitly averages over the distribution — equivalent to expectimax with
 * enough rollouts.
 */
public final class MctsAgent implements AgentPlugin {

    private final int    rollouts;
    private final double explorationC;
    private final int    rolloutDepth;
    private final int    actionBins;
    private final Random rng;

    /** Per-column visit counts from the most recent search — for "see it think" overlays. */
    private int[] lastVisits = new int[0];

    /** A lightweight, UI-friendly view of one node in the search tree — the tree
     *  itself is rebuilt and discarded every search, so a UI wanting to draw it needs
     *  a snapshot taken WHILE it still exists (see {@link #lastTree()}). */
    public record TreeNodeView(int action, int visits, double meanValue, java.util.List<TreeNodeView> children) {}

    /** Snapshot of the most recent search's tree — the root plus its most-visited
     *  children (columns), each with ITS most-visited children in turn (what usually
     *  happens next after picking that column) — for a genuine node-and-edge tree
     *  diagram, not just a per-column bar chart. {@code null} before the first search. */
    private volatile TreeNodeView lastTree = null;
    public TreeNodeView lastTree() { return lastTree; }

    /**
     * Optional wall-clock deadline (nanoTime). When set, the search loop exits early
     * rather than blocking indefinitely — prevents high-rollout configs stalling fast
     * physics speeds. Reset to {@link Long#MAX_VALUE} to remove the budget.
     */
    private volatile long searchDeadlineNs = Long.MAX_VALUE;
    public void setSearchDeadline(long deadlineNs) { this.searchDeadlineNs = deadlineNs; }

    /**
     * @param rollouts     simulations per move (more → stronger, slower)
     * @param explorationC UCB1 exploration constant (√2 is the standard default)
     * @param rolloutDepth max steps in each simulation rollout
     * @param actionBins   number of discrete drop positions
     */
    /** No-arg constructor for {@link java.util.ServiceLoader} discovery; uses default hyperparams. */
    public MctsAgent() { this(50, Math.sqrt(2), 5, 32); }

    public MctsAgent(int rollouts, double explorationC, int rolloutDepth, int actionBins) {
        this.rollouts     = rollouts;
        this.explorationC = explorationC;
        this.rolloutDepth = rolloutDepth;
        this.actionBins   = actionBins;
        this.rng          = new Random();
    }

    @Override public String id()          { return "mcts"; }
    @Override public String displayName() { return "MCTS"; }

    @Override
    public Object selectAction(GameState state, ActionSpec spec) {
        // Reconstitute a core from the state snapshot
        // (We need a live GameCore to call snapshot(); in production this is
        //  injected. For now we reconstruct by replaying seed+stepCount as a proxy.)
        GameCore liveCore = buildCoreApproximation(state);

        MctsNode root = new MctsNode(-1, null);
        List<Integer> actions = allActions();

        for (int r = 0; r < rollouts; r++) {
            GameCore fork = liveCore.snapshot();

            // Selection
            MctsNode node = root;
            while (node.isExpanded() && !node.isLeaf()) {
                node = node.selectChild(explorationC);
                applyAction(fork, node.action);
                if (fork.isGameOver()) break;
            }

            // Expansion
            if (!fork.isGameOver() && !node.isExpanded()) {
                node.expand(actions);
                node = node.selectChild(explorationC);
                applyAction(fork, node.action);
            }

            // Rollout (random)
            double value = rollout(fork);

            // Backup
            node.backup(value);
        }

        return root.bestAction();
    }

    /**
     * Exact path: search using forks of the supplied live core instead of the
     * replay approximation. Also records the per-column visit distribution so a UI
     * can render "where the agent is thinking" (ROADMAP §VI.4).
     */
    @Override
    public Object selectAction(GameCore liveCore, ActionSpec spec) {
        MctsNode root = new MctsNode(-1, null);
        List<Integer> actions = allActions();

        long deadline = searchDeadlineNs;   // read once per search
        for (int r = 0; r < rollouts; r++) {
            if (r > 0 && System.nanoTime() > deadline) break;  // time budget exceeded
            GameCore fork = liveCore.snapshot();

            MctsNode node = root;
            while (node.isExpanded() && !node.isLeaf()) {
                node = node.selectChild(explorationC);
                applyAction(fork, node.action);
                if (fork.isGameOver()) break;
            }
            if (!fork.isGameOver() && !node.isExpanded()) {
                node.expand(actions);
                node = node.selectChild(explorationC);
                applyAction(fork, node.action);
            }
            node.backup(rollout(fork));
        }

        int[] visits = new int[actionBins];
        for (MctsNode child : root.children()) {
            if (child.action >= 0 && child.action < actionBins) {
                visits[child.action] = child.visits();
            }
        }
        lastVisits = visits;
        lastTree = snapshotNode(root, 2, 6);
        return root.bestAction();
    }

    /** Per-column visit counts from the most recent {@link #selectAction(GameCore, ActionSpec)}. */
    public int[] lastVisits() { return lastVisits; }

    /** Captures the top-{@code maxChildren}-by-visits subtree {@code depthRemaining}
     *  levels deep — cheap regardless of how large the real search tree got, since
     *  only the handful of nodes actually kept get walked. */
    private TreeNodeView snapshotNode(MctsNode node, int depthRemaining, int maxChildren) {
        List<TreeNodeView> childViews = List.of();
        if (depthRemaining > 0 && !node.children().isEmpty()) {
            List<MctsNode> sorted = new ArrayList<>(node.children());
            sorted.sort((a, b) -> Integer.compare(b.visits(), a.visits()));
            childViews = new ArrayList<>();
            for (MctsNode c : sorted) {
                if (childViews.size() >= maxChildren || c.visits() == 0) break;
                childViews.add(snapshotNode(c, depthRemaining - 1, 4));
            }
        }
        return new TreeNodeView(node.action, node.visits(), node.meanValue(), childViews);
    }

    /** Number of discrete drop columns this agent reasons over. */
    public int actionBins() { return actionBins; }

    /** Max steps of heuristic-guided play simulated per rollout beyond the search tree. */
    public int rolloutDepth() { return rolloutDepth; }
    /** Configured rollout budget (nominal — the wall-clock deadline can cut this short). */
    public int rollouts() { return rollouts; }

    /**
     * Returns a fresh, independent instance with identical hyperparameters (its own
     * tree, its own {@link Random} stream). Used to run root-parallel search: several
     * forks each build a full independent tree for the same decision on separate
     * threads, and their visit counts are summed to make the final pick — "more
     * simulations happening at the same time" for a single move, not just more total
     * rollouts run serially.
     */
    public MctsAgent fork() {
        return new MctsAgent(rollouts, explorationC, rolloutDepth, actionBins);
    }

    // -------------------------------------------------------------------------

    private List<Integer> allActions() {
        List<Integer> a = new ArrayList<>(actionBins);
        for (int i = 0; i < actionBins; i++) a.add(i);
        return a;
    }

    private void applyAction(GameCore core, int action) {
        double x = PhysicsConfig.DROP_X_MIN
                + action / (double) (actionBins - 1)
                * (PhysicsConfig.DROP_X_MAX - PhysicsConfig.DROP_X_MIN);
        core.dropAndSettle(x);
    }

    private double rollout(GameCore core) {
        long scoreBefore = core.getScore();
        for (int d = 0; d < rolloutDepth && !core.isGameOver(); d++) {
            // Heuristic-guided (ε-greedy) rollouts: pure-random play almost never merges,
            // giving a near-zero value signal that leaves UCB1 unable to differentiate
            // columns. A cheap merge-seeking default policy makes rollouts informative,
            // which is what lets MCTS concentrate visits and actually plan strongly.
            int a = (rng.nextDouble() < 0.8) ? heuristicAction(core.getState()) : rng.nextInt(actionBins);
            applyAction(core, a);
        }
        return (core.getScore() - scoreBefore) * 0.001; // normalise to ~[0,1]
    }

    /** Cheap merge-seek / low-stack default policy used inside rollouts. */
    private int heuristicAction(GameState s) {
        double xMin = PhysicsConfig.DROP_X_MIN, xMax = PhysicsConfig.DROP_X_MAX;
        FruitTier cur = s.currentFruitTier();
        int best = actionBins / 2;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int b = 0; b < actionBins; b++) {
            double x = xMin + b / (double) (actionBins - 1) * (xMax - xMin);
            double score = 0.0;
            for (Fruit f : s.fruits()) {
                double dx = Math.abs(f.x() - x);
                if (f.tier() == cur && dx < (cur.radius + f.radius()) * 1.2) score += (cur.tier + 1) * 2.0;
                if (dx < cur.radius * 2.0) score -= f.y() * 0.15;            // prefer lower columns
                if (dx < cur.radius * 2.0 && s.isAboveDeadline(f)) score -= 6.0;
            }
            if (score > bestScore) { bestScore = score; best = b; }
        }
        return best;
    }

    /**
     * Approximate reconstruction: start fresh from the same seed and replay
     * {@code stepCount} random actions to get the board into roughly the right
     * trajectory. A production implementation would inject the live GameCore directly.
     */
    private GameCore buildCoreApproximation(GameState state) {
        GameCore core = new GameCore(state.rngSeed());
        ActionSpec spec = ActionSpec.discrete(actionBins);
        Random r = new Random(state.rngSeed());
        for (long s = 0; s < state.stepCount() && !core.isGameOver(); s++) {
            core.dropAndSettle(PhysicsConfig.DROP_X_MIN
                    + r.nextInt(actionBins) / (double) (actionBins - 1)
                    * (PhysicsConfig.DROP_X_MAX - PhysicsConfig.DROP_X_MIN));
        }
        return core;
    }
}
