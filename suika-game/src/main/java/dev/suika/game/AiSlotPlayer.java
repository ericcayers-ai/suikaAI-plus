package dev.suika.game;

import dev.suika.ai.AgentPlugin;
import dev.suika.ai.MlpPolicy;
import dev.suika.ai.NeuralAgent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bridges {@link ModelSlots} (the on-disk save format) to a ready-to-play
 * {@link AgentPlugin} for any {@link AiTechnique} — the piece that lets RT Lab load
 * "technique X, slot N" and hand back something it can call
 * {@code selectAction(GameState, ActionSpec)} on immediately.
 *
 * <p>Every technique has SOME state worth saving into its 3 slots: Evolution/Imitation
 * (NEUROEVO, CMA-ES, PBT, BC, DAgger) have real trained {@link MlpPolicy} weights;
 * PPO (and other Python-exported policies) play from {@code model.onnx} via
 * {@link OnnxAgent} with no Python at play time; everything else (planning, baselines,
 * JVM surrogates) is fully determined by technique + hyperparameters via
 * {@link ModelSlots#saveConfig}. Loading a slot reconstructs a working agent — this
 * class is the one place that knows which path a given technique takes.
 */
final class AiSlotPlayer {

    private AiSlotPlayer() {}

    /** True for technique families with a trained {@link MlpPolicy} to save — every
     *  other technique's "save" is its hyperparameters, not weights. */
    static boolean isWeightBearing(AiTechnique t) {
        return t.family == AiTechnique.Family.EVOLUTION
                || t.family == AiTechnique.Family.IMITATION
                || t.family == AiTechnique.Family.DEEP_RL;
    }

    /** Techniques that can play from a slot {@code model.onnx} without Python. */
    static boolean isOnnxPlayable(AiTechnique t) {
        return t == AiTechnique.PPO
                || t == AiTechnique.BC
                || t == AiTechnique.DAGGER
                || t.family == AiTechnique.Family.EVOLUTION
                || t.family == AiTechnique.Family.DEEP_RL;
    }

    static boolean hasSave(AiTechnique t, int slot) {
        return ModelSlots.info(t.id, slot).present() || ModelSlots.hasOnnx(t.id, slot);
    }

    /** Builds a ready-to-play agent from a saved slot, or {@code null} if the slot is
     *  empty or unreadable (architecture mismatch, corrupt file, etc). */
    static AgentPlugin load(AiTechnique t, int slot) {
        PlaygroundConfig cfg = new PlaygroundConfig();
        cfg.selectDefaultsFor(t);

        // Prefer ONNX Runtime when model.onnx is present — no Python at play time.
        if (isOnnxPlayable(t) && ModelSlots.hasOnnx(t.id, slot)) {
            OnnxAgent onnx = ModelSlots.tryLoadOnnxAgent(t.id, slot, cfg.actionBins);
            if (onnx != null) return onnx;
            // Honest fallthrough: ONNX present but unloadable → try classic weights / config.
        }

        if (isWeightBearing(t)) {
            MlpPolicy policy = ModelSlots.newCompatiblePolicy();
            if (!ModelSlots.load(t.id, slot, policy)) return null;
            // Play a saved trained net on the GPU when GPU inference is active (load-once /
            // infer-many path) — GpuNeuralAgent falls straight back to the exact JVM
            // forward pass if the bridge can't start, so this never breaks playback.
            return GpuProbe.gpuInferenceActive() ? new GpuNeuralAgent(policy) : new NeuralAgent(policy);
        }
        ModelSlots.ConfigSlot saved = ModelSlots.loadConfig(t.id, slot);
        if (saved == null) {
            // ONNX-only PPO slot may lack a config/weights manifest — still playable above;
            // if ORT failed, surface null rather than a silent heuristic.
            return null;
        }
        applyHyperparams(cfg, saved.params());
        AgentPlugin agent = Agents.build(cfg);
        // Learning ensembles saved their live trust statistics into the same param
        // map — restore them so a loaded save resumes with its accumulated learning.
        if (agent instanceof EnsembleAgents.HasLearnedState h) h.importLearnedState(saved.params());
        return agent;
    }

    static void save(AiTechnique t, int slot, MlpPolicy policy, PlaygroundConfig cfg, double score) {
        save(t, slot, policy, cfg, score, Map.of());
    }

    /** Persists the technique's current state into a slot: real weights when
     *  {@code policy} is non-null and the technique is weight-bearing, otherwise the
     *  current hyperparameters from {@code cfg} merged with any {@code learnedState}
     *  (an ensemble's trust weights / bandit pulls — see
     *  {@link EnsembleAgents.HasLearnedState}). */
    static void save(AiTechnique t, int slot, MlpPolicy policy, PlaygroundConfig cfg, double score,
                     Map<String, Double> learnedState) {
        if (isWeightBearing(t) && policy != null) {
            ModelSlots.save(t.id, slot, policy, score);
        } else {
            Map<String, Double> params = hyperparamsOf(cfg);
            params.putAll(learnedState);
            ModelSlots.saveConfig(t.id, slot, params, score);
        }
    }

    private static Map<String, Double> hyperparamsOf(PlaygroundConfig cfg) {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("rollouts", (double) cfg.rollouts);
        m.put("populationSize", (double) cfg.populationSize);
        m.put("mutationSigma", cfg.mutationSigma);
        m.put("targetReturn", cfg.targetReturn);
        m.put("learningRate", cfg.learningRate);
        m.put("actionBins", (double) cfg.actionBins);
        // v0.12 additions — ensemble customization + evolution selection math.
        m.put("ensembleDonorIndex", (double) cfg.ensembleDonorIndex);
        m.put("ensembleDonorSlot", (double) cfg.ensembleDonorSlot);
        m.put("netWeightIndex", (double) cfg.netWeightIndex);
        m.put("tieThresholdIndex", (double) cfg.tieThresholdIndex);
        m.put("ucbCIndex", (double) cfg.ucbCIndex);
        m.put("adaptLrIndex", (double) cfg.adaptLrIndex);
        m.put("selectionIndex", (double) cfg.selectionIndex);
        m.put("crossover", cfg.crossover ? 1.0 : 0.0);
        m.put("sigmaAnneal", cfg.sigmaAnneal ? 1.0 : 0.0);
        m.put("tensorboardDetailed", cfg.tensorboardDetailed ? 1.0 : 0.0);
        m.put("autoDrop", cfg.autoDrop ? 1.0 : 0.0);
        return m;
    }

    /** Applies previously-saved hyperparameters back onto a freshly-defaulted config
     *  (missing keys keep whatever {@link PlaygroundConfig#selectDefaultsFor} chose). */
    static void applyHyperparams(PlaygroundConfig cfg, Map<String, Double> p) {
        if (p.containsKey("rollouts"))       cfg.rollouts       = p.get("rollouts").intValue();
        if (p.containsKey("populationSize")) cfg.populationSize = p.get("populationSize").intValue();
        if (p.containsKey("mutationSigma"))  cfg.mutationSigma  = p.get("mutationSigma");
        if (p.containsKey("targetReturn"))   cfg.targetReturn   = p.get("targetReturn");
        if (p.containsKey("learningRate"))   cfg.learningRate   = p.get("learningRate");
        if (p.containsKey("actionBins"))     cfg.actionBins     = p.get("actionBins").intValue();
        if (p.containsKey("ensembleDonorIndex")) cfg.ensembleDonorIndex =
                clampIdx(p.get("ensembleDonorIndex"), PlaygroundConfig.ENSEMBLE_DONORS.length);
        if (p.containsKey("ensembleDonorSlot")) cfg.ensembleDonorSlot =
                clampIdx(p.get("ensembleDonorSlot"), ModelSlots.SLOT_COUNT + 1);
        if (p.containsKey("netWeightIndex")) cfg.netWeightIndex =
                clampIdx(p.get("netWeightIndex"), PlaygroundConfig.NET_WEIGHT_OPTIONS.length);
        if (p.containsKey("tieThresholdIndex")) cfg.tieThresholdIndex =
                clampIdx(p.get("tieThresholdIndex"), PlaygroundConfig.TIE_THRESHOLD_OPTIONS.length);
        if (p.containsKey("ucbCIndex")) cfg.ucbCIndex =
                clampIdx(p.get("ucbCIndex"), PlaygroundConfig.UCB_C_OPTIONS.length);
        if (p.containsKey("adaptLrIndex")) cfg.adaptLrIndex =
                clampIdx(p.get("adaptLrIndex"), PlaygroundConfig.ADAPT_LR_OPTIONS.length);
        if (p.containsKey("selectionIndex")) cfg.selectionIndex =
                clampIdx(p.get("selectionIndex"), dev.suika.ai.GeneticTrainer.Selection.values().length);
        if (p.containsKey("crossover"))   cfg.crossover   = p.get("crossover") > 0.5;
        if (p.containsKey("sigmaAnneal")) cfg.sigmaAnneal = p.get("sigmaAnneal") > 0.5;
        if (p.containsKey("tensorboardDetailed")) cfg.tensorboardDetailed = p.get("tensorboardDetailed") > 0.5;
        if (p.containsKey("autoDrop")) cfg.autoDrop = p.get("autoDrop") > 0.5;
    }

    private static int clampIdx(double v, int len) {
        return Math.max(0, Math.min(len - 1, (int) Math.round(v)));
    }
}
