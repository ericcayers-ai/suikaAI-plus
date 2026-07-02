package dev.suika.game;

/**
 * The full AI capability matrix (ROADMAP §IV.13) surfaced in the AI Playground.
 *
 * <p>Ordered from strongest expected performance (top) to weakest (bottom) so the
 * capability list reads like a leaderboard at a glance.
 */
public enum AiTechnique {

    // id, display, category, dataMode, kind, family, jvmNative, python, parallel, speed, blurb
    // ---- Planning: strongest single-agent reasoners ----
    MCTS("mcts", "MCTS", "Planning", "either", "planning", Family.PLANNING,
            true, false, true, true,
            "Monte-Carlo Tree Search over the perfect simulator. UCB1 selection + heuristic rollouts."),
    ALPHAZERO("alphazero", "AlphaZero", "Planning + Learning", "state", "both", Family.PLANNING,
            true, true, true, true,
            "MCTS guided by a policy-value net. Search core JVM, net trains in Python."),
    GREEDY("greedy", "Greedy One-Ply", "Planning", "state", "planning", Family.PLANNING,
            true, false, true, true,
            "Simulates every column for real, picks the best immediate score. Fast and solid."),

    // ---- Deep RL: policy-gradient and value-based learners (need Python) ----
    PPO("ppo", "PPO", "Deep RL", "either", "learning", Family.PYTHON,
            false, true, true, true,
            "On-policy policy-gradient via Stable-Baselines3. Pairs with the vector env."),
    DQN("dqn", "DQN / Rainbow", "Deep RL", "either", "learning", Family.PYTHON,
            false, true, false, true,
            "Value-based off-policy learning with replay. Discrete drop columns."),
    SAC("sac", "SAC", "Deep RL", "state", "learning", Family.PYTHON,
            false, true, false, true,
            "Off-policy max-entropy actor-critic for continuous drop position."),

    // ---- Model-based: learn and plan inside a world model ----
    MUZERO("muzero", "MuZero", "Model-Based", "pixels", "both", Family.PYTHON,
            false, true, true, true,
            "Learn a latent dynamics model and plan inside it. Compare vs the true sim."),
    DREAMER("dreamer", "Dreamer", "Model-Based", "pixels", "model-based", Family.PYTHON,
            false, true, true, true,
            "World model trained from pixels; the policy learns inside the dream."),

    // ---- Evolution: gradient-free optimisation ----
    NEUROEVO("neuroevo", "Neuroevolution (GA)", "Evolution", "state", "learning", Family.EVOLUTION,
            true, false, true, true,
            "Evolve MLP weights by tournament selection + Gaussian mutation. No gradients."),
    CMA_ES("cma-es", "CMA-ES", "Evolution", "state", "learning", Family.EVOLUTION,
            true, false, true, true,
            "Covariance-adaptive evolution strategy. Strong on continuous weight spaces."),
    PBT("pbt", "Population-Based Training", "Self-Play", "either", "meta", Family.EVOLUTION,
            true, false, true, true,
            "A population trains in parallel, copying winners and perturbing hyperparams."),

    // ---- Imitation: clone or distil human / expert play ----
    DAGGER("dagger", "DAgger", "Imitation", "state", "imitation", Family.IMITATION,
            true, false, false, true,
            "Iterative imitation: relabel the states the agent actually visits."),
    BC("bc", "Behavioral Cloning", "Imitation", "state", "imitation", Family.IMITATION,
            true, false, false, true,
            "Learn to copy YOUR drops from your recorded games. Train-on-my-playstyle."),
    GAIL("gail", "Inverse RL / GAIL", "Imitation", "state", "imitation+RL", Family.PYTHON,
            false, true, false, true,
            "Recover the reward behind demos, then optimise it. Learns the why."),

    // ---- Offline: learn from logged data without live interaction ----
    DECISION_TRANSFORMER("dt", "Decision Transformer", "Offline", "state", "offline", Family.PYTHON,
            true, true, false, true,
            "Return-conditioned sequence modeling. 'Play to reach score X.'"),
    OFFLINE_RL("offline", "Offline RL (CQL/IQL)", "Offline", "either", "offline", Family.PYTHON,
            false, true, false, true,
            "Learn from logged games with no live interaction. Conservative value learning."),

    // ---- Generative: denoising / flow-based action generation ----
    DIFFUSION("diffusion", "Diffusion Policy", "Generative", "state", "generative", Family.PYTHON,
            true, true, false, true,
            "Denoise an action from noise. Captures multimodal 'many good drops'."),
    FLOW("flow", "Flow Matching", "Generative", "state", "generative", Family.PYTHON,
            true, true, false, true,
            "Continuous-flow cousin of diffusion. Fewer inference steps, real-time."),

    // ---- Self-play / adversarial ----
    SELF_PLAY("self-play", "Racing Self-Play", "Self-Play", "either", "learning", Family.PYTHON,
            true, true, true, true,
            "Two agents race the same fruit sequence; reward is relative score."),

    // ---- Baselines (weakest) ----
    HEURISTIC("heuristic", "Heuristic", "Baseline", "state", "scripted", Family.PLANNING,
            true, false, false, true,
            "Seek same-tier merges, else keep the surface flat. Scripted, no learning."),
    RANDOM("random", "Random", "Baseline", "either", "—", Family.PLANNING,
            true, false, false, true,
            "Uniformly random drops. The floor every learner must beat."),

    // ---- Ensemble: composed agents combining two or more of the techniques above.
    // Family.PLANNING so they run through PlanningRunner (live board, no separate
    // trainer loop needed) — each genuinely calls through to the real agents it
    // combines; see EnsembleAgents.java for the actual composition logic. ----
    ENS_MCTS_NET("ens-mcts-net", "MCTS + Policy Net", "Ensemble", "state", "ensemble", Family.PLANNING,
            true, false, false, true,
            "MCTS search narrows the choice; a policy net's logits nudge the final pick."),
    ENS_GREEDY_GUARD("ens-greedy-guard", "Policy Net + Greedy Guard", "Ensemble", "state", "ensemble", Family.PLANNING,
            true, false, false, true,
            "Plays a policy net normally, but Greedy One-Ply overrides it on an immediate merge."),
    ENS_MCTS_TIEBREAK("ens-mcts-greedy-tiebreak", "MCTS + Greedy Tiebreak", "Ensemble", "state", "ensemble", Family.PLANNING,
            true, false, false, true,
            "MCTS's genuinely-tied top columns get an exact one-ply evaluation to break the tie."),
    ENS_VOTING("ens-voting-committee", "Voting Committee", "Ensemble", "state", "ensemble", Family.PLANNING,
            true, false, false, true,
            "MCTS, Greedy, and Heuristic each propose a column; majority wins."),
    ENS_EVOLVED_MCTS("ens-evolved-mcts", "MCTS + Evolved Value Net", "Ensemble", "state", "ensemble", Family.PLANNING,
            true, false, false, true,
            "MCTS blended with a CMA-ES-evolved value net (heavily trusted when a slot is saved)."),
    ENS_IMITATION_MCTS("ens-imitation-mcts", "MCTS + Imitation Blend", "Ensemble", "state", "ensemble", Family.PLANNING,
            true, false, false, true,
            "Defers to a DAgger-trained policy's pick unless MCTS's search strongly disagrees."),
    ENS_RTG_VERIFIED("ens-rtg-verified", "Return-Conditioned + MCTS Verify", "Ensemble", "state", "ensemble", Family.PLANNING,
            true, false, false, true,
            "A return-conditioned proposal gets sanity-checked against a shallow MCTS search."),
    ENS_GENERATIVE_GREEDY("ens-generative-greedy", "Generative + Greedy Filter", "Ensemble", "state", "ensemble", Family.PLANNING,
            true, false, false, true,
            "Samples several diffusion-style proposals, keeps whichever scores best exactly."),
    ENS_ADAPTIVE_VOTE("ens-adaptive-vote", "Adaptive Voting Committee", "Ensemble", "state", "ensemble", Family.PLANNING,
            true, false, false, true,
            "Like Voting Committee, but each member's trust weight adapts to its recent results."),
    ENS_BANDIT("ens-bandit-meta", "Bandit Meta-Controller", "Ensemble", "state", "ensemble", Family.PLANNING,
            true, false, false, true,
            "A UCB1 bandit learns, move by move, which single agent to trust right now.");

    /** Which specialised control-center drives this technique. */
    public enum Family { PLANNING, EVOLUTION, IMITATION, PYTHON }

    public final String id, display, category, dataMode, kind, blurb;
    public final Family family;
    /**
     * {@code parallel}: true only when the "Parallelism" control in the AI Playground
     * drawer has a real, wired effect for this technique — root-parallel search
     * (MCTS/AlphaZero/MuZero/Dreamer/SelfPlay), column-parallel evaluation (Greedy),
     * the evolution eval pool (Neuroevo/CMA-ES/PBT), or PPO's {@code --n-envs}/
     * {@code --device} training flags. Everywhere else it's {@code false} so the drawer
     * honestly shows "n/a" instead of a knob that silently does nothing.
     */
    public final boolean jvmNative, python, parallel, speed;

    AiTechnique(String id, String display, String category, String dataMode, String kind,
                Family family, boolean jvmNative, boolean python, boolean parallel, boolean speed,
                String blurb) {
        this.id = id; this.display = display; this.category = category;
        this.dataMode = dataMode; this.kind = kind; this.family = family;
        this.jvmNative = jvmNative; this.python = python;
        this.parallel = parallel; this.speed = speed; this.blurb = blurb;
    }

    public boolean imitationBased() { return family == Family.IMITATION; }

    /**
     * True only for the one technique whose shown training command has a real,
     * existing {@code --device} flag that a detected GPU can actually satisfy
     * ({@code python -m suika.train_ppo}). Every other Python-family technique's
     * training script is either CPU-only or not yet implemented, so claiming
     * GPU use there would be misleading — see {@link GpuProbe}'s class doc.
     */
    public boolean gpuCapableTraining() { return this == PPO; }

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
            case PPO, DQN, SAC -> new String[]{
                "A neural network learns by trial and error, like a",
                "player slowly getting good. It nudges its behaviour",
                "toward moves that earned more points over millions of",
                "practice drops. Training runs in Python." };
            case MUZERO, DREAMER -> new String[]{
                "It learns its own mental 'model' of how the game works,",
                "then practises inside that imagination instead of the",
                "real game — so it can plan ahead without a built-in",
                "simulator." };
            case NEUROEVO -> new String[]{
                "Keeps a population of AI brains, lets them all play,",
                "breeds the best ones together and randomly tweaks the",
                "offspring — evolution, not gradients. Repeat for many",
                "generations and watch the scores climb." };
            case CMA_ES -> new String[]{
                "A smarter evolution strategy: instead of random tweaks",
                "it learns which directions of change tend to help and",
                "samples new candidates from that adapting cloud. Strong",
                "on fine-tuning many numbers at once." };
            case PBT -> new String[]{
                "A whole population trains at the same time. Losers copy",
                "the winners' brains and settings, then shake them up a",
                "little — so good ideas spread while the search keeps",
                "exploring." };
            case DAGGER -> new String[]{
                "You play, the AI copies you — but it also asks an expert",
                "what to do in the tricky spots it ends up in, so it",
                "doesn't just inherit your mistakes. Imitation that fixes",
                "its own blind spots." };
            case BC -> new String[]{
                "Pure copy-cat learning: it watches the drops YOU make",
                "and trains a network to do the same thing in the same",
                "situations. Play your way and it learns your style.",
                "No reward signal needed — just your example." };
            case GAIL -> new String[]{
                "Instead of copying moves directly, it tries to figure",
                "out WHY you play the way you do — the hidden goal — then",
                "optimises for that. Learns the intent behind the demos,",
                "not just the actions." };
            case DECISION_TRANSFORMER -> new String[]{
                "You tell it a target score and it treats playing like",
                "finishing a sentence: given 'I want to reach X', predict",
                "the next drop. Learned entirely from logged games, no",
                "live trial-and-error." };
            case OFFLINE_RL -> new String[]{
                "Learns a good policy purely from a fixed pile of recorded",
                "games — never touching the live game while training. Plays",
                "it safe by staying close to moves it has actually seen",
                "work." };
            case DIFFUSION -> new String[]{
                "Borrows the trick behind AI image generators: start from",
                "random noise and repeatedly 'denoise' it into a sensible",
                "drop. Great at capturing that many different drops can",
                "all be good." };
            case FLOW -> new String[]{
                "A faster cousin of the diffusion approach — it learns a",
                "smooth 'flow' that turns noise into a good drop in just a",
                "few steps, so it can decide in real time." };
            case SELF_PLAY -> new String[]{
                "Two agents race on the exact same fruits; whoever scores",
                "more wins. By constantly trying to beat a copy of itself,",
                "it keeps pushing its own skill upward." };
            case HEURISTIC -> new String[]{
                "A hand-written rulebook: look for two equal fruits to",
                "merge, otherwise keep the pile flat and low. No learning",
                "at all — just common-sense instructions." };
            case RANDOM -> new String[]{
                "Drops in a completely random column every time. It exists",
                "as the bottom of the leaderboard — the score every real",
                "AI has to beat." };
            case ENS_MCTS_NET -> new String[]{
                "MCTS searches like usual, then a neural network's own",
                "opinion nudges the final choice among the columns the",
                "search actually visited — search narrows it down, the",
                "net helps pick the winner." };
            case ENS_GREEDY_GUARD -> new String[]{
                "Normally plays whatever a policy network suggests, but",
                "the instant an easy same-tier merge appears anywhere on",
                "the board, Greedy One-Ply steps in and takes it — a",
                "safety net against a distracted policy." };
            case ENS_MCTS_TIEBREAK -> new String[]{
                "When MCTS's search genuinely can't decide between a",
                "few columns (they're all nearly equally visited), it",
                "actually drops-and-settles each one for real to see",
                "which truly scores best, instead of guessing." };
            case ENS_VOTING -> new String[]{
                "Three different agents — a searcher, a one-ply",
                "evaluator, and a scripted rulebook — each vote for a",
                "column. Whichever column two or more agree on wins;",
                "true three-way ties default to the searcher." };
            case ENS_EVOLVED_MCTS -> new String[]{
                "Same idea as MCTS + Policy Net, but the net comes from",
                "an evolved (CMA-ES) population instead of a blank",
                "slate — once you've trained one, this ensemble leans",
                "on it heavily rather than lightly." };
            case ENS_IMITATION_MCTS -> new String[]{
                "Trusts a DAgger-trained clone of expert play by",
                "default, but only if MCTS's own search doesn't",
                "strongly object — the search acts as a check on the",
                "clone, not a replacement for it." };
            case ENS_RTG_VERIFIED -> new String[]{
                "A return-conditioned agent proposes an ambitious drop",
                "aimed at a target score, then a quick MCTS search",
                "double-checks it against its own best idea and keeps",
                "whichever one actually scores higher." };
            case ENS_GENERATIVE_GREEDY -> new String[]{
                "Samples several different candidate drops the way a",
                "diffusion model would, then drops-and-settles each",
                "one for real and keeps the best — proposal by",
                "generation, selection by exact evaluation." };
            case ENS_ADAPTIVE_VOTE -> new String[]{
                "Same three-agent vote as Voting Committee, but each",
                "member's influence rises or falls based on the score",
                "it's actually been earning recently — the committee",
                "learns who to listen to as it plays." };
            case ENS_BANDIT -> new String[]{
                "Instead of blending opinions, it picks ONE agent to",
                "make each individual move, tracking which one has",
                "been paying off best lately (classic explore/exploit)",
                "— a meta-agent that learns who should drive." };
        };
    }

    /** One short line describing what this technique is doing moment-to-moment, live. */
    public String liveHint() {
        return switch (family) {
            case PLANNING -> switch (this) {
                case MCTS, ALPHAZERO   -> "simulating many futures, then dropping the best";
                case GREEDY            -> "trying every column, keeping the highest score";
                case HEURISTIC         -> "following hand-written merge rules";
                case RANDOM            -> "dropping in a random column";
                case ENS_MCTS_NET, ENS_EVOLVED_MCTS -> "search + net blend, choosing the top pick";
                case ENS_GREEDY_GUARD  -> "playing the policy net, watching for easy merges";
                case ENS_MCTS_TIEBREAK -> "settling MCTS's closest calls for real";
                case ENS_VOTING, ENS_ADAPTIVE_VOTE -> "three agents voting on the next drop";
                case ENS_IMITATION_MCTS -> "leaning on imitation, checked by search";
                case ENS_RTG_VERIFIED  -> "proposing boldly, verifying with a quick search";
                case ENS_GENERATIVE_GREEDY -> "sampling proposals, keeping the best one";
                case ENS_BANDIT        -> "picking which single agent drives this move";
                default                -> "planning the next drop";
            };
            case EVOLUTION -> "breeding & mutating a population of AI brains";
            case IMITATION -> "learning to copy your drops in real time";
            case PYTHON    -> switch (this) {
                case DIFFUSION, FLOW                  -> "denoising random noise into a drop";
                case DECISION_TRANSFORMER, OFFLINE_RL -> "predicting drops from logged games";
                case MUZERO, DREAMER                  -> "planning inside a learned world model";
                case SELF_PLAY                        -> "racing a copy of itself for a better score";
                default                               -> "running a learned policy (Python-trained)";
            };
        };
    }

    /** Short environment badge: "JVM", "Python", or "JVM + Python". */
    public String envBadge() {
        if (jvmNative && python) return "JVM + Python";
        return python ? "Python" : "JVM";
    }
}
