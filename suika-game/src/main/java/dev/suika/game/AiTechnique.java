package dev.suika.game;

/**
 * The full AI capability matrix (ROADMAP §IV.13) surfaced in the AI Playground.
 *
 * <p>Every technique the project implements is listed here with the metadata the
 * Playground UI needs: which control-center {@link Family} drives it, its data mode,
 * whether it plans/learns/imitates, whether it runs JVM-native or wants the Python
 * sidecar, and which runtime knobs (speed / parallelism) apply.
 */
public enum AiTechnique {

    // id, display, category, dataMode, kind, family, jvmNative, python, parallel, speed, blurb
    RANDOM("random", "Random", "Baseline", "either", "—", Family.PLANNING,
            true, false, false, true,
            "Uniformly random drops. The floor every learner must beat."),
    HEURISTIC("heuristic", "Heuristic", "Baseline", "state", "scripted", Family.PLANNING,
            true, false, false, true,
            "Seek same-tier merges, else keep the surface flat."),
    GREEDY("greedy", "Greedy One-Ply", "Planning", "state", "planning", Family.PLANNING,
            true, false, true, true,
            "Simulates every column for real, picks the best immediate score."),
    MCTS("mcts", "MCTS", "Planning", "either", "planning", Family.PLANNING,
            true, false, true, true,
            "Monte-Carlo Tree Search over the perfect simulator. UCB1 selection."),
    ALPHAZERO("alphazero", "AlphaZero", "Planning + Learning", "state", "both", Family.PLANNING,
            true, true, true, true,
            "MCTS guided by a policy-value net. Search core JVM, net trains in Python."),

    NEUROEVO("neuroevo", "Neuroevolution (GA)", "Evolution", "state", "learning", Family.EVOLUTION,
            true, false, true, true,
            "Evolve MLP weights by tournament selection + Gaussian mutation. No gradients."),
    CMA_ES("cma-es", "CMA-ES", "Evolution", "state", "learning", Family.EVOLUTION,
            true, false, true, true,
            "Covariance-adaptive evolution strategy. Strong on continuous weight spaces."),

    BC("bc", "Behavioral Cloning", "Imitation", "state", "imitation", Family.IMITATION,
            true, false, false, true,
            "Learn to copy YOUR drops from your recorded games. Train-on-my-playstyle."),
    DAGGER("dagger", "DAgger", "Imitation", "state", "imitation", Family.IMITATION,
            true, false, false, true,
            "Iterative imitation: relabel the states the agent actually visits."),

    PPO("ppo", "PPO", "Deep RL", "either", "learning", Family.PYTHON,
            false, true, true, true,
            "On-policy policy-gradient via Stable-Baselines3. Pairs with the vector env."),
    DQN("dqn", "DQN / Rainbow", "Deep RL", "either", "learning", Family.PYTHON,
            false, true, true, true,
            "Value-based off-policy learning with replay. Discrete drop columns."),
    SAC("sac", "SAC", "Deep RL", "state", "learning", Family.PYTHON,
            false, true, true, true,
            "Off-policy max-entropy actor-critic for continuous drop position."),
    DIFFUSION("diffusion", "Diffusion Policy", "Generative", "state", "generative", Family.PYTHON,
            true, true, false, true,
            "Denoise an action from noise. Captures multimodal 'many good drops'."),
    FLOW("flow", "Flow Matching", "Generative", "state", "generative", Family.PYTHON,
            true, true, false, true,
            "Continuous-flow cousin of diffusion. Fewer inference steps, real-time."),
    DECISION_TRANSFORMER("dt", "Decision Transformer", "Offline", "state", "offline", Family.PYTHON,
            true, true, false, true,
            "Return-conditioned sequence modeling. 'Play to reach score X.'"),
    OFFLINE_RL("offline", "Offline RL (CQL/IQL)", "Offline", "either", "offline", Family.PYTHON,
            false, true, true, true,
            "Learn from logged games with no live interaction. Conservative value learning."),
    GAIL("gail", "Inverse RL / GAIL", "Imitation", "state", "imitation+RL", Family.PYTHON,
            false, true, true, true,
            "Recover the reward behind demos, then optimise it. Learns the why."),
    MUZERO("muzero", "MuZero", "Model-Based", "pixels", "both", Family.PYTHON,
            false, true, true, true,
            "Learn a latent dynamics model and plan inside it. Compare vs the true sim."),
    DREAMER("dreamer", "Dreamer", "Model-Based", "pixels", "model-based", Family.PYTHON,
            false, true, true, true,
            "World model trained from pixels; the policy learns inside the dream."),

    SELF_PLAY("self-play", "Racing Self-Play", "Self-Play", "either", "learning", Family.PYTHON,
            true, true, true, true,
            "Two agents race the same fruit sequence; reward is relative score."),
    PBT("pbt", "Population-Based Training", "Self-Play", "either", "meta", Family.EVOLUTION,
            true, false, true, true,
            "A population trains in parallel, copying winners and perturbing hyperparams.");

    /** Which specialised control-center drives this technique. */
    public enum Family { PLANNING, EVOLUTION, IMITATION, PYTHON }

    public final String id, display, category, dataMode, kind, blurb;
    public final Family family;
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

    /** Short environment badge: "JVM", "Python", or "JVM + Python". */
    public String envBadge() {
        if (jvmNative && python) return "JVM + Python";
        return python ? "Python" : "JVM";
    }
}
