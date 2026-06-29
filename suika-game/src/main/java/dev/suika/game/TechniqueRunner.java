package dev.suika.game;

import dev.suika.core.GameState;

/**
 * Backend for one technique's control center: owns whatever runs live (a played game,
 * a training loop, a capture session) and exposes the data the {@link ControlCenterScreen}
 * renders — board, diagnostics text, live charts, and an optional board-aligned bar
 * overlay ("see it think").
 */
public interface TechniqueRunner {

    void start();
    void update(float dt);
    void dispose();

    /** Board to draw (never null once started). */
    GameState board();

    String title();
    String subtitle();

    /** Diagnostic lines (runtime telemetry) shown in the side panel. */
    String[] stats();

    LiveChart chart1();  String chart1Label();
    LiveChart chart2();  String chart2Label();   // may return null
    /** Optional third chart, shown only where the panel has room (landscape). */
    default LiveChart chart3()      { return null; }
    default String    chart3Label() { return null; }

    /** Extra diagnostic lines shown below the primary stats when the panel has room. */
    default String[] extendedStats() { return new String[0]; }

    /**
     * Live boards to tile when the control center shows more than one game
     * (evolution top-N, self-play rivals). Index 0 is always the primary board.
     * Entries may be null (a slot still warming up) and are skipped by the renderer.
     */
    default GameState[] multiStates() { return new GameState[]{ board() }; }

    /** Per-board captions for {@link #multiStates()} (same length, may be empty). */
    default String[] multiLabels() { return new String[0]; }

    /** Optional per-column bars aligned to the well (MCTS visits, generative probs…). */
    default int[] columnBars() { return null; }
    /** Optional gold marker at this game-x (chosen / predicted drop); NaN to hide. */
    default float markerX() { return Float.NaN; }

    /** Human-driven runners (imitation) accept clicks as drops. */
    default boolean acceptsHumanInput() { return false; }
    default float hoverX() { return Float.NaN; }
    default void setHover(float gameX) { }
    default void humanDrop(float gameX) { }

    /** A blocking modal (e.g. imitation "Train the AI — play a game first"). */
    default boolean modalActive() { return false; }
    default String  modalTitle()  { return ""; }
    default String[] modalBody()  { return new String[0]; }

    // Runtime controls
    void setPaused(boolean paused);
    boolean paused();
    void restart();
    void setSpeed(float multiplier);
    void setParallelism(int threads);
}
