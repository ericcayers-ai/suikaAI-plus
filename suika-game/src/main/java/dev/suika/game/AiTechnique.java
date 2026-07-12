package dev.suika.game;

/**
 * The curated AI capability matrix surfaced in the AI Playground.
 *
 * <p>v0.12 cull: the old 21-entry list is trimmed to the 10 most important
 * techniques plus the 5 strongest, mechanically-distinct ensembles. Selection
 * rationale (per family, strongest representatives kept):
 * <ul>
 *   <li><b>Planning</b>: MCTS (canonical search), AlphaZero (search + learned
 *       guidance), Greedy One-Ply (exact one-step evaluation — strong AND the
 *       building block several ensembles verify with).</li>
 *   <li><b>Deep RL</b>: PPO — the field's default policy-gradient baseline and the
 *       one technique here with a real GPU training path. DQN/SAC dropped: same
 *       niche, weaker fit for this discrete drop-column game.</li>
 *   <li><b>Model-based</b>: MuZero (subsumes Dreamer for a game with cheap exact
 *       simulation; keeps the pixels-perception panel).</li>
 *   <li><b>Evolution</b>: Neuroevolution GA (transparent, teachable, now with
 *       selectable selection mathematics) and CMA-ES (the strongest practical
 *       gradient-free optimizer). PBT dropped — a meta-strategy, not a learner.</li>
 *   <li><b>Imitation</b>: DAgger (strictly dominates plain BC — it fixes BC's
 *       compounding-error problem).</li>
 *   <li><b>Offline</b>: Decision Transformer (return-conditioned sequence modeling;
 *       covers the offline niche with a livelier knob than CQL/IQL).</li>
 *   <li><b>Baseline</b>: Heuristic — the scripted floor every learner must beat,
 *       and a real committee member inside two ensembles.</li>
 * </ul>
 * Dropped: DQN, SAC, Dreamer, PBT, BC, GAIL, Offline RL, Diffusion, Flow,
 * Racing Self-Play, Random — plus the 5 weaker/duplicative ensembles (Voting
 * Committee superseded by Adaptive Voting; Evolved-Net MCTS folded into
 * MCTS + Policy Net's selectable donor; Greedy Guard, Imitation Blend and
 * Generative Filter retired).
 */
public enum AiTechnique {

    // id, display, category, dataMode, kind, family, jvmNative, python, parallel, speed, strength, blurb
    // "strength" (0-100) is an authored, hand-reasoned expected-performance score.
    // ---- Planning: strongest single-agent reasoners ----
    MCTS("mcts", "MCTS", "Planning", "either", "planning", Family.PLANNING,
            true, false, true, true, 95,
            "Monte-Carlo Tree Search over the perfect simulator. UCB1 selection + heuristic rollouts."),
    ALPHAZERO("alphazero", "AlphaZero", "Planning + Learning", "state", "both", Family.PLANNING,
            true, true, true, true, 97,
            "MCTS guided by a policy-value net. Search core JVM, net trains in Python."),
    GREEDY("greedy", "Greedy One-Ply", "Planning", "state", "planning", Family.PLANNING,
            true, false, true, true, 78,
            "Simulates every column for real, picks the best immediate score. Fast and solid."),

    // ---- Deep RL ----
    PPO("ppo", "PPO", "Deep RL", "either", "learning", Family.PYTHON,
            false, true, true, true, 80,
            "On-policy policy-gradient via Stable-Baselines3. Pairs with the vector env."),
    DQN("dqn", "DQN", "Deep RL", "state", "learning", Family.DEEP_RL,
            true, false, true, true, 74,
            "Value-based deep RL: Q-learning with experience replay + a target network, training live on the JVM."),

    // ---- Model-based ----
    MUZERO("muzero", "MuZero", "Model-Based", "pixels", "both", Family.PYTHON,
            false, true, true, true, 88,
            "Learn a latent dynamics model and plan inside it. Compare vs the true sim."),

    // ---- Evolution: gradient-free optimisation ----
    NEUROEVO("neuroevo", "Neuroevolution (GA)", "Evolution", "state", "learning", Family.EVOLUTION,
            true, false, true, true, 62,
            "Evolve MLP weights with selectable selection math (tournament / rank / Boltzmann)."),
    CMA_ES("cma-es", "CMA-ES", "Evolution", "state", "learning", Family.EVOLUTION,
            true, false, true, true, 65,
            "Covariance-adaptive evolution strategy. Strong on continuous weight spaces."),
    PBT("pbt", "Population-Based Training", "Evolution", "state", "meta", Family.EVOLUTION,
            true, false, true, true, 66,
            "A population trains in parallel, copying winners and perturbing their settings."),

    // ---- Imitation ----
    DAGGER("dagger", "DAgger", "Imitation", "state", "imitation", Family.IMITATION,
            true, false, false, true, 58,
            "Iterative imitation: relabel the states the agent actually visits."),
    BC("bc", "Behavioral Cloning", "Imitation", "state", "imitation", Family.IMITATION,
            true, false, false, true, 48,
            "Learn to copy YOUR drops from your recorded games. Train-on-my-playstyle."),

    // ---- Offline ----
    DECISION_TRANSFORMER("dt", "Decision Transformer", "Offline", "state", "offline", Family.PYTHON,
            true, true, false, true, 59,
            "Return-conditioned sequence modeling. 'Play to reach score X.'"),

    // ---- Baseline ----
    HEURISTIC("heuristic", "Heuristic", "Baseline", "state", "scripted", Family.PLANNING,
            true, false, false, true, 35,
            "Seek same-tier merges, else keep the surface flat. Scripted, no learning."),

    // ---- Ensembles: composed agents combining the techniques above.
    // Family.PLANNING so they run through PlanningRunner (live board, no separate
    // trainer loop needed) — each genuinely calls through to the real agents it
    // combines; see EnsembleAgents.java. Each declares its members via
    // ensembleMembers() so the UI is explicit about exactly what is inside. ----
    ENS_ADAPTIVE_VOTE("ens-adaptive-vote", "Adaptive Voting Committee", "Ensemble", "state", "ensemble", Family.PLANNING,
            true, false, true, true, 92,
            "MCTS, Greedy and Heuristic vote; each member's trust weight adapts to its recent results."),
    ENS_BANDIT("ens-bandit-meta", "Bandit Meta-Controller", "Ensemble", "state", "ensemble", Family.PLANNING,
            true, false, true, true, 90,
            "A UCB1 bandit learns, move by move, which single agent to trust right now."),
    ENS_MCTS_NET("ens-mcts-net", "MCTS + Policy Net", "Ensemble", "state", "ensemble", Family.PLANNING,
            true, false, true, true, 86,
            "MCTS search narrows the choice; a trained donor net's logits weigh in on the final pick."),
    ENS_MCTS_TIEBREAK("ens-mcts-greedy-tiebreak", "MCTS + Greedy Tiebreak", "Ensemble", "state", "ensemble", Family.PLANNING,
            true, false, true, true, 84,
            "MCTS's genuinely-tied top columns get an exact one-ply evaluation to break the tie."),
    ENS_RTG_VERIFIED("ens-rtg-verified", "Return-Conditioned + MCTS Verify", "Ensemble", "state", "ensemble", Family.PLANNING,
            true, false, true, true, 82,
            "A return-conditioned proposal gets sanity-checked against a shallow MCTS search.");

    /** Which specialised control-center drives this technique. */
    public enum Family { PLANNING, EVOLUTION, IMITATION, PYTHON, DEEP_RL }

    public final String id, display, category, dataMode, kind, blurb;
    public final Family family;
    /**
     * {@code parallel}: true only when the "Parallelism" control in the AI Playground
     * drawer has a real, wired effect for this technique — root-parallel search
     * (MCTS/AlphaZero/MuZero), column-parallel evaluation (Greedy), the evolution
     * eval pool (Neuroevo/CMA-ES), PPO's {@code --n-envs}/{@code --device} training
     * flags, or the ensembles' shared inner-agent pool (member calls genuinely run
     * concurrently — see EnsembleAgents.ENSEMBLE_POOL).
     */
    public final boolean jvmNative, python, parallel, speed;
    /** Authored expected-performance score, 0-100. Drives the infocard's Performance
     *  bar and the ensemble dropdown sort order. */
    public final int strength;

    AiTechnique(String id, String display, String category, String dataMode, String kind,
                Family family, boolean jvmNative, boolean python, boolean parallel, boolean speed,
                int strength, String blurb) {
        this.id = id; this.display = display; this.category = category;
        this.dataMode = dataMode; this.kind = kind; this.family = family;
        this.jvmNative = jvmNative; this.python = python;
        this.parallel = parallel; this.speed = speed;
        this.strength = strength; this.blurb = blurb;
    }

    public boolean imitationBased() { return family == Family.IMITATION; }

    public boolean isEnsemble() { return kind.equals("ensemble"); }

    /**
     * Exactly which building-block agents an ensemble is composed of, in the role
     * order the composition uses them — empty for non-ensembles. This is the single
     * source the UI (playground drawer, infocard) shows, so what an ensemble "uses"
     * is explicit rather than buried in EnsembleAgents' implementation.
     */
    public String[] ensembleMembers() {
        return switch (this) {
            case ENS_ADAPTIVE_VOTE -> new String[]{"MCTS (voter)", "Greedy One-Ply (voter)", "Heuristic (voter)"};
            case ENS_BANDIT        -> new String[]{"MCTS (arm)", "Greedy One-Ply (arm)", "Heuristic (arm)"};
            case ENS_MCTS_NET      -> new String[]{"MCTS (search)", "Donor policy net (advisor)"};
            case ENS_MCTS_TIEBREAK -> new String[]{"MCTS (search)", "Greedy One-Ply (exact tiebreak)"};
            case ENS_RTG_VERIFIED  -> new String[]{"Return-Conditioned (proposer)", "MCTS (verifier)"};
            default -> new String[0];
        };
    }

    /**
     * True only for the one technique whose shown training command has a real,
     * existing {@code --device} flag that a detected GPU can actually satisfy
     * ({@code python -m suika.train_ppo}). Every other Python-family technique's
     * training script is either CPU-only or not yet implemented, so claiming
     * GPU use there would be misleading — see {@link GpuProbe}'s class doc.
     */
    public boolean gpuCapableTraining() { return this == PPO; }

    /**
     * True only for techniques with a REAL, runnable Python training script that now
     * writes TensorBoard event files ({@code train_ppo.py}, {@code decision_transformer.py}
     * — both accept {@code --tensorboard}/{@code --tb-detailed}/{@code --tb-logdir}).
     * MuZero has no training script in this project yet ({@link PythonRunner}'s shown
     * command is illustrative only), so claiming TensorBoard support there would be
     * fabricated — same honesty rule as {@link #gpuCapableTraining()}.
     */
    public boolean supportsTensorboard() { return this == PPO || this == DECISION_TRANSFORMER; }

    /**
     * True for every technique whose training progress can be VIEWED in TensorBoard. That's
     * the two Python scripts above <em>plus</em> all the JVM-native learners, whose LiveChart
     * curves are now exported to a TensorBoard log on each save (see
     * {@link TensorboardLauncher#exportScalarsAsync}). The distinction from
     * {@link #supportsTensorboard()} is real: only the Python scripts honour the
     * {@code --tb-detailed} training flag, so the "detailed logging" toggle stays gated on
     * that method while the "open TensorBoard" button is gated on this one.
     */
    public boolean tensorboardViewable() {
        return supportsTensorboard()
                || family == Family.EVOLUTION
                || family == Family.IMITATION
                || family == Family.DEEP_RL
                || isEnsemble();
    }

    /** True for techniques that run a neural network (so GPU inference is meaningful):
     *  the Python-backed ones, the evolved/imitation nets, and the net-blend ensemble. The
     *  pure search/rule techniques (MCTS/Greedy/Heuristic and the search-only ensembles)
     *  are false — they have no net and stay on the CPU by nature. */
    public boolean netBased() {
        return switch (this) {
            case PPO, DQN, MUZERO, ALPHAZERO, DECISION_TRANSFORMER,
                 NEUROEVO, CMA_ES, PBT, DAGGER, BC, ENS_MCTS_NET -> true;
            default -> false;
        };
    }

    /**
     * True only for techniques whose LIVE control-center decision loop genuinely runs a
     * net query through the GPU bridge when GPU inference is active — i.e. where showing
     * "GPU" next to the Parallelism control ({@link PlaygroundConfig#parallelismLabel()})
     * is actually true right now, not just plausible because the technique happens to
     * involve a net somewhere.
     *
     * <p>{@link #netBased()} is broader and deliberately includes techniques where that
     * would be dishonest in live play: AlphaZero/MuZero's live JVM view is a pure search
     * surrogate that never calls a net at all (the real net only exists in the separate
     * Python trainer — see their {@code liveHint()}); Evolution/Imitation/DQN query their
     * net many times a second across up to 16 live boards, a frequency where the GPU
     * bridge's ~1ms IPC round-trip is a severe regression, not a speedup, so their live
     * loop stays JVM-CPU by design (see {@link GpuInferenceBridge}'s class doc) — GPU only
     * ever helps THOSE techniques on the separate load-once/infer-many playback path
     * ({@link AiSlotPlayer#load}), which this method intentionally does not cover since
     * {@link PlaygroundConfig} is never shown there.
     *
     * <p>{@link #ENS_MCTS_NET} is different: its donor net is queried exactly ONCE per
     * move (see {@code EnsembleAgents.NetGuidedMcts}), a cadence where GPU offload is a
     * genuine, safe win even in live play — so it's the one technique this returns true
     * for.
     */
    public boolean gpuInferenceLive() { return this == ENS_MCTS_NET; }

    /**
     * A plain-English, no-prior-knowledge explanation of the technique, already split
     * into short lines for the infocard. Think "explain it to a friend", not a paper.
     */
    public String[] explainerLines() {
        return switch (this) {
            case MCTS -> new String[]{
                "Before each drop it plays out thousands of imaginary",
                "futures in a perfect copy of the game, then picks the",
                "column that led to the best outcomes. No training — it",
                "just thinks hard every single move." };
            case ALPHAZERO -> new String[]{
                "Same look-ahead search as MCTS, but a neural network",
                "learns to guess good moves so the search can focus on",
                "the promising ones. It gets smarter the more it plays,",
                "combining planning with learning." };
            case GREEDY -> new String[]{
                "For every possible column it actually drops the fruit",
                "and checks the immediate score, then keeps the best.",
                "Fast and surprisingly solid — but it never looks more",
                "than one move ahead." };
            case DQN -> new String[]{
                "Learns a 'how good is each column right now' score",
                "(a Q-value) by trial and error. It remembers thousands",
                "of past moves in a replay buffer and studies random",
                "batches of them — the classic Atari-playing algorithm." };
            case PPO -> new String[]{
                "A neural network learns by trial and error, like a",
                "player slowly getting good. It nudges its behaviour",
                "toward moves that earned more points over millions of",
                "practice drops. Training runs in Python." };
            case MUZERO -> new String[]{
                "It learns its own mental 'model' of how the game works,",
                "then practises inside that imagination instead of the",
                "real game — so it can plan ahead without a built-in",
                "simulator." };
            case NEUROEVO -> new String[]{
                "Keeps a population of AI brains, lets them all play,",
                "breeds the best ones together and randomly tweaks the",
                "offspring — evolution, not gradients. You choose the",
                "selection math: tournament, rank, or Boltzmann." };
            case CMA_ES -> new String[]{
                "A smarter evolution strategy: instead of random tweaks",
                "it learns which directions of change tend to help and",
                "samples new candidates from that adapting cloud. Strong",
                "on fine-tuning many numbers at once." };
            case PBT -> new String[]{
                "A whole population trains at once. The stragglers copy",
                "the current winners' brains and settings, then shake",
                "them up a little — good ideas spread fast while the",
                "search keeps exploring. Evolution with live exploiting." };
            case DAGGER -> new String[]{
                "You play, the AI copies you — but it also asks an expert",
                "what to do in the tricky spots it ends up in, so it",
                "doesn't just inherit your mistakes. Imitation that fixes",
                "its own blind spots." };
            case BC -> new String[]{
                "Pure copy-cat learning: it watches the drops YOU make",
                "and trains a network to do the same thing in the same",
                "situations. Play your way and it learns your style —",
                "no expert relabeling, just your example." };
            case DECISION_TRANSFORMER -> new String[]{
                "You tell it a target score and it treats playing like",
                "finishing a sentence: given 'I want to reach X', predict",
                "the next drop. Learned entirely from logged games, no",
                "live trial-and-error." };
            case HEURISTIC -> new String[]{
                "A hand-written rulebook: look for two equal fruits to",
                "merge, otherwise keep the pile flat and low. No learning",
                "at all — just common-sense instructions." };
            case ENS_ADAPTIVE_VOTE -> new String[]{
                "Uses: MCTS + Greedy + Heuristic, each voting for a",
                "column. Each member's influence rises or falls with the",
                "score it's actually been earning (multiplicative-weights",
                "update) — the committee learns who to listen to." };
            case ENS_BANDIT -> new String[]{
                "Uses: MCTS + Greedy + Heuristic as three 'arms'. A UCB1",
                "bandit picks ONE agent to make each move, tracking which",
                "has been paying off best lately (explore/exploit) — a",
                "meta-agent that learns who should drive." };
            case ENS_MCTS_NET -> new String[]{
                "Uses: MCTS + a donor policy net (your choice of trained",
                "Neuroevo, CMA-ES or DAgger save). Search narrows the",
                "options; the net's opinion weighs in on the final pick,",
                "with an adjustable blend weight." };
            case ENS_MCTS_TIEBREAK -> new String[]{
                "Uses: MCTS + Greedy One-Ply. When the search genuinely",
                "can't decide between a few columns (all nearly equally",
                "visited), it drops-and-settles each one FOR REAL to see",
                "which truly scores best, instead of guessing." };
            case ENS_RTG_VERIFIED -> new String[]{
                "Uses: a Return-Conditioned proposer + a shallow MCTS",
                "verifier. The proposer aims a drop at your target score;",
                "the verifier double-checks it against its own best idea",
                "and keeps whichever one actually scores higher." };
        };
    }

    /**
     * Ordered, plain-language guide to the settings that apply to THIS technique — what
     * each knob does and how to use it, top (most impactful) to bottom. Shown in the
     * infocard so a player knows exactly which drawer controls matter for the technique
     * they picked. Preset/Speed apply to everything, so they lead every list.
     */
    public String[] settingsHints() {
        java.util.List<String> h = new java.util.ArrayList<>();
        h.add("Preset — Slow = best quality (deeper/slower), High = fastest.");
        h.add("Speed — playback multiplier; doesn't change the AI, just how fast you watch.");
        switch (this) {
            case MCTS, ALPHAZERO -> {
                h.add("Rollouts — imagined futures per move: more = stronger but slower.");
                h.add("Parallelism — search trees run at once; Auto uses all cores.");
            }
            case GREEDY -> h.add("Parallelism — columns evaluated at once; Auto uses all cores.");
            case HEURISTIC -> h.add("No tunables — it follows fixed merge rules (a baseline).");
            case PPO -> {
                h.add("Preset here — scales the live JVM surrogate's compute; the real net");
                h.add("   trains in Python (presets don't change the Python trainer itself).");
                h.add("Parallelism — parallel training envs (--n-envs) for the Python trainer.");
                h.add("Prefer GPU (Settings) — trains/infers on CUDA when a GPU is present.");
                h.add("TensorBoard — Detailed adds per-episode score + weight histograms;");
                h.add("   OPEN starts a local dashboard once you've run the command below.");
            }
            case MUZERO -> {
                h.add("Preset here — scales the live MCTS surrogate's search depth (rollouts");
                h.add("   + think budget); the learned world-model itself trains in Python.");
                h.add("Prefer GPU (Settings) — the Python trainer/inference uses CUDA.");
            }
            case DECISION_TRANSFORMER -> {
                h.add("Target return — the score you ask it to aim for; higher = bolder play.");
                h.add("TensorBoard — Detailed adds per-batch loss, gradient norm, and weight");
                h.add("   histograms; OPEN starts a local dashboard once you've trained.");
            }
            case NEUROEVO -> {
                h.add("Population — genomes per generation: bigger = better search, more compute.");
                h.add("Selection — Tournament / Rank / Boltzmann: how parents are chosen.");
                h.add("Mutation σ — random tweak size; Breeding toggles crossover & σ-anneal.");
                h.add("Sims/generation — games averaged per genome: more = less noisy fitness.");
                h.add("Elite views / Ghost — how many lineages you watch live at once.");
            }
            case CMA_ES -> {
                h.add("Population — λ candidates the Gaussian cloud samples each generation.");
                h.add("Sims/generation — games averaged per candidate: more = steadier fitness.");
                h.add("Elite views / Ghost — how many top candidates you watch live.");
            }
            case PBT -> {
                h.add("Population — members training in parallel; bigger = more exploration.");
                h.add("Selection — how winners are chosen for the stragglers to copy.");
                h.add("Sims/generation — games averaged per member: more = steadier ranking.");
                h.add("Elite views / Ghost — how many members you watch live.");
            }
            case DQN -> {
                h.add("Learning rate — TD update step size; too high makes Q-values oscillate.");
                h.add("Epsilon decays automatically — it explores early, exploits once trained.");
            }
            case DAGGER -> h.add("Learning rate — how fast it adapts to the states it visits.");
            case BC -> h.add("Learning rate — how fast it fits to your recorded drops.");
            case ENS_MCTS_NET -> {
                h.add("Rollouts — depth of the MCTS half of the blend.");
                h.add("Donor net — which trained save (Neuroevo/CMA-ES/DAgger) advises the pick.");
                h.add("Donor slot — Auto (first saved) or a specific slot to compare nets.");
                h.add("Net weight — how much the net's opinion counts vs raw search visits.");
            }
            case ENS_MCTS_TIEBREAK -> {
                h.add("Rollouts — depth of the MCTS search.");
                h.add("Tie threshold — how close columns must be to trigger the exact tiebreak.");
            }
            case ENS_RTG_VERIFIED -> h.add("Target return — score the proposer aims for; the MCTS verifier checks it.");
            case ENS_ADAPTIVE_VOTE -> {
                h.add("Rollouts — depth of the MCTS voter.");
                h.add("Adapt rate — how fast each member's trust weight shifts with results.");
            }
            case ENS_BANDIT -> {
                h.add("Rollouts — depth of the MCTS arm.");
                h.add("Explore (UCB c) — higher tries under-used agents more; lower exploits.");
            }
        }
        if (family == Family.EVOLUTION || family == Family.IMITATION || family == Family.DEEP_RL)
            h.add("SAVES — persist/reload trained weights; Autosave (Settings) writes slot 1.");
        return h.toArray(new String[0]);
    }

    /** One short line describing what this technique is doing moment-to-moment, live. */
    public String liveHint() {
        return switch (this) {
            case MCTS, ALPHAZERO    -> "simulating many futures, then dropping the best";
            case GREEDY             -> "trying every column, keeping the highest score";
            case HEURISTIC          -> "following hand-written merge rules";
            case ENS_MCTS_NET       -> "search + donor net blend, choosing the top pick";
            case ENS_MCTS_TIEBREAK  -> "settling MCTS's closest calls for real";
            case ENS_ADAPTIVE_VOTE  -> "three agents voting, trust weights adapting";
            case ENS_RTG_VERIFIED   -> "proposing boldly, verifying with a quick search";
            case ENS_BANDIT         -> "picking which single agent drives this move";
            case NEUROEVO, CMA_ES, PBT -> "breeding & mutating a population of AI brains";
            case DAGGER, BC         -> "learning to copy your drops in real time";
            case DECISION_TRANSFORMER -> "predicting drops from logged games";
            case MUZERO             -> "planning inside a learned world model";
            case PPO                -> "running a learned policy (Python-trained)";
            case DQN                -> "learning Q-values from replayed experience";
        };
    }

    /** Short environment badge: "JVM", "Python", or "JVM + Python". */
    public String envBadge() {
        if (jvmNative && python) return "JVM + Python";
        return python ? "Python" : "JVM";
    }
}
