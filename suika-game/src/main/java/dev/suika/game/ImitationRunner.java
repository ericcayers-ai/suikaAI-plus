package dev.suika.game;

import dev.suika.ai.ActionSpec;
import dev.suika.ai.BehavioralCloningTrainer;
import dev.suika.ai.DemoDataset;
import dev.suika.ai.Demonstration;
import dev.suika.ai.MctsAgent;
import dev.suika.ai.NeuralAgent;
import dev.suika.core.GameCore;
import dev.suika.core.GameState;
import dev.suika.core.PhysicsConfig;
import dev.suika.env.StateObservationEncoder;

import java.util.List;

/**
 * Control center for imitation learning (Behavioral Cloning, DAgger) — the
 * "Train an AI on my playstyle" flow (ROADMAP §IV.6).
 *
 * <p>Dynamic flow: a "Train the AI" card pops up first; the AI waits and only
 * <em>watches</em> while you play the first full game, capturing every (board, drop)
 * pair. Once that game ends it keeps training live as you keep playing — the loss and
 * action-match accuracy stream to the charts, and the AI's predicted drop is shown as
 * a gold marker so you can see it learning to copy you. DAgger additionally aggregates
 * MCTS-expert labels so the clone is pulled toward strong play, not just yours.
 */
public final class ImitationRunner extends LiveBoardRunner {

    private final boolean isDagger;
    private final StateObservationEncoder encoder = new StateObservationEncoder();
    private final DemoDataset dataset = new DemoDataset(7L);
    private final ActionSpec spec;
    private BehavioralCloningTrainer bc;

    private enum Phase { WATCH_FIRST, TRAIN }
    private volatile Phase phase = Phase.WATCH_FIRST;
    private boolean firstDropDone = false;
    private float restartTimer = -1f;

    private float hoverX = (float) ((PhysicsConfig.DROP_X_MIN + PhysicsConfig.DROP_X_MAX) / 2.0);
    private float predictedX = Float.NaN;

    private final LiveChart lossChart = new LiveChart(200);
    private final LiveChart accChart  = new LiveChart(200);
    private volatile double loss = 0, accuracy = 0;
    private volatile int updates = 0;
    private float accTimer = 0f;

    private volatile boolean running = false;
    private Thread worker;

    public ImitationRunner(SuikaGame game, PlaygroundConfig cfg) {
        super(game, cfg);
        this.isDagger = cfg.technique == AiTechnique.DAGGER;
        this.spec = ActionSpec.discrete(cfg.actionBins);
    }

    @Override
    protected void onNewGame() {
        // dataset + trainer persist across games; only the board resets.
        if (bc == null) bc = new BehavioralCloningTrainer(dataset, cfg.learningRate, 4);
    }

    @Override public boolean acceptsHumanInput() { return true; }
    @Override public float   hoverX()            { return hoverX; }
    @Override public void    setHover(float gx)  { this.hoverX = gx; }

    @Override
    public void humanDrop(float gx) {
        if (core.isGameOver()) return;
        // capture demonstration: board state -> the column the human chose
        float[] obs = encoder.encode(core.getState());
        dataset.add(new Demonstration(obs, xToBin(gx), 0.0, false));
        firstDropDone = true;
        doDrop(gx);
    }

    @Override
    protected void onUpdate(float dt) {
        // predicted drop marker (cheap single forward pass) once we have a policy
        if (phase == Phase.TRAIN && bc != null && !core.isGameOver()) {
            NeuralAgent a = bc.trainedAgent();
            Object act = a.selectAction(core.getState(), spec);
            predictedX = (float) spec.toDropX(act, PhysicsConfig.DROP_X_MIN, PhysicsConfig.DROP_X_MAX);
        }

        // periodic action-match accuracy on the captured demos
        accTimer -= dt;
        if (accTimer <= 0f && phase == Phase.TRAIN && dataset.size() > 0) {
            accuracy = measureAccuracy();
            accChart.add((float) accuracy);
            accTimer = 0.5f;
        }

        if (core.isGameOver()) {
            if (phase == Phase.WATCH_FIRST) startTraining();
            if (restartTimer < 0f) restartTimer = 1.4f;
            restartTimer -= dt;
            if (restartTimer <= 0f) { newGame(); restartTimer = -1f; }
        }
    }

    private void startTraining() {
        phase = Phase.TRAIN;
        if (bc == null) bc = new BehavioralCloningTrainer(dataset, cfg.learningRate, 4);
        running = true;
        worker = new Thread(this::trainLoop, "imitation-trainer");
        worker.setDaemon(true);
        worker.start();
    }

    private void trainLoop() {
        MctsAgent expert = isDagger ? new MctsAgent(40, Math.sqrt(2), 5, cfg.actionBins) : null;
        long expertSeed = 4242L;
        while (running) {
            try {
                // DAgger: aggregate MCTS-expert labels from fresh states.
                if (isDagger) {
                    GameCore c = new GameCore(expertSeed++);
                    int steps = 4 + (int) (Math.random() * 8);
                    for (int i = 0; i < steps && !c.isGameOver(); i++) {
                        float[] obs = encoder.encode(c.getState());
                        int a = (int) expert.selectAction(c, spec);
                        dataset.add(new Demonstration(obs, a, 0, false));
                        c.dropAndSettle(spec.toDropX(a, PhysicsConfig.DROP_X_MIN, PhysicsConfig.DROP_X_MAX));
                    }
                }
                if (dataset.size() > 0) {
                    bc.update();
                    updates = bc.updateCount();
                    loss = currentLoss();
                    lossChart.add((float) loss);
                }
            } catch (Exception e) {
                running = false;
            }
        }
    }

    private double currentLoss() {
        List<Demonstration> batch = dataset.sample(Math.min(16, dataset.size()));
        if (batch.isEmpty()) return 0;
        NeuralAgent a = bc.trainedAgent();
        double l = 0; int n = 0;
        for (Demonstration d : batch) {
            double[] logits = bc.policy().forward(d.observation());
            double max = Double.NEGATIVE_INFINITY;
            for (double v : logits) max = Math.max(max, v);
            double sum = 0; for (double v : logits) sum += Math.exp(v - max);
            l -= (logits[d.action()] - max) - Math.log(sum);
            n++;
        }
        return n > 0 ? l / n : 0;
    }

    private double measureAccuracy() {
        List<Demonstration> batch = dataset.sample(Math.min(24, dataset.size()));
        if (batch.isEmpty()) return 0;
        int correct = 0;
        for (Demonstration d : batch) {
            int pred = bc.policy().greedyAction(d.observation());
            if (pred == d.action()) correct++;
        }
        return 100.0 * correct / batch.size();
    }

    private int xToBin(float gx) {
        double t = (gx - PhysicsConfig.DROP_X_MIN) / (PhysicsConfig.DROP_X_MAX - PhysicsConfig.DROP_X_MIN);
        return Math.max(0, Math.min(cfg.actionBins - 1, (int) Math.round(t * (cfg.actionBins - 1))));
    }

    @Override public float markerX() { return phase == Phase.TRAIN ? predictedX : Float.NaN; }

    @Override
    public boolean modalActive() { return phase == Phase.WATCH_FIRST && !firstDropDone; }
    @Override public String modalTitle() { return "Train the AI on your playstyle"; }
    @Override public String[] modalBody() {
        return new String[]{
            (isDagger ? "DAgger" : "Behavioral Cloning") + " — imitation learning",
            "",
            "Play a full game. The AI watches and records every",
            "drop you make. When the game ends it starts training",
            "live, and keeps learning as you keep playing.",
            "",
            "Click in the well to drop and begin.",
        };
    }

    @Override public String title()    { return cfg.technique.display; }
    @Override public String subtitle() {
        return phase == Phase.WATCH_FIRST ? "Imitation  ·  watching you (game 1)"
                                          : "Imitation  ·  training live  ·  " + updates + " updates";
    }

    @Override
    public String[] stats() {
        return new String[]{
            "phase        " + (phase == Phase.WATCH_FIRST ? "capture (game 1)" : "train + play"),
            "demos        " + dataset.size(),
            "bc updates   " + updates,
            "loss         " + String.format("%.3f", loss),
            "match acc.   " + String.format("%.0f%%", accuracy),
            "your score   " + core.getScore(),
            isDagger ? "expert       MCTS relabeling" : "method       supervised cloning",
        };
    }

    @Override public LiveChart chart1()      { return accChart; }
    @Override public String    chart1Label() { return "match accuracy  ·  " + String.format("%.0f%%", accuracy); }
    @Override public LiveChart chart2()      { return lossChart; }
    @Override public String    chart2Label() { return "BC loss  ·  " + String.format("%.3f", loss); }

    @Override
    public void dispose() {
        running = false;
        if (worker != null) worker.interrupt();
    }
}
