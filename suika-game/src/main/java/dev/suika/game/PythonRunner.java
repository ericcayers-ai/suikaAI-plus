package dev.suika.game;

import dev.suika.ai.ActionSpec;
import dev.suika.ai.AgentPlugin;
import dev.suika.core.GameCore;
import dev.suika.core.GameState;
import dev.suika.core.PhysicsConfig;

/**
 * Control center for techniques whose full training lives in Python (PPO/DQN/SAC,
 * Diffusion/Flow, Decision Transformer, Offline RL, GAIL, MuZero, Dreamer).
 *
 * <p>It is honest about the environment: a background probe detects whether the managed
 * {@link PythonSetup} virtual-env (with PyTorch + CUDA) is installed, and whether a GPU
 * is visible to torch. The panel surfaces that status plus the exact training command.
 * Meanwhile a JVM-native surrogate plays live so the board and score graph are always
 * populated. For the "Racing Self-Play" technique a second rival board runs alongside.
 */
public final class PythonRunner extends AgentRunner {

    private volatile String pythonStatus = "probing python...";
    private volatile String torchStatus  = "torch: checking...";
    private volatile String gpuStatus    = "gpu: checking...";
    private volatile boolean probed = false;

    // Racing Self-Play: a second independent game ("rival") playing the same surrogate.
    private GameCore rival;
    private double rivalAccum;
    private float  rivalTimer;

    public PythonRunner(SuikaGame game, PlaygroundConfig cfg) { super(game, cfg); }

    private boolean isSelfPlay() { return cfg.technique == AiTechnique.SELF_PLAY; }

    @Override
    public void start() {
        super.start();
        if (isSelfPlay()) { rival = new GameCore(seed + 777); rivalTimer = 0.3f; }
        Thread t = new Thread(this::probeEnvironment, "python-probe");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Detects the managed venv first (the one created by the Settings auto-installer),
     * then any system Python. When the venv is present, queries torch for CUDA so the
     * offline / Python techniques are visibly "linked up" to the downloaded GPU stack.
     */
    private void probeEnvironment() {
        if (PythonSetup.isReady()) {
            String py = PythonSetup.venvPython().toString();
            pythonStatus = "venv ~/.suikai/venv";
            // Ask torch directly whether it imported and whether CUDA is available.
            String[] info = queryTorch(py);
            torchStatus = info[0];
            gpuStatus   = info[1];
            probed = true;
            return;
        }
        // No managed venv — fall back to detecting a system interpreter.
        for (String exe : new String[]{"python", "python3", "py"}) {
            try {
                Process p = new ProcessBuilder(exe, "--version").redirectErrorStream(true).start();
                if (p.waitFor() == 0) {
                    String v = new String(p.getInputStream().readAllBytes()).trim();
                    pythonStatus = "system: " + (v.isEmpty() ? exe : v);
                    torchStatus  = "torch: run Settings → AI ENV setup";
                    gpuStatus    = "gpu: n/a (no venv)";
                    probed = true;
                    return;
                }
            } catch (Exception ignored) { /* try next */ }
        }
        pythonStatus = "not found — JVM surrogate only";
        torchStatus  = "torch: install Python 3 first";
        gpuStatus    = "gpu: n/a";
        probed = true;
    }

    /** Returns {torchLine, gpuLine} by importing torch in the venv interpreter. */
    private String[] queryTorch(String python) {
        try {
            Process p = new ProcessBuilder(python, "-c",
                    "import torch,sys;" +
                    "print('torch:'+torch.__version__);" +
                    "print('cuda:'+str(torch.cuda.is_available())+':'+" +
                    "(torch.cuda.get_device_name(0) if torch.cuda.is_available() else 'cpu'))")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            int code = p.waitFor();
            if (code != 0 || out.isEmpty()) return new String[]{"torch: not installed yet", "gpu: n/a"};
            String ver = "torch installed", gpu = "gpu: cpu only";
            for (String line : out.split("\\R")) {
                if (line.startsWith("torch:")) ver = line.replace("torch:", "torch v");
                if (line.startsWith("cuda:")) {
                    String[] parts = line.split(":");
                    boolean avail = parts.length > 1 && parts[1].equals("True");
                    String dev = parts.length > 2 ? parts[2] : "cpu";
                    gpu = avail ? "gpu: " + dev : "gpu: cpu only (CUDA n/a)";
                }
            }
            return new String[]{ver, gpu};
        } catch (Exception e) {
            return new String[]{"torch: probe failed", "gpu: n/a"};
        }
    }

    @Override
    protected void onUpdate(float dt) {
        super.onUpdate(dt);
        if (rival == null) return;
        // Step rival physics in lock-step with the playback speed.
        rivalAccum += dt * speed;
        while (rivalAccum >= PhysicsConfig.FIXED_DT) {
            rival.tick();
            rivalAccum -= PhysicsConfig.FIXED_DT;
        }
        if (rival.isGameOver()) { rival = new GameCore(seed + 777 + drops); rivalTimer = 0.3f; return; }
        rivalTimer -= dt;
        if (rivalTimer <= 0f && agent() != null) {
            ActionSpec spec = ActionSpec.discrete(cfg.actionBins);
            AgentPlugin a = agent();
            Object act = a.selectAction(rival.snapshot(), spec);
            double x = spec.toDropX(act, PhysicsConfig.DROP_X_MIN, PhysicsConfig.DROP_X_MAX);
            rival.spawnDrop(x);
            rivalTimer = baseDelay() * 1.1f;
        }
    }

    @Override
    public GameState[] multiStates() {
        if (isSelfPlay() && rival != null) return new GameState[]{ board(), rival.getState() };
        return new GameState[]{ board() };
    }

    @Override
    public String[] multiLabels() {
        if (isSelfPlay() && rival != null)
            return new String[]{ "AGENT  ·  " + core.getScore(), "RIVAL  ·  " + rival.getScore() };
        return new String[0];
    }

    @Override public String title()    { return cfg.technique.display; }
    @Override public String subtitle() { return cfg.technique.category + "  ·  " + cfg.technique.envBadge(); }

    @Override
    public String[] stats() {
        return new String[]{
            "data mode   " + cfg.technique.dataMode,
            "python      " + pythonStatus,
            torchStatus,
            gpuStatus,
            "surrogate   " + surrogateLabel(),
            "score       " + core.getScore(),
            "drops       " + drops,
            "speed       " + cfg.speedLabel(),
        };
    }

    @Override
    public String[] extendedStats() {
        return new String[]{
            "train       python -m " + trainModule(),
            "deploy      export ONNX -> OnnxPolicyRunner",
            PythonSetup.isReady()
                ? "env ready   GPU stack linked"
                : "env setup   Settings -> AI ENVIRONMENT",
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
