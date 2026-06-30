package dev.suika.game;

import dev.suika.ai.ActionSpec;
import dev.suika.ai.AgentPlugin;
import dev.suika.ai.GreedyOnePlyAgent;
import dev.suika.ai.MctsAgent;
import dev.suika.core.PhysicsConfig;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Base for runners whose board is played by an {@link AgentPlugin}. The agent thinks
 * on a daemon thread (so heavy planners never stutter the UI), drops on a steady
 * cadence gated by a clear chute, and the game auto-restarts on game-over so the
 * control center keeps streaming live data. The current agent is swappable, which
 * lets {@link EvolutionRunner} hot-swap in each new champion.
 */
public abstract class AgentRunner extends LiveBoardRunner {

    private volatile AgentPlugin agent;
    private final ActionSpec spec;

    private enum Phase { WAIT, THINK }
    private Phase phase = Phase.WAIT;
    private float moveTimer;
    private final AtomicReference<Double> result = new AtomicReference<>(null);
    private volatile boolean thinking = false;
    private volatile long lastThinkMs = 0;

    private float markerX = Float.NaN;
    protected long bestScore = 0;

    /** Per-game final scores for the game-score-history chart (chart2 in PlanningRunner). */
    protected final LiveChart gameScoreChart = new LiveChart(200);
    protected int gamesPlayed = 0;

    protected AgentRunner(SuikaGame game, PlaygroundConfig cfg) {
        super(game, cfg);
        this.spec  = ActionSpec.discrete(cfg.actionBins);
        this.agent = Agents.build(cfg);
        this.moveTimer = baseDelay();
    }

    /** Subclasses (evolution) call this to hot-swap the playing policy. */
    protected void setAgent(AgentPlugin a) { if (a != null) this.agent = a; }
    protected AgentPlugin agent() { return agent; }

    protected float baseDelay() { return Math.max(0.05f, 0.6f / Math.max(0.1f, speed)); }

    @Override
    protected void onUpdate(float dt) {
        bestScore = Math.max(bestScore, core.getScore());
        if (core.isGameOver()) {
            // No pause between games — restart immediately so the agent keeps playing.
            newGame();
            return;
        }
        switch (phase) {
            case WAIT -> {
                moveTimer -= dt;
                if (moveTimer <= 0f && chuteClear() && agent != null) startThink();
            }
            case THINK -> {
                Double x = result.getAndSet(null);
                if (x != null && !thinking) {
                    markerX = x.floatValue();
                    doDrop(x);
                    phase = Phase.WAIT;
                    moveTimer = baseDelay();
                }
            }
        }
    }

    private void startThink() {
        phase = Phase.THINK;
        thinking = true;
        final var snap = core.snapshot();
        final AgentPlugin a = agent;
        // Set a wall-clock deadline so MCTS never stalls at fast speeds.
        if (a instanceof MctsAgent m && cfg.maxThinkMs > 0) {
            m.setSearchDeadline(System.nanoTime() + cfg.maxThinkMs * 1_000_000L);
        }
        Thread t = new Thread(() -> {
            long t0 = System.nanoTime();
            Object act = a.selectAction(snap, spec);
            double x = spec.toDropX(act, PhysicsConfig.DROP_X_MIN, PhysicsConfig.DROP_X_MAX);
            lastThinkMs = (System.nanoTime() - t0) / 1_000_000;
            thinking = false;
            result.set(x);
        }, "playground-think");
        t.setDaemon(true);
        t.start();
    }

    @Override
    protected void newGame() {
        if (core != null && core.getScore() > 0) gameScoreChart.add(core.getScore());
        gamesPlayed++;
        super.newGame();
    }

    @Override
    protected void onNewGame() {
        phase = Phase.WAIT;
        moveTimer = baseDelay();
        markerX = Float.NaN;
        result.set(null);
    }

    @Override public float markerX() { return markerX; }

    @Override
    public int[] columnBars() {
        AgentPlugin a = agent;
        if (a instanceof MctsAgent m && m.lastVisits().length > 0) return m.lastVisits();
        if (a instanceof GreedyOnePlyAgent g && g.lastScores().length > 0) return g.lastScores();
        return null;
    }

    protected boolean thinking()   { return thinking; }
    protected long    thinkMs()    { return lastThinkMs; }
    protected long    bestScore()  { return bestScore; }
}
