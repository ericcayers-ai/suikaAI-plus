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

    /**
     * Bumped every {@link #onNewGame()}. A think-thread captures the generation it
     * started under and only publishes its result if the game hasn't moved on —
     * otherwise a slow search (e.g. a high-rollout MCTS move) that's still running when
     * the board hits game-over and restarts could land its result AFTER the restart's
     * own reset cleared {@code result}, injecting a stale decision — computed for a
     * dead board — into the new game. Harmless in terms of bounds (the x is always
     * clamped), but it read as an erratic, out-of-nowhere drop.
     */
    private volatile int gameGen = 0;

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
                // Pondering: thinking starts the moment the chute is clear — the whole
                // waiting timespan (move cadence + settle time) is used to strategize —
                // but the DROP itself still waits for the cadence timer below, so the
                // pacing the player watches is unchanged.
                moveTimer -= dt;
                if (chuteClear() && agent != null) startThink();
            }
            case THINK -> {
                moveTimer -= dt;
                if (!thinking && moveTimer <= 0f) {
                    Double x = result.getAndSet(null);
                    if (x != null) {
                        markerX = x.floatValue();
                        doDrop(x);
                        phase = Phase.WAIT;
                        moveTimer = baseDelay();
                    }
                }
            }
        }
    }

    /** Wall-clock start of the in-flight think, for the control center's stuck-run
     *  watchdog. 0 when no think is running. */
    private volatile long thinkStartNs = 0;

    /** Milliseconds the current think has been running (0 when idle) — the safety
     *  watchdog treats a think that exceeds its window as a stuck configuration. */
    long thinkingForMs() {
        long t0 = thinkStartNs;
        return (thinking && t0 > 0) ? (System.nanoTime() - t0) / 1_000_000 : 0;
    }

    private void startThink() {
        phase = Phase.THINK;
        thinking = true;
        thinkStartNs = System.nanoTime();
        final var snap = core.snapshot();
        final AgentPlugin a = agent;
        final int myGen = gameGen;
        // Pondering budget: a planner never gets LESS than maxThinkMs, but when the drop
        // cadence leaves a longer wait ahead (slow playback speeds), it may think for the
        // whole waiting window and genuinely strategize deeper — "think in the timespans
        // of waiting". moveTimer holds the time remaining until the next drop is allowed.
        // maxThinkMs == 0 keeps the original "unlimited" meaning.
        final long deadlineNs;
        if (a instanceof MctsAgent && cfg.maxThinkMs > 0) {
            long windowNs = (long) (Math.max(0f, moveTimer) * 1_000_000_000L);
            long budgetNs = Math.max(cfg.maxThinkMs * 1_000_000L, windowNs);
            deadlineNs = System.nanoTime() + budgetNs;
        } else {
            deadlineNs = 0L;
        }
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
            // Auto drop-adjustment / precision pass: the discrete column choice is
            // refined by simulating a handful of sub-column positions around it for
            // real and keeping the exact x that scores best — utmost drop precision
            // beyond the technique's own column grid. (Settings -> Drop columns: Auto.)
            if (cfg.autoDrop) {
                x = refineDropX(snap, x, deadlineNs > 0 ? deadlineNs : Long.MAX_VALUE);
            }
            lastThinkMs = (System.nanoTime() - t0) / 1_000_000;
            // A slower think from an earlier game finishing after a restart must not
            // publish into the new one — see gameGen's javadoc.
            if (myGen != gameGen) { thinking = false; return; }
            thinking = false;
            result.set(x);
        }, "playground-think");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Local continuous refinement of a chosen drop x: simulates the drop for real at a
     * few sub-column offsets around the discrete pick and returns the exact position
     * with the best immediate outcome (score gained; a game-ending drop is heavily
     * penalised). Each probe forks its own snapshot, so the live board is untouched.
     * Respects the remaining think-time budget — probes stop the moment it's exceeded.
     */
    private double refineDropX(GameCore snap, double x, long deadlineNs) {
        double colW = (PhysicsConfig.DROP_X_MAX - PhysicsConfig.DROP_X_MIN)
                / Math.max(1, cfg.actionBins - 1);
        double bestX = x;
        double bestV = probeDrop(snap, x);
        // Two-pass local search for utmost sub-column precision: a coarse sweep across
        // ±1 column to find the best basin, then a fine sweep around it at ~1/12-column
        // resolution. Every probe is a real forked drop, so the placement is genuinely
        // the best-scoring exact x — not merely the grid center. Deadline-bounded, so at
        // turbo speeds it still at least keeps the discrete pick.
        final int COARSE = 6;
        for (int i = 0; i <= COARSE; i++) {
            if (System.nanoTime() > deadlineNs) return bestX;
            double cx = clampDropX(x - colW + (2.0 * colW) * i / COARSE);
            double v = probeDrop(snap, cx);
            if (v > bestV) { bestV = v; bestX = cx; }
        }
        double center = bestX, fineHalf = colW * 0.5;
        final int FINE = 6;
        for (int i = 0; i <= FINE; i++) {
            if (System.nanoTime() > deadlineNs) return bestX;
            double cx = clampDropX(center - fineHalf + (2.0 * fineHalf) * i / FINE);
            double v = probeDrop(snap, cx);
            if (v > bestV) { bestV = v; bestX = cx; }
        }
        return bestX;
    }

    private static double clampDropX(double x) {
        return Math.max(PhysicsConfig.DROP_X_MIN, Math.min(PhysicsConfig.DROP_X_MAX, x));
    }

    private static double probeDrop(GameCore snap, double x) {
        GameCore fork = snap.snapshot();
        long before = fork.getScore();
        var r = fork.dropAndSettle(x);
        double v = fork.getScore() - before;
        if (r.terminated()) v -= 1000.0;
        return v;
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
        gameGen++;
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
        // Ensembles built on an inner MCTS expose that search's visit distribution.
        if (a instanceof EnsembleAgents.HasMctsCore h && h.mctsCore().lastVisits().length > 0) {
            return h.mctsCore().lastVisits();
        }
        return null;
    }

    protected boolean thinking()   { return thinking; }
    protected long    thinkMs()    { return lastThinkMs; }
    protected long    bestScore()  { return bestScore; }
    /** How many independent search trees / column-evaluators ran for the last move. */
    protected int     parallelWorkers() { return parallelWorkers; }
}
