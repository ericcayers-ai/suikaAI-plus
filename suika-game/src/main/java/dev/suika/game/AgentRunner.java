package dev.suika.game;

import dev.suika.ai.ActionSpec;
import dev.suika.ai.AgentPlugin;
import dev.suika.ai.GreedyOnePlyAgent;
import dev.suika.ai.MctsAgent;
import dev.suika.core.GameCore;
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

    /**
     * Combined per-column visit counts from the most recent root-parallel MCTS search
     * (see {@link #parallelMctsSelect}), or {@code null} when the last search was
     * single-tree (parallelism off / not an MctsAgent). {@link #columnBars()} prefers
     * this over a lone tree's counts when present.
     */
    private volatile int[] parallelVisits = null;
    private volatile int   parallelWorkers = 1;

    /** Per-game final scores for the game-score-history chart (chart2 in PlanningRunner). */
    protected final LiveChart gameScoreChart = new LiveChart(200);
    protected int gamesPlayed = 0;

    private final long sessionStartNs = System.nanoTime();
    /** Wall-clock seconds since this control-center session started (resets on BACK → relaunch). */
    protected double elapsedSeconds() { return (System.nanoTime() - sessionStartNs) / 1_000_000_000.0; }

    protected AgentRunner(SuikaGame game, PlaygroundConfig cfg) {
        super(game, cfg);
        this.spec  = ActionSpec.discrete(cfg.actionBins);
        this.agent = Agents.build(cfg);
        this.moveTimer = baseDelay();
    }

    /** Subclasses (evolution) call this to hot-swap the playing policy. */
    protected void setAgent(AgentPlugin a) { if (a != null) this.agent = a; }
    protected AgentPlugin agent() { return agent; }

    /**
     * Unlike the base RESTART (board reset only), this rebuilds the agent from the
     * current config too — so changing Rollouts/Population/Return/LR or Parallelism
     * via the quick-settings hotswap and pressing RESTART actually takes effect,
     * instead of silently continuing to play with the agent built at launch time.
     * {@link EvolutionRunner} overrides this further (rebuilds its trainer, not just
     * a single agent).
     */
    @Override
    public void restart() {
        this.agent = Agents.build(cfg);
        newGame();
    }

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
        long deadlineNs = (a instanceof MctsAgent && cfg.maxThinkMs > 0)
                ? System.nanoTime() + cfg.maxThinkMs * 1_000_000L : 0L;
        if (a instanceof MctsAgent m && deadlineNs > 0) m.setSearchDeadline(deadlineNs);
        Thread t = new Thread(() -> {
            long t0 = System.nanoTime();
            Object act;
            if (a instanceof MctsAgent m0 && cfg.technique.parallel && cfg.evalThreads() > 1) {
                act = parallelMctsSelect(m0, snap, deadlineNs);
            } else {
                act = a.selectAction(snap, spec);
                if (a instanceof MctsAgent) parallelVisits = null; // show this lone tree's own visits
                parallelWorkers = 1;
            }
            double x = spec.toDropX(act, PhysicsConfig.DROP_X_MIN, PhysicsConfig.DROP_X_MAX);
            lastThinkMs = (System.nanoTime() - t0) / 1_000_000;
            thinking = false;
            result.set(x);
        }, "playground-think");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Root-parallel search: several independent MCTS trees search the SAME decision
     * concurrently (each its own fork of {@code snap} — {@link GameCore#snapshot()} is
     * safe to call from multiple threads on a shared source, see its javadoc), then
     * their per-column visit counts are summed and the busiest column wins. This is
     * genuinely more simulations happening at once for one move, not just a larger
     * rollout count run serially — the whole point of exposing "Parallelism" here.
     */
    private Integer parallelMctsSelect(MctsAgent primary, GameCore snap, long deadlineNs) {
        int threads = Math.max(1, Math.min(16, cfg.evalThreads()));
        MctsAgent[] forks = new MctsAgent[threads];
        GameCore[]  snaps = new GameCore[threads];
        for (int i = 0; i < threads; i++) {
            forks[i] = i == 0 ? primary : primary.fork();
            if (deadlineNs > 0) forks[i].setSearchDeadline(deadlineNs);
            snaps[i] = snap.snapshot();
        }
        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            workers[i] = new Thread(() -> forks[idx].selectAction(snaps[idx], spec), "mcts-root-" + i);
            workers[i].start();
        }
        for (Thread w : workers) {
            try { w.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        int bins = spec.bins();
        int[] combined = new int[bins];
        for (MctsAgent f : forks) {
            int[] v = f.lastVisits();
            for (int b = 0; b < bins && b < v.length; b++) combined[b] += v[b];
        }
        int best = 0;
        for (int b = 1; b < bins; b++) if (combined[b] > combined[best]) best = b;
        parallelVisits  = combined;
        parallelWorkers = threads;
        return best;
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
        parallelVisits = null;
        result.set(null);
    }

    @Override public float markerX() { return markerX; }

    @Override
    public int[] columnBars() {
        AgentPlugin a = agent;
        if (a instanceof MctsAgent m) {
            int[] pv = parallelVisits;
            if (pv != null && pv.length > 0) return pv;
            if (m.lastVisits().length > 0) return m.lastVisits();
        }
        if (a instanceof GreedyOnePlyAgent g && g.lastScores().length > 0) return g.lastScores();
        if (a instanceof Agents.GenerativeAgent g) {
            double[][] history = g.lastStepHistory();
            if (history.length > 0) {
                // Final step's distribution — what was actually sampled from after
                // refining from noise. Scaled to 0-100 like Greedy's bars, since these
                // are probabilities (typically all under 1.0), not raw counts.
                double[] finalDist = history[history.length - 1];
                double max = 0; for (double v : finalDist) max = Math.max(max, v);
                int[] bars = new int[finalDist.length];
                if (max > 1e-9) for (int i = 0; i < bars.length; i++) bars[i] = (int) Math.round(100.0 * finalDist[i] / max);
                return bars;
            }
        }
        return null;
    }

    protected boolean thinking()   { return thinking; }
    protected long    thinkMs()    { return lastThinkMs; }
    protected long    bestScore()  { return bestScore; }
    /** How many independent search trees / column-evaluators ran for the last move. */
    protected int     parallelWorkers() { return parallelWorkers; }
}
