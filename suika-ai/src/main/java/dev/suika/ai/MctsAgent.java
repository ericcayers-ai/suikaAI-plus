package dev.suika.ai;

import dev.suika.core.GameCore;
import dev.suika.core.GameState;
import dev.suika.core.PhysicsConfig;

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
        MctsNode.MinMax stats = new MctsNode.MinMax();
        List<Integer> actions = allActions();
        long rootScore = liveCore.getScore();

        // Seed each column with a grounded one-ply value (see the live-core path for why).
        root.expand(actions);
        for (MctsNode child : root.children()) {
            GameCore fork = liveCore.snapshot();
            applyAction(fork, child.action);
            double v = positionValue(fork, rootScore, fork.isGameOver());
            stats.update(v);
            child.backup(v);
        }

        for (int r = 0; r < rollouts; r++) {
            GameCore fork = liveCore.snapshot();

            // Selection
            MctsNode node = root;
            while (node.isExpanded() && !node.isLeaf()) {
                node = node.selectChild(explorationC, stats);
                applyAction(fork, node.action);
                if (fork.isGameOver()) break;
            }

            // Expansion
            if (!fork.isGameOver() && !node.isExpanded()) {
                node.expand(actions);
                node = node.selectChild(explorationC, stats);
                applyAction(fork, node.action);
            }

            // Rollout + evaluate on the shared value scale
            double value = simulateAndValue(fork, rootScore);

            // Backup
            stats.update(value);
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
        MctsNode.MinMax stats = new MctsNode.MinMax();
        List<Integer> actions = allActions();
        long rootScore = liveCore.getScore();

        // Seed every column with a grounded one-ply evaluation before any rollouts. This is
        // the single change that makes UCT actually strong at this budget: with only tens of
        // rollouts over ~32 columns a from-scratch tree spends its whole budget just visiting
        // each arm once, so the pick is little better than the (weak) exploration order. By
        // paying one physics settle per column up front — the exact thing the one-ply greedy
        // does so well — every arm starts from a real value estimate, and the rollouts then
        // concentrate on refining the promising ones instead of discovering them from zero.
        root.expand(actions);
        for (MctsNode child : root.children()) {
            GameCore fork = liveCore.snapshot();
            applyAction(fork, child.action);
            double v = positionValue(fork, rootScore, fork.isGameOver());
            stats.update(v);
            child.backup(v);
        }

        long deadline = searchDeadlineNs;   // read once per search
        for (int r = 0; r < rollouts; r++) {
            if (r > 0 && System.nanoTime() > deadline) break;  // time budget exceeded
            GameCore fork = liveCore.snapshot();

            MctsNode node = root;
            while (node.isExpanded() && !node.isLeaf()) {
                node = node.selectChild(explorationC, stats);
                applyAction(fork, node.action);
                if (fork.isGameOver()) break;
            }
            if (!fork.isGameOver() && !node.isExpanded()) {
                node.expand(actions);
                node = node.selectChild(explorationC, stats);
                applyAction(fork, node.action);
            }
            double value = simulateAndValue(fork, rootScore);
            stats.update(value);
            node.backup(value);
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

    /**
     * Plays a short heuristic-guided rollout from {@code fork}, then scores the whole line
     * from the root with {@link #positionValue}. ε-greedy over the shared {@link HeuristicAgent}
     * default policy: pure-random play almost never merges, giving a flat value signal UCB1
     * can't differentiate on, so a cheap survival-aware default policy makes rollouts
     * informative — which is what lets the search concentrate on genuinely strong lines.
     */
    private double simulateAndValue(GameCore fork, long rootScore) {
        boolean terminated = fork.isGameOver();
        for (int d = 0; d < rolloutDepth && !fork.isGameOver(); d++) {
            int a = (rng.nextDouble() < 0.85) ? heuristicAction(fork.getState()) : rng.nextInt(actionBins);
            applyAction(fork, a);
            if (fork.isGameOver()) { terminated = true; break; }
        }
        return positionValue(fork, rootScore, terminated);
    }

    /**
     * The one value scale shared by the root-column seeds and the rollouts: total merge
     * points gained since the root plus the board {@link BoardEval#health health} of where
     * the line ended up, or a large fixed penalty (minus whatever it managed to score first)
     * if the line died. Putting seeds and rollouts on the same scale is what makes the
     * min-max normalisation and the visit counts mean the same thing across a search.
     */
    private double positionValue(GameCore core, long rootScore, boolean terminated) {
        double gained = core.getScore() - rootScore;
        if (terminated) return gained - BoardEval.GAME_OVER_PENALTY;
        return gained + BoardEval.health(core.getState());
    }

    /** Cheap merge-seek / low-stack / dead-line-defending default policy used inside
     *  rollouts — the same rule the {@link HeuristicAgent} baseline plays, so the
     *  planner's imagined futures are rolled out with a sensible, survival-aware policy
     *  rather than near-random play. */
    private int heuristicAction(GameState s) {
        return HeuristicAgent.bestColumn(s, actionBins);
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
