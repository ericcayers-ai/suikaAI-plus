package dev.suika.game;

/**
 * Control center for techniques whose full training lives in Python (PPO, MuZero,
 * Decision Transformer).
 *
 * <p>It is honest about the environment: a background probe detects whether the managed
 * {@link PythonSetup} virtual-env (with PyTorch + CUDA) is installed, and whether a GPU
 * is visible to torch. The panel surfaces that status plus the exact training command.
 * Meanwhile a JVM-native surrogate plays live so the board and score graph are always
 * populated.
 */
public final class PythonRunner extends AgentRunner {

    private volatile String pythonStatus = "probing python...";
    private volatile String torchStatus  = "torch: checking...";
    private volatile String gpuStatus    = "gpu: checking...";

    public PythonRunner(SuikaGame game, PlaygroundConfig cfg) { super(game, cfg); }

    @Override
    public void start() {
        super.start();
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
            String[] info = queryTorch(py);
            torchStatus = info[0];
            gpuStatus   = info[1];
            return;
        }
        // No managed venv — fall back to detecting a system interpreter.
        for (String exe : new String[]{"python", "python3", "py"}) {
            try {
                Process p = new ProcessBuilder(exe, "--version").redirectErrorStream(true).start();
                if (p.waitFor() == 0) {
                    String v = new String(p.getInputStream().readAllBytes()).trim();
                    pythonStatus = "system: " + (v.isEmpty() ? exe : v);
                    torchStatus  = "torch: run Settings -> AI ENV setup";
                    gpuStatus    = "gpu: n/a (no venv)";
                    return;
                }
            } catch (Exception ignored) { /* try next */ }
        }
        pythonStatus = "not found — JVM surrogate only";
        torchStatus  = "torch: install Python 3 first";
        gpuStatus    = "gpu: n/a";
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

    @Override public String title()    { return cfg.technique.display; }
    @Override public String subtitle() { return cfg.technique.category + "  ·  " + cfg.technique.envBadge(); }

    @Override
    public String[] stats() {
        java.util.List<String> s = new java.util.ArrayList<>();
        s.add("data mode   " + cfg.technique.dataMode);
        s.add("python      " + pythonStatus);
        s.add(torchStatus);
        s.add(gpuStatus);
        s.add("surrogate   " + surrogateLabel());
        s.add("drops       " + drops);
        s.add("speed       " + cfg.speedLabel());
        // Only PPO's training command actually has a real --n-envs/--device knob today —
        // see AiTechnique.gpuCapableTraining().
        if (cfg.technique.parallel) s.add("compute     " + cfg.parallelismLabel());
        return s.toArray(new String[0]);
    }

    /** PPO is the one technique whose shown command has real, existing flags this config drives. */
    private String ppoTrainFlags() {
        int envs = Math.max(1, Math.min(64, cfg.evalThreads()));
        boolean gpu = cfg.parallelism == 0 && Boolean.TRUE.equals(GpuProbe.available());
        String flags = " --n-envs " + envs + " --device " + (gpu ? "cuda" : "cpu");
        // Only meaningful when actually training on the GPU — see train_ppo.py's
        // --gpu-mem-fraction (a real, working flag; Settings -> AI ENVIRONMENT slider).
        if (gpu && game.settings.gpuUtilPercent < 100) {
            flags += String.format(" --gpu-mem-fraction %.2f", game.settings.gpuUtilPercent / 100.0);
        }
        flags += tensorboardFlags();
        return flags;
    }

    /** Shared TensorBoard flags for every technique with a real, runnable training
     *  script — see {@link AiTechnique#supportsTensorboard()}. The log directory is
     *  fixed (~/.suikai/tb_logs/<id>) so {@link TensorboardLauncher} can always find it
     *  regardless of what {@code --out} the user chose for the model checkpoint itself. */
    private String tensorboardFlags() {
        if (!cfg.technique.supportsTensorboard()) return "";
        String flags = " --tb-logdir " + TensorboardLauncher.logDir(cfg.technique.id);
        if (cfg.tensorboardDetailed) flags += " --tb-detailed";
        return flags;
    }

    // The landscape panel scrolls with the mouse wheel once stats() + extendedStats()
    // exceed its visible height, so there's no hard line cap — see ControlCenterScreen's
    // drawPanelText()/maxStatsScroll().
    @Override
    public String[] extendedStats() {
        java.util.List<String> s = new java.util.ArrayList<>();
        boolean ppo = cfg.technique == AiTechnique.PPO;
        // Every Python technique's train command shows --device cuda when "Prefer GPU" is
        // on and a CUDA device was detected (PPO also carries its --n-envs/--gpu-mem flags;
        // both PPO and Decision Transformer carry --tb-logdir/--tb-detailed — see
        // tensorboardFlags()).
        String deviceFlag = (GpuProbe.gpuUsableFor(cfg.technique) ? " --device cuda" : "") + tensorboardFlags();
        s.add("train       python -m " + trainModule() + (ppo ? ppoTrainFlags() : deviceFlag));
        if (!ppo && GpuProbe.gpuUsableFor(cfg.technique)) s.add("accel       GPU (CUDA) — Prefer GPU is on");
        if (cfg.technique.supportsTensorboard()) {
            s.add("tb logdir   " + TensorboardLauncher.logDir(cfg.technique.id));
            s.add("tb button   SETUP -> TensorBoard toggle + OPEN (below)");
        }
        s.add("deploy      model.onnx → OnnxPolicyRunner (ORT, no Python at play)");
        s.add(PythonSetup.isReady()
                ? "env ready   GPU stack linked"
                : "env setup   Settings -> AI ENVIRONMENT");
        s.add("doing now   " + cfg.technique.liveHint());
        s.add("tendency    " + tendencyLabel());

        switch (cfg.technique) {
            case DECISION_TRANSFORMER -> {
                s.add("target return " + (int) cfg.targetReturn + "  ·  score you're asking it to aim for");
                s.add("prompt      \"given logged games reaching score X, predict the");
                s.add("            drop that continues that\" — sequence modeling");
                s.add("higher target -> more aggressive play; lower -> more conservative");
                s.add("            (cycle Target return in SETUP, compare the drops)");
                s.add("real model  a trained transformer replaces this JVM heuristic");
                s.add("            once you run the training command above");
            }
            case MUZERO -> {
                s.add("idea        learns a compact model of \"what happens next\", then");
                s.add("            plans inside it with tree search — never told the");
                s.add("            real physics rules");
                s.add("surrogate   this JVM view plans with real MCTS over the actual");
                s.add("            physics (perfect model) standing in for the learned one");
            }
            default -> {
                s.add("on-policy   plays, then nudges behaviour toward whatever just");
                s.add("            earned more reward, averaged over practice games");
            }
        }
        return s.toArray(new String[0]);
    }

    private String surrogateLabel() {
        return switch (cfg.technique) {
            case DECISION_TRANSFORMER -> "JVM return-conditioned";
            case MUZERO               -> "JVM planning (MCTS)";
            default                   -> "JVM heuristic policy";
        };
    }

    private String trainModule() {
        return switch (cfg.technique) {
            case DECISION_TRANSFORMER -> "suika.decision_transformer";
            case MUZERO               -> "suika.muzero";
            default                   -> "suika.train_ppo";
        };
    }

    // chart2: per-game final-score history, same "gameScoreChart" AgentRunner already tracks.
    @Override public LiveChart chart2() { return gameScoreChart; }
    @Override public String    chart2Label() {
        return gameScoreChart.size() == 0 ? "game scores (game 1 in progress)"
                : "game scores  ·  last " + Math.round(gameScoreChart.latest());
    }
}
