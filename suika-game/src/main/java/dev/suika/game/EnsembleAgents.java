package dev.suika.game;

import dev.suika.ai.ActionSpec;
import dev.suika.ai.AgentPlugin;
import dev.suika.ai.GenerativeModelBridge;
import dev.suika.ai.GreedyOnePlyAgent;
import dev.suika.ai.HeuristicAgent;
import dev.suika.ai.MctsAgent;
import dev.suika.ai.MlpPolicy;
import dev.suika.ai.NeuralAgent;
import dev.suika.ai.ReturnConditionedAgent;
import dev.suika.core.GameCore;
import dev.suika.core.GameState;
import dev.suika.core.PhysicsConfig;
import dev.suika.core.StepResult;
import dev.suika.env.StateObservationEncoder;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Random;

/**
 * Ten composed {@link AgentPlugin}s, each genuinely combining two or more of the
 * existing building-block agents into a different decision process — every one calls
 * straight through to the real {@code selectAction}/search/forward-pass of the pieces
 * it's built from, not a cosmetic label wrapping a single agent.
 *
 * <p>Several source a "trained" {@link MlpPolicy} from whatever the matching
 * technique's save slots hold (see {@link ModelSlots}); with nothing saved yet they
 * fall back to a freshly random-initialised net (still real, just untrained) so the
 * ensemble is always usable, not just after someone trains a donor technique first.
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
     * §10: bounded, shared, daemon thread pool for parallelizing genuinely
     * independent inner-agent calls within a single ensemble decision. Only used
     * where a research pass confirmed BOTH conditions hold: (a) the calls don't
     * feed into each other (no result of one is an input to another — e.g.
     * {@link McTsGreedyTiebreak} does NOT qualify, its tiebreak set depends on
     * MCTS's own visit counts) and (b) none of them mutate the shared
     * {@link GameCore} — every inner agent forks its own snapshot before
     * simulating (see {@code GreedyOnePlyAgent#evaluateColumns}), so calling
     * several concurrently against the same live core is safe.
     *
     * <p>Sized well below the core count (capped at 4, floor 2) so it composes
     * with MCTS's OWN root-parallel search threads and the control center's
     * multi-board tiling instead of every layer of parallelism fighting the others
     * for cores — this is about overlapping otherwise-serial WAIT time (each
     * inner call blocks on real search/simulation work), not about maximizing
     * raw thread count. None of these techniques are GPU-capable (all
     * {@code Family.PLANNING}, pure JVM search/heuristics — see
     * {@link AiTechnique#gpuCapableTraining()}), so there's no GPU work to route
     * here; that lever only applies to the separate Python-family training path.
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
     *  {@link MctsAgent} — lets the control center's search-tree diagram (see
     *  {@link ControlCenterScreen}) draw the same visualization for these as for
     *  plain MCTS/AlphaZero, without needing to know which specific ensemble it is. */
    interface HasMctsCore { MctsAgent mctsCore(); }

    /** Loads the first present slot for {@code sourceTechnique}, or a freshly
     *  random-initialised policy of the same architecture if none exist yet. */
    private static MlpPolicy loadOrFreshPolicy(AiTechnique sourceTechnique, long fallbackSeed) {
        MlpPolicy p = ModelSlots.newCompatiblePolicy();
        for (int slot = 1; slot <= ModelSlots.SLOT_COUNT; slot++) {
            if (ModelSlots.load(sourceTechnique.id, slot, p)) return p;
        }
        p.initRandom(new Random(fallbackSeed));
        return p;
    }

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
    // 1. MCTS + Policy Net — search narrows, an untrained-by-default (or BC-slot-
    //    sourced) net's logits nudge the final pick among visited columns.
    // -------------------------------------------------------------------------
    static final class NetGuidedMcts implements AgentPlugin, HasMctsCore {
        private final MctsAgent mcts;
        private final MlpPolicy net;
        private final StateObservationEncoder encoder = new StateObservationEncoder();
        private volatile int[] lastVisits = new int[0];

        NetGuidedMcts(int rollouts, int actionBins) {
            this.mcts = new MctsAgent(rollouts, Math.sqrt(2), 6, actionBins);
            this.net = loadOrFreshPolicy(AiTechnique.BC, 101L);
        }
        @Override public String id()          { return "ens-mcts-net"; }
        @Override public String displayName() { return "MCTS + Policy Net"; }
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
                double score = (visits[b] / (double) maxVisit) * 0.7 + normalize(logits, b) * 0.3;
                if (score > bestScore) { bestScore = score; best = b; }
            }
            return best;
        }
    }

    // -------------------------------------------------------------------------
    // 2. Policy net normally; GreedyOnePly overrides it when the current fruit has
    //    an immediate same-tier merge available anywhere on the board.
    // -------------------------------------------------------------------------
    static final class GreedyGuardedPolicy implements AgentPlugin {
        private final NeuralAgent net;
        private final GreedyOnePlyAgent greedy;

        GreedyGuardedPolicy(int actionBins) {
            this.net = new NeuralAgent(loadOrFreshPolicy(AiTechnique.NEUROEVO, 202L));
            this.greedy = new GreedyOnePlyAgent(actionBins);
        }
        @Override public String id()          { return "ens-greedy-guard"; }
        @Override public String displayName() { return "Policy Net + Greedy Guard"; }

        @Override public Object selectAction(GameState state, ActionSpec spec) {
            return immediateMergeAvailable(state) ? greedy.selectAction(state, spec) : net.selectAction(state, spec);
        }
        @Override public Object selectAction(GameCore core, ActionSpec spec) {
            return immediateMergeAvailable(core.getState())
                    ? greedy.selectAction(core, spec) : net.selectAction(core, spec);
        }
        private boolean immediateMergeAvailable(GameState state) {
            var cur = state.currentFruitTier();
            for (var f : state.fruits()) if (f.tier() == cur) return true;
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // 3. MCTS narrows to the columns it was genuinely torn between (within 85% of
    //    the top visit count); GreedyOnePly's EXACT one-ply evaluation breaks the
    //    tie instead of MCTS's noisier rollout-based estimate.
    // -------------------------------------------------------------------------
    static final class McTsGreedyTiebreak implements AgentPlugin, HasMctsCore {
        private final MctsAgent mcts;
        private final int actionBins;

        McTsGreedyTiebreak(int rollouts, int actionBins) {
            this.mcts = new MctsAgent(rollouts, Math.sqrt(2), 6, actionBins);
            this.actionBins = actionBins;
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
            for (int b = 0; b < visits.length; b++) if (visits[b] >= maxVisit * 0.85) tied.add(b);
            if (tied.size() <= 1) return mctsAction;
            int best = tied.get(0);
            double bestValue = Double.NEGATIVE_INFINITY;
            for (int b : tied) {
                double v = quickEval(core, actionBins, b);
                if (v > bestValue) { bestValue = v; best = b; }
            }
            return best;
        }
    }

    // -------------------------------------------------------------------------
    // 4. Voting committee: MCTS + Greedy + Heuristic each propose a column; majority
    //    wins, MCTS breaks a full three-way disagreement.
    // -------------------------------------------------------------------------
    static final class VotingCommittee implements AgentPlugin {
        private final MctsAgent mcts;
        private final GreedyOnePlyAgent greedy;
        private final HeuristicAgent heuristic = new HeuristicAgent();

        VotingCommittee(int rollouts, int actionBins) {
            this.mcts = new MctsAgent(rollouts, Math.sqrt(2), 5, actionBins);
            this.greedy = new GreedyOnePlyAgent(actionBins);
        }
        @Override public String id()          { return "ens-voting-committee"; }
        @Override public String displayName() { return "Voting Committee"; }

        @Override public Object selectAction(GameState state, ActionSpec spec) {
            return vote(mcts.selectAction(state, spec), greedy.selectAction(state, spec), heuristic.selectAction(state, spec));
        }
        @Override public Object selectAction(GameCore core, ActionSpec spec) {
            // §10: all three votes are independent (none reads another's result) and
            // none mutates the shared core (each forks its own snapshot) — see
            // ENSEMBLE_POOL's doc. MCTS is the expensive one; running Greedy's real
            // one-ply simulation alongside it (instead of after it) is the actual win.
            var mctsF = ENSEMBLE_POOL.submit(() -> mcts.selectAction(core, spec));
            var greedyF = ENSEMBLE_POOL.submit(() -> greedy.selectAction(core, spec));
            Object heuristicResult = heuristic.selectAction(core.getState(), spec); // cheap — run inline
            return vote(await(mctsF), await(greedyF), heuristicResult);
        }
        private Object vote(Object a, Object b, Object c) {
            int ai = ((Number) a).intValue(), bi = ((Number) b).intValue(), ci = ((Number) c).intValue();
            if (ai == bi || ai == ci) return a; // MCTS agrees with at least one other
            if (bi == ci) return b;             // Greedy + Heuristic agree, MCTS is the outlier
            return a;                            // three-way split — defer to the strongest single agent
        }
    }

    // -------------------------------------------------------------------------
    // 5. MCTS + a CMA-ES-evolved value net (heavier net weight than #1, since this
    //    is a fitness-selected policy when a slot exists, not a default net).
    // -------------------------------------------------------------------------
    static final class EvolvedNetMcts implements AgentPlugin, HasMctsCore {
        private final MctsAgent mcts;
        private final MlpPolicy evolvedNet;
        private final StateObservationEncoder encoder = new StateObservationEncoder();
        private volatile int[] lastVisits = new int[0];

        EvolvedNetMcts(int rollouts, int actionBins) {
            this.mcts = new MctsAgent(rollouts, Math.sqrt(2), 6, actionBins);
            this.evolvedNet = loadOrFreshPolicy(AiTechnique.CMA_ES, 303L);
        }
        @Override public String id()          { return "ens-evolved-mcts"; }
        @Override public String displayName() { return "MCTS + Evolved Value Net"; }
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
            double[] logits = evolvedNet.forward(encoder.encode(state));
            int best = ((Number) mctsAction).intValue();
            double bestScore = Double.NEGATIVE_INFINITY;
            for (int b = 0; b < spec.bins() && b < visits.length; b++) {
                if (visits[b] == 0) continue;
                double score = (visits[b] / (double) maxVisit) * 0.4 + normalize(logits, b) * 0.6;
                if (score > bestScore) { bestScore = score; best = b; }
            }
            return best;
        }
    }

    // -------------------------------------------------------------------------
    // 6. MCTS defers to a DAgger-slot-sourced imitation policy whenever the search
    //    didn't strongly reject that column (still holds >=50% of the top visit
    //    share) — "don't override trained human/expert style unless search really
    //    disagrees", a different mechanism from the additive blends above.
    // -------------------------------------------------------------------------
    static final class ImitationBlendedMcts implements AgentPlugin, HasMctsCore {
        private final MctsAgent mcts;
        private final NeuralAgent imitation;

        ImitationBlendedMcts(int rollouts, int actionBins) {
            this.mcts = new MctsAgent(rollouts, Math.sqrt(2), 6, actionBins);
            this.imitation = new NeuralAgent(loadOrFreshPolicy(AiTechnique.DAGGER, 404L));
        }
        @Override public String id()          { return "ens-imitation-mcts"; }
        @Override public String displayName() { return "MCTS + Imitation Blend"; }
        @Override public MctsAgent mctsCore()  { return mcts; }

        @Override public Object selectAction(GameState state, ActionSpec spec) {
            return defer(mcts.selectAction(state, spec), imitation.selectAction(state, spec), new int[0]);
        }
        @Override public Object selectAction(GameCore core, ActionSpec spec) {
            Object mctsAction = mcts.selectAction(core, spec);
            return defer(mctsAction, imitation.selectAction(core.getState(), spec), mcts.lastVisits());
        }
        private Object defer(Object mctsAction, Object imitationAction, int[] visits) {
            if (visits.length == 0) return mctsAction;
            int imBin = ((Number) imitationAction).intValue();
            int maxVisit = 0; for (int v : visits) maxVisit = Math.max(maxVisit, v);
            if (maxVisit > 0 && imBin >= 0 && imBin < visits.length && visits[imBin] >= maxVisit * 0.5) return imBin;
            return mctsAction;
        }
    }

    // -------------------------------------------------------------------------
    // 7. A Decision-Transformer-style return-conditioned agent proposes an
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
            // §10: the return-conditioned proposal and the verifier's shallow MCTS
            // search don't depend on each other's result — only the two quickEval
            // calls below (which pick between them) need both bins already known.
            var pF = ENSEMBLE_POOL.submit(() -> ((Number) rtg.selectAction(core.getState(), spec)).intValue());
            var mF = ENSEMBLE_POOL.submit(() -> {
                MctsAgent verifier = new MctsAgent(VERIFY_ROLLOUTS, Math.sqrt(2), 4, actionBins);
                return ((Number) verifier.selectAction(core, spec)).intValue();
            });
            int pBin = await(pF), mBin = await(mF);
            if (pBin == mBin) return pBin;
            double pValue = quickEval(core, actionBins, pBin);
            double mValue = quickEval(core, actionBins, mBin);
            return pValue >= mValue ? pBin : mBin;
        }
    }

    // -------------------------------------------------------------------------
    // 8. A generative (diffusion-style) sampler proposes several candidate columns;
    //    an exact one-ply evaluation filters them down to the single best, instead
    //    of just taking the sampler's raw pick.
    // -------------------------------------------------------------------------
    static final class GenerativeGreedyFilter implements AgentPlugin {
        private final GenerativeModelBridge bridge;
        private final int actionBins;
        private static final int CANDIDATES = 6;

        GenerativeGreedyFilter(int actionBins) {
            this.bridge = new GenerativeModelBridge(GenerativeModelBridge.ModelType.DIFFUSION_POLICY, 606L);
            this.actionBins = actionBins;
        }
        @Override public String id()          { return "ens-generative-greedy"; }
        @Override public String displayName() { return "Generative Proposals + Greedy Filter"; }

        @Override public Object selectAction(GameState state, ActionSpec spec) {
            return sampleCandidates(state).iterator().next();
        }
        @Override public Object selectAction(GameCore core, ActionSpec spec) {
            LinkedHashSet<Integer> candidates = sampleCandidates(core.getState());
            int best = candidates.iterator().next();
            double bestValue = Double.NEGATIVE_INFINITY;
            for (int bin : candidates) {
                double v = quickEval(core, actionBins, bin);
                if (v > bestValue) { bestValue = v; best = bin; }
            }
            return best;
        }
        private LinkedHashSet<Integer> sampleCandidates(GameState state) {
            LinkedHashSet<Integer> candidates = new LinkedHashSet<>();
            for (int i = 0; i < CANDIDATES * 3 && candidates.size() < CANDIDATES; i++) {
                candidates.add(bridge.sampleAction(state, actionBins));
            }
            return candidates;
        }
    }

    // -------------------------------------------------------------------------
    // 9. Adaptive voting committee: like #4, but each member's trust weight is a
    //    genuine multiplicative-weights update driven by the score actually gained
    //    since its last winning pick — the committee LEARNS which member to trust
    //    over the course of a session instead of voting with fixed weights.
    // -------------------------------------------------------------------------
    static final class AdaptiveVotingCommittee implements AgentPlugin {
        private final MctsAgent mcts;
        private final GreedyOnePlyAgent greedy;
        private final HeuristicAgent heuristic = new HeuristicAgent();
        private final double[] weight = {1.0, 1.0, 1.0}; // MCTS, Greedy, Heuristic
        private int lastWinner = -1;
        private long scoreAtLastPick = 0;
        private static final double LEARNING_RATE = 0.08;

        AdaptiveVotingCommittee(int rollouts, int actionBins) {
            this.mcts = new MctsAgent(rollouts, Math.sqrt(2), 5, actionBins);
            this.greedy = new GreedyOnePlyAgent(actionBins);
        }
        @Override public String id()          { return "ens-adaptive-vote"; }
        @Override public String displayName() { return "Adaptive Voting Committee"; }

        @Override public Object selectAction(GameState state, ActionSpec spec) {
            return decide(state.score(), ((Number) mcts.selectAction(state, spec)).intValue(),
                    ((Number) greedy.selectAction(state, spec)).intValue(),
                    ((Number) heuristic.selectAction(state, spec)).intValue());
        }
        @Override public Object selectAction(GameCore core, ActionSpec spec) {
            // §10: same independence/no-mutation guarantee as VotingCommittee above.
            var mctsF = ENSEMBLE_POOL.submit(() -> mcts.selectAction(core, spec));
            var greedyF = ENSEMBLE_POOL.submit(() -> greedy.selectAction(core, spec));
            int heuristicBin = ((Number) heuristic.selectAction(core.getState(), spec)).intValue();
            return decide(core.getScore(), ((Number) await(mctsF)).intValue(),
                    ((Number) await(greedyF)).intValue(), heuristicBin);
        }
        private Object decide(long currentScore, int mctsBin, int greedyBin, int heuristicBin) {
            if (lastWinner >= 0) {
                double reward = currentScore - scoreAtLastPick;
                weight[lastWinner] *= Math.exp(LEARNING_RATE * Math.signum(reward) * Math.min(1.0, Math.abs(reward) / 10.0));
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
        double[] trustWeights() { return weight.clone(); }
    }

    // -------------------------------------------------------------------------
    // 10. UCB1 bandit meta-controller: picks WHICH agent (MCTS/Greedy/Heuristic)
    //     makes each move, learning from the score actually gained after each pick
    //     which one performs best given how the board looks lately. Directly
    //     "learns a tendency" rather than just having one baked in.
    // -------------------------------------------------------------------------
    static final class BanditMetaController implements AgentPlugin {
        private static final int ARMS = 3; // 0=MCTS, 1=Greedy, 2=Heuristic
        private static final double C = 1.4; // UCB1 exploration constant

        private final MctsAgent mcts;
        private final GreedyOnePlyAgent greedy;
        private final HeuristicAgent heuristic = new HeuristicAgent();
        private final long[] pulls = new long[ARMS];
        private final double[] rewardSum = new double[ARMS];
        private int lastArm = -1;
        private long scoreAtLastPick = 0;

        BanditMetaController(int rollouts, int actionBins) {
            this.mcts = new MctsAgent(rollouts, Math.sqrt(2), 5, actionBins);
            this.greedy = new GreedyOnePlyAgent(actionBins);
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
        private int pick(long currentScore) {
            if (lastArm >= 0) rewardSum[lastArm] += (currentScore - scoreAtLastPick);
            long totalPulls = 0; for (long p : pulls) totalPulls += p;
            int chosen = 0;
            double bestUcb = Double.NEGATIVE_INFINITY;
            for (int a = 0; a < ARMS; a++) {
                if (pulls[a] == 0) { chosen = a; break; }
                double mean = rewardSum[a] / pulls[a];
                double ucb = mean + C * Math.sqrt(Math.log(Math.max(2, totalPulls)) / pulls[a]);
                if (ucb > bestUcb) { bestUcb = ucb; chosen = a; }
            }
            pulls[chosen]++;
            lastArm = chosen;
            scoreAtLastPick = currentScore;
            return chosen;
        }
        /** Pull counts [MCTS, Greedy, Heuristic] — how the bandit has actually been
         *  splitting its trust, for a diagnostics readout. */
        long[] pullCounts() { return pulls.clone(); }
    }
}
