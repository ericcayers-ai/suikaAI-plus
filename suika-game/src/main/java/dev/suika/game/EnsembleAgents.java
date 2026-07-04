package dev.suika.game;

import dev.suika.ai.ActionSpec;
import dev.suika.ai.AgentPlugin;
import dev.suika.ai.GreedyOnePlyAgent;
import dev.suika.ai.HeuristicAgent;
import dev.suika.ai.MctsAgent;
import dev.suika.ai.MlpPolicy;
import dev.suika.ai.ReturnConditionedAgent;
import dev.suika.core.GameCore;
import dev.suika.core.GameState;
import dev.suika.core.PhysicsConfig;
import dev.suika.core.StepResult;
import dev.suika.env.StateObservationEncoder;

import java.util.Map;
import java.util.Random;

/**
 * The five composed {@link AgentPlugin}s surfaced as "Ensemble" techniques — each
 * genuinely combines two or more of the building-block agents into a different
 * decision process, calling straight through to the real
 * {@code selectAction}/search/forward-pass of the pieces it's built from.
 *
 * <p>What each ensemble uses is declared centrally in
 * {@link AiTechnique#ensembleMembers()} (shown verbatim in the UI), and each is
 * customizable from {@link PlaygroundConfig}: the net-blend's donor save + blend
 * weight, the tiebreak's tie threshold, the bandit's UCB exploration constant, and
 * the adaptive committee's learning rate.
 *
 * <p>The two ensembles that LEARN as they play (adaptive committee, bandit)
 * implement {@link HasLearnedState} so their learned trust/pull statistics persist
 * through the SAVES slots alongside every other technique's progress.
 *
 * <p>When called with a live {@link GameCore} (the normal path from
 * {@link AgentRunner#startThink}), every ensemble here uses the EXACT
 * search/simulation path (real forks, real settle), never the {@code GameState}-only
 * approximation — the state-only overload exists only as the interface's required
 * fallback.
 */
final class EnsembleAgents {

    private EnsembleAgents() {}

    /**
     * Bounded, shared, daemon thread pool for parallelizing genuinely independent
     * inner-agent calls within a single ensemble decision. Only used where BOTH
     * conditions hold: (a) the calls don't feed into each other and (b) none mutates
     * the shared {@link GameCore} — every inner agent forks its own snapshot before
     * simulating, so calling several concurrently against the same live core is safe.
     *
     * <p>Sized well below the core count (capped at 4, floor 2) so it composes with
     * MCTS's OWN root-parallel search threads and the control center's multi-board
     * tiling instead of every layer of parallelism fighting for cores.
     */
    private static final java.util.concurrent.ExecutorService ENSEMBLE_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(
                    Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)),
                    r -> { Thread t = new Thread(r, "ensemble-inner"); t.setDaemon(true); return t; });

    private static <T> T await(java.util.concurrent.Future<T> f) {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    /** Implemented by every ensemble whose decision runs through a real inner
     *  {@link MctsAgent} — lets the control center's search-tree diagram draw the same
     *  visualization for these as for plain MCTS/AlphaZero. */
    interface HasMctsCore { MctsAgent mctsCore(); }

    /** Ensembles that accumulate learned statistics while playing (trust weights,
     *  bandit pulls). Export/import round-trips through {@link ModelSlots}'s
     *  config-save param map so the SAVES slots persist real learning progress,
     *  not just hyperparameters. */
    interface HasLearnedState {
        Map<String, Double> exportLearnedState();
        void importLearnedState(Map<String, Double> state);
        /** Short live diagnostics lines for the control-center panel. */
        String[] learnedStateLines();
    }

    /** Loads a specific donor slot ({@code slot} 1..SLOT_COUNT), or the first present
     *  slot when {@code slot <= 0}, or a freshly random-initialised policy of the same
     *  architecture if none load. */
    private static MlpPolicy loadOrFreshPolicy(AiTechnique sourceTechnique, int slot, long fallbackSeed) {
        MlpPolicy p = ModelSlots.newCompatiblePolicy();
        if (slot >= 1 && slot <= ModelSlots.SLOT_COUNT) {
            if (ModelSlots.load(sourceTechnique.id, slot, p)) return p;
        } else {
            for (int s = 1; s <= ModelSlots.SLOT_COUNT; s++) {
                if (ModelSlots.load(sourceTechnique.id, s, p)) return p;
            }
        }
        p.initRandom(new Random(fallbackSeed));
        return p;
    }

    private static MlpPolicy loadOrFreshPolicy(AiTechnique sourceTechnique, long fallbackSeed) {
        return loadOrFreshPolicy(sourceTechnique, 0, fallbackSeed);
    }

    /** True when a donor technique has loadable trained WEIGHTS in the given slot (or ANY
     *  slot when {@code slot <= 0}). Weight-aware (not just "a save exists"): a config-only
     *  save — e.g. an untrained PPO/MuZero slot — correctly reads as untrained, so the UI
     *  never claims a random net is a trained donor. */
    static boolean donorTrained(AiTechnique donor, int slot) {
        if (slot >= 1 && slot <= ModelSlots.SLOT_COUNT) return ModelSlots.hasWeights(donor.id, slot);
        for (int s = 1; s <= ModelSlots.SLOT_COUNT; s++) {
            if (ModelSlots.hasWeights(donor.id, s)) return true;
        }
        return false;
    }

    static boolean donorTrained(AiTechnique donor) { return donorTrained(donor, 0); }

    private static double normalize(double[] v, int i) {
        double max = Double.NEGATIVE_INFINITY, min = Double.POSITIVE_INFINITY;
        for (double x : v) { max = Math.max(max, x); min = Math.min(min, x); }
        double range = max - min;
        return range > 1e-9 ? (v[i] - min) / range : 0.5;
    }

    private static double quickEval(GameCore core, int actionBins, int bin) {
        GameCore fork = core.snapshot();
        double x = PhysicsConfig.DROP_X_MIN
                + bin / (double) (actionBins - 1) * (PhysicsConfig.DROP_X_MAX - PhysicsConfig.DROP_X_MIN);
        StepResult r = fork.dropAndSettle(x);
        double v = r.observation().score() - core.getScore();
        if (r.terminated()) v -= 10.0;
        return v;
    }

    // -------------------------------------------------------------------------
    // 1. MCTS + Policy Net — search narrows, a donor net's logits weigh in on the
    //    final pick among visited columns. Donor save (Neuroevo / CMA-ES / DAgger)
    //    and blend weight are user-selectable.
    // -------------------------------------------------------------------------
    static final class NetGuidedMcts implements AgentPlugin, HasMctsCore {
        private final MctsAgent mcts;
        private final MlpPolicy net;
        private final double netWeight;
        final AiTechnique donor;
        final int donorSlot;
        final boolean donorTrained;
        private final StateObservationEncoder encoder = new StateObservationEncoder();
        private volatile int[] lastVisits = new int[0];

        NetGuidedMcts(int rollouts, int actionBins, AiTechnique donor, int donorSlot, double netWeight) {
            this.mcts = new MctsAgent(rollouts, Math.sqrt(2), 6, actionBins);
            this.donor = donor;
            this.donorSlot = donorSlot;
            this.donorTrained = donorTrained(donor, donorSlot);
            this.net = loadOrFreshPolicy(donor, donorSlot, 101L);
            this.netWeight = netWeight;
        }
        @Override public String id()          { return "ens-mcts-net"; }
        @Override public String displayName() {
            return "MCTS + Policy Net (" + donor.display + (donorSlot >= 1 ? " slot " + donorSlot : "") + ")";
        }
        @Override public MctsAgent mctsCore()  { return mcts; }

        @Override public Object selectAction(GameState state, ActionSpec spec) {
            return blend(mcts.selectAction(state, spec), state, spec);
        }
        @Override public Object selectAction(GameCore core, ActionSpec spec) {
            Object mctsAction = mcts.selectAction(core, spec);
            lastVisits = mcts.lastVisits();
            return blend(mctsAction, core.getState(), spec);
        }
        private Object blend(Object mctsAction, GameState state, ActionSpec spec) {
            int[] visits = lastVisits;
            if (!spec.discrete() || visits.length == 0) return mctsAction;
            int maxVisit = 0; for (int v : visits) maxVisit = Math.max(maxVisit, v);
            if (maxVisit == 0) return mctsAction;
            double[] logits = net.forward(encoder.encode(state));
            int best = ((Number) mctsAction).intValue();
            double bestScore = Double.NEGATIVE_INFINITY;
            for (int b = 0; b < spec.bins() && b < visits.length; b++) {
                if (visits[b] == 0) continue;
                double score = (visits[b] / (double) maxVisit) * (1.0 - netWeight)
                        + normalize(logits, b) * netWeight;
                if (score > bestScore) { bestScore = score; best = b; }
            }
            return best;
        }
    }

    // -------------------------------------------------------------------------
    // 2. MCTS narrows to the columns it was genuinely torn between (within the
    //    configured share of the top visit count); GreedyOnePly's EXACT one-ply
    //    evaluation breaks the tie instead of MCTS's noisier rollout estimate.
    // -------------------------------------------------------------------------
    static final class McTsGreedyTiebreak implements AgentPlugin, HasMctsCore {
        private final MctsAgent mcts;
        private final int actionBins;
        private final double tieThreshold;

        McTsGreedyTiebreak(int rollouts, int actionBins, double tieThreshold) {
            this.mcts = new MctsAgent(rollouts, Math.sqrt(2), 6, actionBins);
            this.actionBins = actionBins;
            this.tieThreshold = tieThreshold;
        }
        @Override public String id()          { return "ens-mcts-greedy-tiebreak"; }
        @Override public String displayName() { return "MCTS + Greedy Tiebreak"; }
        @Override public MctsAgent mctsCore()  { return mcts; }

        @Override public Object selectAction(GameState state, ActionSpec spec) {
            return mcts.selectAction(state, spec);
        }
        @Override public Object selectAction(GameCore core, ActionSpec spec) {
            Object mctsAction = mcts.selectAction(core, spec);
            int[] visits = mcts.lastVisits();
            if (visits.length == 0) return mctsAction;
            int maxVisit = 0; for (int v : visits) maxVisit = Math.max(maxVisit, v);
            if (maxVisit == 0) return mctsAction;
            java.util.List<Integer> tied = new java.util.ArrayList<>();
            for (int b = 0; b < visits.length; b++) if (visits[b] >= maxVisit * tieThreshold) tied.add(b);
            if (tied.size() <= 1) return mctsAction;
            // The tied candidates' exact evaluations are independent full settles —
            // run them concurrently on the shared pool (each forks its own snapshot).
            java.util.List<java.util.concurrent.Future<double[]>> evals = new java.util.ArrayList<>(tied.size());
            for (int b : tied) {
                final int bin = b;
                evals.add(ENSEMBLE_POOL.submit(() -> new double[]{bin, quickEval(core, actionBins, bin)}));
            }
            int best = tied.get(0);
            double bestValue = Double.NEGATIVE_INFINITY;
            for (var f : evals) {
                double[] r = await(f);
                if (r[1] > bestValue) { bestValue = r[1]; best = (int) r[0]; }
            }
            return best;
        }
    }

    // -------------------------------------------------------------------------
    // 3. A Decision-Transformer-style return-conditioned agent proposes an
    //    (ambitious) column; a shallow MCTS sanity-checks it against its own top
    //    pick via an exact one-ply evaluation of both, keeping whichever is better.
    // -------------------------------------------------------------------------
    static final class ReturnConditionedVerified implements AgentPlugin {
        private final ReturnConditionedAgent rtg;
        private final int actionBins;
        private static final int VERIFY_ROLLOUTS = 24;

        ReturnConditionedVerified(double targetReturn, int actionBins) {
            this.rtg = new ReturnConditionedAgent(targetReturn, 505L);
            this.actionBins = actionBins;
        }
        @Override public String id()          { return "ens-rtg-verified"; }
        @Override public String displayName() { return "Return-Conditioned + MCTS Verify"; }

        @Override public Object selectAction(GameState state, ActionSpec spec) { return rtg.selectAction(state, spec); }

        @Override public Object selectAction(GameCore core, ActionSpec spec) {
            // The return-conditioned proposal and the verifier's shallow MCTS search
            // don't depend on each other's result — run both concurrently.
            var pF = ENSEMBLE_POOL.submit(() -> ((Number) rtg.selectAction(core.getState(), spec)).intValue());
            var mF = ENSEMBLE_POOL.submit(() -> {
                MctsAgent verifier = new MctsAgent(VERIFY_ROLLOUTS, Math.sqrt(2), 4, actionBins);
                return ((Number) verifier.selectAction(core, spec)).intValue();
            });
            int pBin = await(pF), mBin = await(mF);
            if (pBin == mBin) return pBin;
            var pvF = ENSEMBLE_POOL.submit(() -> quickEval(core, actionBins, pBin));
            double mValue = quickEval(core, actionBins, mBin);
            return await(pvF) >= mValue ? pBin : mBin;
        }
    }

    // -------------------------------------------------------------------------
    // 4. Adaptive voting committee: MCTS + Greedy + Heuristic vote, and each
    //    member's trust weight is a genuine multiplicative-weights update driven by
    //    the score actually gained since its last winning pick — the committee
    //    LEARNS which member to trust over the course of a session.
    // -------------------------------------------------------------------------
    static final class AdaptiveVotingCommittee implements AgentPlugin, HasLearnedState {
        private final MctsAgent mcts;
        private final GreedyOnePlyAgent greedy;
        private final HeuristicAgent heuristic = new HeuristicAgent();
        private final double[] weight = {1.0, 1.0, 1.0}; // MCTS, Greedy, Heuristic
        private int lastWinner = -1;
        private long scoreAtLastPick = 0;
        private final double learningRate;

        AdaptiveVotingCommittee(int rollouts, int actionBins, double learningRate) {
            this.mcts = new MctsAgent(rollouts, Math.sqrt(2), 5, actionBins);
            this.greedy = new GreedyOnePlyAgent(actionBins);
            this.learningRate = learningRate;
        }
        @Override public String id()          { return "ens-adaptive-vote"; }
        @Override public String displayName() { return "Adaptive Voting Committee"; }

        @Override public Object selectAction(GameState state, ActionSpec spec) {
            return decide(state.score(), ((Number) mcts.selectAction(state, spec)).intValue(),
                    ((Number) greedy.selectAction(state, spec)).intValue(),
                    ((Number) heuristic.selectAction(state, spec)).intValue());
        }
        @Override public Object selectAction(GameCore core, ActionSpec spec) {
            // All three votes are independent and none mutates the shared core.
            var mctsF = ENSEMBLE_POOL.submit(() -> mcts.selectAction(core, spec));
            var greedyF = ENSEMBLE_POOL.submit(() -> greedy.selectAction(core, spec));
            int heuristicBin = ((Number) heuristic.selectAction(core.getState(), spec)).intValue();
            return decide(core.getScore(), ((Number) await(mctsF)).intValue(),
                    ((Number) await(greedyF)).intValue(), heuristicBin);
        }
        private synchronized Object decide(long currentScore, int mctsBin, int greedyBin, int heuristicBin) {
            if (lastWinner >= 0) {
                double reward = currentScore - scoreAtLastPick;
                weight[lastWinner] *= Math.exp(learningRate * Math.signum(reward) * Math.min(1.0, Math.abs(reward) / 10.0));
                double sum = weight[0] + weight[1] + weight[2];
                for (int i = 0; i < 3; i++) weight[i] = Math.max(0.05, weight[i] / sum * 3.0);
            }
            int[] bins = {mctsBin, greedyBin, heuristicBin};
            Map<Integer, Double> votes = new java.util.HashMap<>();
            for (int i = 0; i < 3; i++) votes.merge(bins[i], weight[i], Double::sum);
            int winnerBin = mctsBin; double best = -1;
            for (var e : votes.entrySet()) if (e.getValue() > best) { best = e.getValue(); winnerBin = e.getKey(); }
            int winnerIdx = 0;
            for (int i = 0; i < 3; i++) if (bins[i] == winnerBin) { winnerIdx = i; break; }
            lastWinner = winnerIdx;
            scoreAtLastPick = currentScore;
            return winnerBin;
        }
        /** Current trust weights [MCTS, Greedy, Heuristic] — for a diagnostics readout. */
        synchronized double[] trustWeights() { return weight.clone(); }

        @Override public synchronized Map<String, Double> exportLearnedState() {
            Map<String, Double> m = new java.util.LinkedHashMap<>();
            m.put("ens.weight.mcts", weight[0]);
            m.put("ens.weight.greedy", weight[1]);
            m.put("ens.weight.heuristic", weight[2]);
            return m;
        }
        @Override public synchronized void importLearnedState(Map<String, Double> state) {
            weight[0] = state.getOrDefault("ens.weight.mcts", weight[0]);
            weight[1] = state.getOrDefault("ens.weight.greedy", weight[1]);
            weight[2] = state.getOrDefault("ens.weight.heuristic", weight[2]);
            lastWinner = -1;
        }
        @Override public String[] learnedStateLines() {
            double[] w = trustWeights();
            return new String[]{ String.format("trust        MCTS %.2f · Greedy %.2f · Heur %.2f", w[0], w[1], w[2]) };
        }
    }

    // -------------------------------------------------------------------------
    // 5. UCB1 bandit meta-controller: picks WHICH agent (MCTS/Greedy/Heuristic)
    //    makes each move, learning from the score actually gained after each pick
    //    which one performs best given how the board looks lately.
    // -------------------------------------------------------------------------
    static final class BanditMetaController implements AgentPlugin, HasLearnedState {
        private static final int ARMS = 3; // 0=MCTS, 1=Greedy, 2=Heuristic
        private final double c;   // UCB1 exploration constant

        private final MctsAgent mcts;
        private final GreedyOnePlyAgent greedy;
        private final HeuristicAgent heuristic = new HeuristicAgent();
        private final long[] pulls = new long[ARMS];
        private final double[] rewardSum = new double[ARMS];
        private int lastArm = -1;
        private long scoreAtLastPick = 0;

        BanditMetaController(int rollouts, int actionBins, double ucbC) {
            this.mcts = new MctsAgent(rollouts, Math.sqrt(2), 5, actionBins);
            this.greedy = new GreedyOnePlyAgent(actionBins);
            this.c = ucbC;
        }
        @Override public String id()          { return "ens-bandit-meta"; }
        @Override public String displayName() { return "Bandit Meta-Controller"; }

        @Override public Object selectAction(GameState state, ActionSpec spec) {
            int arm = pick(state.score());
            return switch (arm) {
                case 0 -> mcts.selectAction(state, spec);
                case 1 -> greedy.selectAction(state, spec);
                default -> heuristic.selectAction(state, spec);
            };
        }
        @Override public Object selectAction(GameCore core, ActionSpec spec) {
            int arm = pick(core.getScore());
            return switch (arm) {
                case 0 -> mcts.selectAction(core, spec);
                case 1 -> greedy.selectAction(core, spec);
                default -> heuristic.selectAction(core.getState(), spec);
            };
        }
        private synchronized int pick(long currentScore) {
            if (lastArm >= 0) rewardSum[lastArm] += (currentScore - scoreAtLastPick);
            long totalPulls = 0; for (long p : pulls) totalPulls += p;
            int chosen = 0;
            double bestUcb = Double.NEGATIVE_INFINITY;
            for (int a = 0; a < ARMS; a++) {
                if (pulls[a] == 0) { chosen = a; break; }
                double mean = rewardSum[a] / pulls[a];
                double ucb = mean + c * Math.sqrt(Math.log(Math.max(2, totalPulls)) / pulls[a]);
                if (ucb > bestUcb) { bestUcb = ucb; chosen = a; }
            }
            pulls[chosen]++;
            lastArm = chosen;
            scoreAtLastPick = currentScore;
            return chosen;
        }
        /** Pull counts [MCTS, Greedy, Heuristic] — how the bandit has actually been
         *  splitting its trust, for a diagnostics readout. */
        synchronized long[] pullCounts() { return pulls.clone(); }

        @Override public synchronized Map<String, Double> exportLearnedState() {
            Map<String, Double> m = new java.util.LinkedHashMap<>();
            String[] names = {"mcts", "greedy", "heuristic"};
            for (int a = 0; a < ARMS; a++) {
                m.put("ens.pulls." + names[a], (double) pulls[a]);
                m.put("ens.reward." + names[a], rewardSum[a]);
            }
            return m;
        }
        @Override public synchronized void importLearnedState(Map<String, Double> state) {
            String[] names = {"mcts", "greedy", "heuristic"};
            for (int a = 0; a < ARMS; a++) {
                pulls[a] = state.getOrDefault("ens.pulls." + names[a], (double) pulls[a]).longValue();
                rewardSum[a] = state.getOrDefault("ens.reward." + names[a], rewardSum[a]);
            }
            lastArm = -1;
        }
        @Override public String[] learnedStateLines() {
            long[] p = pullCounts();
            long total = Math.max(1, p[0] + p[1] + p[2]);
            return new String[]{
                String.format("arm picks    MCTS %d%% · Greedy %d%% · Heur %d%%",
                        100 * p[0] / total, 100 * p[1] / total, 100 * p[2] / total)
            };
        }
    }
}
