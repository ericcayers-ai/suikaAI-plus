package dev.suika.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Deep Q-Network trainer (Mnih et al. 2015, adapted to this project's small MLP) —
 * a real, JVM-native value-based deep RL learner:
 * <ul>
 *   <li><b>Experience replay</b>: a bounded ring buffer of (s, a, r, s', done)
 *       transitions; each update samples a random mini-batch, breaking the temporal
 *       correlation that destabilises naive online Q-learning.</li>
 *   <li><b>Target network</b>: a periodically-synced frozen copy provides the TD
 *       bootstrap target {@code r + γ·max_a' Q_target(s', a')}, preventing the
 *       chase-your-own-tail divergence of bootstrapping from the live net.</li>
 *   <li><b>ε-greedy exploration</b> with linear decay: the caller asks
 *       {@link #selectAction} which explores early and exploits once trained.</li>
 * </ul>
 *
 * <p>Rewards are the raw merge-score deltas scaled by {@code REWARD_SCALE} so TD
 * targets live in a well-conditioned range for the tanh MLP. All methods are
 * thread-safe: the game thread calls {@link #recordTransition}/{@link #selectAction}
 * while a background thread calls {@link #update()}.
 */
public final class DqnTrainer implements TrainerPlugin, AutoCloseable {

    private static final int INPUT_SIZE  = dev.suika.env.StateObservationEncoder.TOTAL;
    private static final int HIDDEN_SIZE = 64;
    private static final int OUTPUT_BINS = 32;

    private static final double GAMMA        = 0.97;
    private static final double REWARD_SCALE = 0.02;   // ~50-point merge → reward 1.0
    private static final int    BATCH_SIZE   = 64;
    private static final int    MIN_REPLAY   = 200;    // don't train on a near-empty buffer
    private static final int    REPLAY_CAP   = 50_000;
    private static final int    TARGET_SYNC_EVERY = 250; // updates between target syncs

    /** One stored transition. {@code nextObs} is null on terminal transitions. */
    private record Transition(float[] obs, int action, double reward, float[] nextObs) {}

    private final MlpPolicy online = new MlpPolicy(INPUT_SIZE, HIDDEN_SIZE, OUTPUT_BINS);
    private final MlpPolicy target = new MlpPolicy(INPUT_SIZE, HIDDEN_SIZE, OUTPUT_BINS);
    private final ArrayDeque<Transition> replay = new ArrayDeque<>();
    private final Random rng;
    private final double learningRate;
    private final int actionBins;

    // ε decays linearly from EPS_START to EPS_END over EPS_DECAY_STEPS recorded moves.
    private static final double EPS_START = 1.0, EPS_END = 0.05;
    private static final int    EPS_DECAY_STEPS = 4_000;
    private volatile long movesSeen = 0;

    private volatile int    updates = 0;
    private volatile double lastLoss = 0;
    private volatile long   transitionsSeen = 0;

    public DqnTrainer() { this(1e-3, 0L, OUTPUT_BINS); }

    public DqnTrainer(double learningRate, long seed, int actionBins) {
        this.learningRate = learningRate;
        this.rng = new Random(seed);
        this.actionBins = Math.min(actionBins, OUTPUT_BINS);
        online.initRandom(rng);
        target.setWeights(online.getWeights());
    }

    @Override public String id() { return "dqn"; }
    @Override public void observe(dev.suika.core.StepResult transition) { /* fed via recordTransition */ }

    /** Current exploration rate. */
    public double epsilon() {
        double t = Math.min(1.0, movesSeen / (double) EPS_DECAY_STEPS);
        return EPS_START + (EPS_END - EPS_START) * t;
    }

    /** ε-greedy action for an encoded observation; ticks the exploration schedule. */
    public int selectAction(float[] obs) {
        movesSeen++;
        if (rng.nextDouble() < epsilon()) return rng.nextInt(actionBins);
        int a = online.greedyAction(obs);
        return Math.min(a, actionBins - 1);
    }

    /** Greedy (no exploration) action — used for evaluation/ghost boards. */
    public int greedyAction(float[] obs) {
        return Math.min(online.greedyAction(obs), actionBins - 1);
    }

    /** Store one transition. {@code nextObs} null ⇒ terminal. */
    public synchronized void recordTransition(float[] obs, int action, double rewardRaw, float[] nextObs) {
        replay.addLast(new Transition(obs, action, rewardRaw * REWARD_SCALE, nextObs));
        if (replay.size() > REPLAY_CAP) replay.removeFirst();
        transitionsSeen++;
    }

    /** One mini-batch TD update; no-op until the buffer holds {@link #MIN_REPLAY}. */
    @Override
    public void update() {
        Transition[] batch;
        synchronized (this) {
            if (replay.size() < MIN_REPLAY) return;
            // Reservoir-free random sample: copy refs once, index randomly.
            Transition[] all = replay.toArray(new Transition[0]);
            batch = new Transition[BATCH_SIZE];
            for (int i = 0; i < BATCH_SIZE; i++) batch[i] = all[rng.nextInt(all.length)];
        }

        float[][] inputs = new float[BATCH_SIZE][];
        int[] actions = new int[BATCH_SIZE];
        double[] targets = new double[BATCH_SIZE];
        double lossAcc = 0;
        for (int i = 0; i < BATCH_SIZE; i++) {
            Transition t = batch[i];
            inputs[i] = t.obs();
            actions[i] = t.action();
            double y = t.reward();
            if (t.nextObs() != null) {
                double[] q = target.forward(t.nextObs());
                double max = Double.NEGATIVE_INFINITY;
                for (int a = 0; a < actionBins; a++) max = Math.max(max, q[a]);
                y += GAMMA * max;
            }
            targets[i] = y;
            double[] qOnline = online.forward(t.obs());
            double d = qOnline[t.action()] - y;
            lossAcc += d * d;
        }
        lastLoss = lossAcc / BATCH_SIZE;

        double[] grad = online.backpropQGradient(inputs, actions, targets);
        double[] w = online.getWeights();
        for (int i = 0; i < w.length; i++) w[i] -= learningRate * grad[i];
        online.setWeights(w);

        updates++;
        if (updates % TARGET_SYNC_EVERY == 0) target.setWeights(online.getWeights());
    }

    /** The live online network (compatible with every {@link ModelSlots}-style save). */
    public MlpPolicy policy()       { return online; }
    public int       updateCount()  { return updates; }
    public double    lastLoss()     { return lastLoss; }
    public long      replaySize()   { synchronized (this) { return replay.size(); } }
    public long      transitions()  { return transitionsSeen; }

    /** A play-ready greedy agent over the current online net. */
    public NeuralAgent greedyAgent() {
        MlpPolicy copy = new MlpPolicy(INPUT_SIZE, HIDDEN_SIZE, OUTPUT_BINS);
        copy.setWeights(online.getWeights());
        return new NeuralAgent(copy);
    }

    /** Load previously-saved weights into both online and target nets. */
    public void loadWeights(MlpPolicy source) {
        online.setWeights(source.getWeights());
        target.setWeights(source.getWeights());
    }

    /** Snapshot of top agents for elite-view style UIs (only one net — returns it). */
    public List<AgentPlugin> topAgents(int n) {
        List<AgentPlugin> out = new ArrayList<>();
        if (n > 0) out.add(greedyAgent());
        return out;
    }

    @Override public void close() { }
}
