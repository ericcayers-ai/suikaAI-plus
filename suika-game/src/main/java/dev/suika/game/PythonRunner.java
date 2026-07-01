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

    // chart3, technique-dependent: self-play's agent-vs-rival lead, or the
    // diffusion/flow generative agent's per-drop confidence. Sampled once per agent
    // drop (not every frame) so a 260-sample buffer covers real playing history.
    private final LiveChart leadChart       = new LiveChart(260);
    private final LiveChart confidenceChart = new LiveChart(260);
    private int lastChart3SampleDrops = -1;

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
        if (drops != lastChart3SampleDrops) {
            lastChart3SampleDrops = drops;
            if (isSelfPlay() && rival != null) {
                leadChart.add(core.getScore() - rival.getScore());
            } else if (agent() instanceof Agents.GenerativeAgent g && g.lastBin() >= 0) {
                double[][] hist = g.lastStepHistory();
                if (hist.length > 0) {
                    double[] finalDist = hist[hist.length - 1];
                    double sum = 0, max = 0;
                    for (double v : finalDist) { sum += v; max = Math.max(max, v); }
                    // Normalised so "confidence" reads as a 0-100% share of the final
                    // distribution's mass on its top pick, not a raw (usually <1) probability.
                    confidenceChart.add(sum > 1e-9 ? (float) (100.0 * max / sum) : 0f);
                }
            }
        }
        if (rival == null) return;
        // Step rival physics in lock-step with the playback speed (capped per frame
        // so extreme speeds don't freeze the UI).
        rivalAccum += Math.min(dt * speed, 4.0);
        int rsteps = 0;
        while (rivalAccum >= PhysicsConfig.FIXED_DT && rsteps < 240) {
            rival.tick();
            rivalAccum -= PhysicsConfig.FIXED_DT;
            rsteps++;
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
        if (isSelfPlay() && rival != null) {
            long delta = core.getScore() - rival.getScore();
            String lead = (delta >= 0 ? "+" : "") + delta;
            return new String[]{
                "AGENT  ·  " + core.getScore() + "  (" + lead + ")",
                "RIVAL  ·  " + rival.getScore(),
            };
        }
        return new String[0];
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
        // score is already shown in the chart-1 caption just above ("score · N") — skip
        // repeating it here so there's room for PPO's extra "compute" line without
        // crowding the panel's bottom edge.
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
        return flags;
    }

    // NOTE ON LINE BUDGET: the landscape panel background is a FIXED height — text
    // isn't clipped to it, so stats().length + extendedStats().length must stay at or
    // below ~22 total or later lines render below the panel. stats() here is up to 8
    // lines, and this header block is always 4, so keep each family's block to ~9 max.
    @Override
    public String[] extendedStats() {
        java.util.List<String> s = new java.util.ArrayList<>();
        boolean ppo = cfg.technique == AiTechnique.PPO;
        s.add("train       python -m " + trainModule() + (ppo ? ppoTrainFlags() : ""));
        s.add("deploy      export ONNX -> OnnxPolicyRunner");
        s.add(PythonSetup.isReady()
                ? "env ready   GPU stack linked"
                : "env setup   Settings -> AI ENVIRONMENT");
        s.add("doing now   " + cfg.technique.liveHint());

        if (agent() instanceof Agents.GenerativeAgent g) {
            boolean flow = cfg.technique == AiTechnique.FLOW;
            if (g.lastBin() >= 0) {
                s.add("last drop   col " + (g.lastBin() + 1) + "/" + g.lastBins()
                        + "  ·  refined over " + g.steps() + " steps");
            }
            s.add((flow ? "flow" : "denoise") + "     starts flat/uniform (\"pure noise\") over every column,");
            s.add("            blends toward a merge-potential score each step — structure");
            s.add("            emerges gradually, same shape as real diffusion/flow");
            s.add("bars below  the FINAL step's distribution (tallest = likeliest pick)");
            s.add(flow ? "why fewer   flow-matching's straighter paths need fewer steps"
                       : "why more    diffusion denoises in smaller, safer increments");
            s.add("real model  would replace the merge-potential score with learned");
            s.add("            structure from demonstrations, trained in Python");
        } else if (cfg.technique == AiTechnique.DECISION_TRANSFORMER || cfg.technique == AiTechnique.OFFLINE_RL) {
            boolean dt = cfg.technique == AiTechnique.DECISION_TRANSFORMER;
            s.add("target return " + (int) cfg.targetReturn + "  ·  score you're asking it to aim for");
            s.add(dt ? "prompt      \"given logged games reaching score X, predict the"
                     : "training    learns a value function from a fixed logged batch —");
            s.add(dt ? "            drop that continues that\" — sequence modeling"
                     : "            never touches the live game (that's \"offline\")");
            s.add("higher target -> more aggressive play; lower -> more conservative");
            s.add("            (cycle Target return in SETUP, compare the drops)");
            s.add("real model  a trained transformer/value-net replaces this JVM");
            s.add("            heuristic once you run the training command above");
        } else if (isSelfPlay()) {
            s.add("setup       AGENT and RIVAL get the identical fruit sequence —");
            s.add("            only the drops differ");
            s.add("reward      relative: beating your own rival, not an absolute score");
            s.add("why it works a copy of yourself is always exactly as good as you were");
            s.add("            a moment ago, so the bar keeps rising as both improve");
            s.add("real model  both sides would run the same learned Python policy;");
            s.add("            here both play the identical JVM surrogate for display");
        } else if (cfg.technique == AiTechnique.MUZERO || cfg.technique == AiTechnique.DREAMER) {
            boolean muzero = cfg.technique == AiTechnique.MUZERO;
            s.add(muzero ? "idea        learns a compact model of \"what happens next\", then"
                         : "idea        learns to predict future boards, then practises");
            s.add(muzero ? "            plans inside it with tree search — never told the"
                         : "            (\"dreams\") entirely inside that learned model instead");
            s.add(muzero ? "            real physics rules" : "            of the real, slower game");
            s.add("surrogate   this JVM view plans with real MCTS over the actual");
            s.add("            physics (perfect model) standing in for the learned one");
        } else {
            s.add(switch (cfg.technique) {
                case PPO  -> "on-policy   plays, then nudges behaviour toward whatever just";
                case DQN  -> "off-policy  learns a per-column value estimate from a replay";
                case SAC  -> "actor-critic balances reward against staying exploratory";
                case GAIL -> "adversarial a discriminator tries to tell your demos apart";
                default -> "learns a policy from played games";
            });
            s.add(switch (cfg.technique) {
                case PPO  -> "            earned more reward, averaged over practice games";
                case DQN  -> "            buffer of past drops, re-learned from repeatedly";
                case SAC  -> "            (entropy bonus) so it doesn't collapse too early";
                case GAIL -> "            from the agent's — learns to fool it, recovering";
                default -> "";
            });
            if (cfg.technique == AiTechnique.GAIL) s.add("            the reward that would explain your demonstrations");
        }
        return s.toArray(new String[0]);
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

    // chart2: per-game final-score history, same "gameScoreChart" AgentRunner already
    // tracks (and PlanningRunner already surfaces) — was previously collected but never
    // shown for any Python-family technique.
    @Override public LiveChart chart2() { return gameScoreChart; }
    @Override public String    chart2Label() {
        return gameScoreChart.size() == 0 ? "game scores (game 1 in progress)"
                : "game scores  ·  last " + Math.round(gameScoreChart.latest());
    }

    // chart3: technique-specific — self-play's live lead, or the generative
    // (diffusion/flow) agent's per-drop confidence. Everything else has no meaningful
    // third series (a static JVM heuristic has nothing new to chart) so stays unused.
    @Override public LiveChart chart3() {
        if (isSelfPlay()) return leadChart;
        if (cfg.technique == AiTechnique.DIFFUSION || cfg.technique == AiTechnique.FLOW) return confidenceChart;
        return null;
    }

    @Override public String chart3Label() {
        if (isSelfPlay()) {
            return leadChart.size() == 0 ? "lead (you − rival)" : "lead (you − rival)  ·  " + Math.round(leadChart.latest());
        }
        if (cfg.technique == AiTechnique.DIFFUSION || cfg.technique == AiTechnique.FLOW) {
            return confidenceChart.size() == 0 ? "pick confidence"
                    : "pick confidence  ·  " + Math.round(confidenceChart.latest()) + "%";
        }
        return null;
    }
}
