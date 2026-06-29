package dev.suika.game;

import dev.suika.ai.AgentPlugin;
import dev.suika.ai.GreedyOnePlyAgent;
import dev.suika.ai.HeuristicAgent;
import dev.suika.ai.MctsAgent;
import dev.suika.ai.RandomAgent;

/**
 * The roster of JVM-native agents selectable in AI-Watch mode (ROADMAP §IV).
 *
 * <p>Each entry carries a display name, a one-line description, and a factory that
 * builds a fresh agent for the configured number of drop columns. All of these run
 * with no Python and no GPU, so they ship inside the player build.
 */
public final class WatchAgents {

    private WatchAgents() {}

    /** A selectable agent definition. */
    public record Entry(String id, String name, String blurb, Factory factory) {}

    /** Builds an agent for a given number of discrete drop columns. */
    public interface Factory { AgentPlugin create(int actionBins); }

    private static final Entry[] ENTRIES = {
        new Entry("random", "Random",
                "Uniformly random drops — the floor every learner must beat.",
                bins -> new RandomAgent()),
        new Entry("heuristic", "Heuristic",
                "Hand-coded: seek same-tier merges, otherwise keep the surface flat.",
                bins -> new HeuristicAgent()),
        new Entry("greedy", "Greedy One-Ply",
                "Tries every column for real, picks the best immediate score.",
                GreedyOnePlyAgent::new),
        new Entry("mcts-fast", "MCTS Fast",
                "Monte-Carlo Tree Search, 60 rollouts/move. Snappy planning.",
                bins -> new MctsAgent(60, Math.sqrt(2), 5, bins)),
        new Entry("mcts-strong", "MCTS Strong",
                "Monte-Carlo Tree Search, 150 rollouts/move. Plays to win.",
                bins -> new MctsAgent(150, Math.sqrt(2), 7, bins)),
    };

    /** Default selection: MCTS — Fast (index into {@link #ENTRIES}). */
    public static final int DEFAULT_INDEX = 3;

    public static int count() { return ENTRIES.length; }

    public static Entry get(int index) {
        return ENTRIES[Math.floorMod(index, ENTRIES.length)];
    }

    public static AgentPlugin create(int index, int actionBins) {
        return get(index).factory().create(actionBins);
    }
}
