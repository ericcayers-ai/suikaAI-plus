package dev.suika.game;

/**
 * Control center for techniques whose full training lives in Python (PPO/DQN/SAC,
 * Diffusion/Flow, Decision Transformer, Offline RL, GAIL, MuZero, Dreamer).
 *
 * <p>It is honest about the environment: a background probe detects whether a Python
 * interpreter is available, and the panel surfaces the exact training command and the
 * ONNX deploy path. Meanwhile a JVM-native surrogate plays live so the board and score
 * graph are always populated — train the real policy in Python, export ONNX, and load
 * it through {@code OnnxPolicyRunner} for in-game inference with no Python at runtime.
 */
public final class PythonRunner extends AgentRunner {

    private volatile String pythonStatus = "probing python...";
    private volatile boolean probed = false;

    public PythonRunner(SuikaGame game, PlaygroundConfig cfg) { super(game, cfg); }

    @Override
    public void start() {
        super.start();
        Thread t = new Thread(this::probePython, "python-probe");
        t.setDaemon(true);
        t.start();
    }

    private void probePython() {
        for (String exe : new String[]{"python", "python3", "py"}) {
            try {
                Process p = new ProcessBuilder(exe, "--version").redirectErrorStream(true).start();
                if (p.waitFor() == 0) {
                    String v = new String(p.getInputStream().readAllBytes()).trim();
                    pythonStatus = "found: " + (v.isEmpty() ? exe : v);
                    probed = true;
                    return;
                }
            } catch (Exception ignored) { /* try next */ }
        }
        pythonStatus = "not found — JVM surrogate only";
        probed = true;
    }

    @Override public String title()    { return cfg.technique.display; }
    @Override public String subtitle() { return cfg.technique.category + "  ·  " + cfg.technique.envBadge(); }

    @Override
    public String[] stats() {
        return new String[]{
            "data mode   " + cfg.technique.dataMode,
            "python      " + pythonStatus,
            "surrogate   " + surrogateLabel(),
            "score       " + core.getScore(),
            "drops       " + drops,
            "train       python -m " + trainModule(),
            "deploy      export ONNX -> OnnxPolicyRunner",
        };
    }

    private String surrogateLabel() {
        return switch (cfg.technique) {
            case DIFFUSION, FLOW -> "JVM generative (softmax)";
            case DECISION_TRANSFORMER, OFFLINE_RL -> "JVM return-conditioned";
            case MUZERO, DREAMER -> "JVM planning (MCTS)";
            default -> "JVM heuristic policy";
        };
    }

    private String trainModule() {
        return switch (cfg.technique) {
            case PPO    -> "suika.train_ppo";
            case DQN    -> "suika.train_dqn";
            case SAC    -> "suika.train_sac";
            case DIFFUSION -> "suika.diffusion_policy";
            case FLOW   -> "suika.flow_matching";
            case DECISION_TRANSFORMER -> "suika.decision_transformer";
            case OFFLINE_RL -> "suika.offline_rl";
            case GAIL   -> "suika.gail";
            case MUZERO -> "suika.muzero";
            case DREAMER-> "suika.dreamer";
            default     -> "suika.train_ppo";
        };
    }

    @Override public String chart2Label() { return null; }
}
