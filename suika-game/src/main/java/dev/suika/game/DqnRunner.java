package dev.suika.game;

import dev.suika.ai.ActionSpec;
import dev.suika.ai.AgentPlugin;
import dev.suika.ai.DqnTrainer;
import dev.suika.ai.MlpPolicy;
import dev.suika.core.GameCore;
import dev.suika.core.GameState;
import dev.suika.env.StateObservationEncoder;

/**
 * Control center for the JVM-native DQN — a real value-based deep-RL loop running
 * live: the board is played ε-greedily by the online Q-network, every
 * (state, action, reward, next-state) transition lands in the replay buffer, and a
 * background thread performs mini-batch TD updates against a target network
 * continuously. Loss, exploration ε, and the score stream to the charts so you watch
 * Q-learning converge in real time.
 */
public final class DqnRunner extends AgentRunner {

    private DqnTrainer dqn;
    private final StateObservationEncoder encoder = new StateObservationEncoder();

    private final LiveChart lossChart = new LiveChart(260);
    private final LiveChart epsChart  = new LiveChart(260);

    private volatile boolean running = false;
    private Thread worker;
    private final java.util.concurrent.atomic.AtomicLong epoch = new java.util.concurrent.atomic.AtomicLong(0);

    // Pending transition: filled when the ε-greedy agent picks a move, closed when the
    // NEXT decision is made (reward = score gained in between) or the game ends.
    private final Object transitionLock = new Object();
    private float[] pendingObs;
    private int     pendingAction;
    private long    scoreAtAction;

    /** Raw score penalty applied to the terminal transition (scaled inside the trainer). */
    private static final double TERMINAL_PENALTY = 250.0;

    private float chartTimer = 0f;

    public DqnRunner(SuikaGame game, PlaygroundConfig cfg) { super(game, cfg); }

    /** The ε-greedy playing agent: picks via the trainer (so exploration is real) and
     *  closes/opens the pending transition around each decision. */
    private final class EpsGreedyAgent implements AgentPlugin {
        @Override public String id()          { return "dqn"; }
        @Override public String displayName() { return "DQN (e-greedy)"; }
        @Override public Object selectAction(GameState state, ActionSpec spec) {
            float[] obs = encoder.encode(state);
            synchronized (transitionLock) {
                if (pendingObs != null) {
                    double reward = state.score() - scoreAtAction;
                    dqn.recordTransition(pendingObs, pendingAction, reward, obs);
                }
                int a = dqn.selectAction(obs);
                a = Math.min(a, spec.bins() - 1);
                pendingObs = obs;
                pendingAction = a;
                scoreAtAction = state.score();
                return a;
            }
        }
        @Override public Object selectAction(GameCore core, ActionSpec spec) {
            return selectAction(core.getState(), spec);
        }
    }

    @Override
    public void start() {
        super.start();
        dqn = new DqnTrainer(cfg.learningRate, seed, cfg.actionBins);
        setAgent(new EpsGreedyAgent());
        running = true;
        long myEpoch = epoch.incrementAndGet();
        worker = new Thread(() -> trainLoop(myEpoch), "dqn-trainer");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void restart() {
        epoch.incrementAndGet();
        if (worker != null) worker.interrupt();
        if (dqn != null) dqn.close();
        lossChart.clear();
        epsChart.clear();
        synchronized (transitionLock) { pendingObs = null; }
        start();
    }

    private void trainLoop(long myEpoch) {
        while (running && epoch.get() == myEpoch) {
            try {
                dqn.update();
                // A single mini-batch is a few ms of CPU — pace the loop so training is
                // continuous but never starves the render/physics threads of a core.
                Thread.sleep(4);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                return;
            }
        }
    }

    /** Game over closes the pending transition as terminal (no next state). */
    @Override
    protected void onNewGame() {
        super.onNewGame();
        DqnTrainer t = dqn;
        if (t == null) return;
        synchronized (transitionLock) {
            if (pendingObs != null) {
                double reward = (core != null ? core.getScore() : 0) - scoreAtAction - TERMINAL_PENALTY;
                t.recordTransition(pendingObs, pendingAction, reward, null);
                pendingObs = null;
            }
        }
    }

    @Override
    protected void onUpdate(float dt) {
        super.onUpdate(dt);
        chartTimer -= dt;
        if (chartTimer <= 0f && dqn != null) {
            if (dqn.updateCount() > 0) lossChart.add((float) dqn.lastLoss());
            epsChart.add((float) (dqn.epsilon() * 100.0));
            chartTimer = 0.5f;
        }
    }

    @Override public String title()    { return cfg.technique.display; }
    @Override public String subtitle() {
        if (dqn == null) return "Deep RL  ·  starting";
        return "Deep RL  ·  " + dqn.updateCount() + " TD updates  ·  eps "
                + String.format("%.0f%%", dqn.epsilon() * 100);
    }

    @Override
    public String[] stats() {
        DqnTrainer t = dqn;
        java.util.List<String> s = new java.util.ArrayList<>();
        s.add("score        " + core.getScore());
        s.add("best         " + bestScore());
        s.add("games        " + gamesPlayed);
        s.add("epsilon      " + (t != null ? String.format("%.0f%%  (exploring)", t.epsilon() * 100) : "—"));
        s.add("replay       " + (t != null ? t.replaySize() + " transitions" : "—"));
        s.add("td updates   " + (t != null ? Integer.toString(t.updateCount()) : "—"));
        s.add("loss         " + (t != null ? String.format("%.4f", t.lastLoss()) : "—"));
        s.add("speed        " + cfg.speedLabel());
        return s.toArray(new String[0]);
    }

    @Override
    public String[] extendedStats() {
        java.util.List<String> s = new java.util.ArrayList<>();
        s.add("algorithm    Q-learning with replay buffer + target network");
        s.add("net          584 -> 64 tanh -> " + cfg.actionBins + " Q-values (one per column)");
        s.add("update       batch of 64 replayed moves, TD target from a frozen");
        s.add("             copy of the net synced every 250 updates");
        s.add("learning rate " + String.format("%.0e", cfg.learningRate)
                + "  ·  reward = merge score gained per drop");
        s.add("exploration  eps-greedy, decaying 100% -> 5% over ~4k moves");
        s.add("why replay   consecutive moves are correlated; random batches");
        s.add("             from the buffer keep the updates stable");
        s.add("doing now    " + cfg.technique.liveHint());
        s.add("tendency     " + tendencyLabel());
        return s.toArray(new String[0]);
    }

    @Override public LiveChart chart2()      { return lossChart; }
    @Override public String    chart2Label() {
        return lossChart.size() == 0 ? "TD loss (buffering replay)"
                : "TD loss  ·  " + String.format("%.4f", dqn != null ? dqn.lastLoss() : 0);
    }
    @Override public LiveChart chart3()      { return epsChart; }
    @Override public String    chart3Label() {
        return "exploration eps %  ·  " + (dqn != null ? Math.round(dqn.epsilon() * 100) : 100) + "%";
    }

    @Override
    public void dispose() {
        running = false;
        epoch.incrementAndGet();
        if (worker != null) worker.interrupt();
        if (dqn != null) dqn.close();
    }

    // -------------------------------------------------------------------------
    // Save / load — trained Q-net weights + chart history (see ModelSlots).
    // -------------------------------------------------------------------------

    public boolean saveToSlot(int slot) {
        if (dqn == null) return false;
        java.util.Map<String, float[]> graphs = new java.util.LinkedHashMap<>();
        graphs.put("tdLoss",   lossChart.export());
        graphs.put("epsilon",  epsChart.export());
        graphs.put("gameScores", gameScoreChart.export());
        ModelSlots.save(cfg.technique.id, slot, dqn.policy(), bestScore(),
                new ModelSlots.SaveExtras(graphs));
        return true;
    }

    public boolean loadFromSlot(int slot) {
        MlpPolicy p = ModelSlots.newCompatiblePolicy();
        if (!ModelSlots.load(cfg.technique.id, slot, p)) return false;
        if (dqn == null) dqn = new DqnTrainer(cfg.learningRate, seed, cfg.actionBins);
        dqn.loadWeights(p);
        var graphs = ModelSlots.loadGraphs(cfg.technique.id, slot);
        if (graphs.containsKey("tdLoss"))     lossChart.importSeries(graphs.get("tdLoss"));
        if (graphs.containsKey("epsilon"))    epsChart.importSeries(graphs.get("epsilon"));
        if (graphs.containsKey("gameScores")) gameScoreChart.importSeries(graphs.get("gameScores"));
        return true;
    }

    public ModelSlots.SlotInfo slotInfo(int slot) { return ModelSlots.info(cfg.technique.id, slot); }
}
