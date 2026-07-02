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
 * everything else (planning, baselines, and the JVM surrogates that stand in for
 * Python-family techniques) is fully determined by technique + hyperparameters, so a
 * "save" there is that pair via {@link ModelSlots#saveConfig}. Either way, loading a
 * slot reconstructs a working agent — this class is the one place that knows which
 * path a given technique takes, so {@link ControlCenterScreen} and the RT Lab launch
 * flow don't have to duplicate that branch.
 */
final class AiSlotPlayer {

    private AiSlotPlayer() {}

    /** True for technique families with a trained {@link MlpPolicy} to save — every
     *  other technique's "save" is its hyperparameters, not weights. */
    static boolean isWeightBearing(AiTechnique t) {
        return t.family == AiTechnique.Family.EVOLUTION || t.family == AiTechnique.Family.IMITATION;
    }

    static boolean hasSave(AiTechnique t, int slot) {
        return ModelSlots.info(t.id, slot).present();
    }

    /** Builds a ready-to-play agent from a saved slot, or {@code null} if the slot is
     *  empty or unreadable (architecture mismatch, corrupt file, etc). */
    static AgentPlugin load(AiTechnique t, int slot) {
        PlaygroundConfig cfg = new PlaygroundConfig();
        cfg.selectDefaultsFor(t);
        if (isWeightBearing(t)) {
            MlpPolicy policy = ModelSlots.newCompatiblePolicy();
            if (!ModelSlots.load(t.id, slot, policy)) return null;
            return new NeuralAgent(policy);
        }
        ModelSlots.ConfigSlot saved = ModelSlots.loadConfig(t.id, slot);
        if (saved == null) return null;
        applyHyperparams(cfg, saved.params());
        return Agents.build(cfg);
    }

    /** Persists the technique's current state into a slot: real weights when
     *  {@code policy} is non-null and the technique is weight-bearing, otherwise the
     *  current hyperparameters from {@code cfg}. */
    static void save(AiTechnique t, int slot, MlpPolicy policy, PlaygroundConfig cfg, double score) {
        if (isWeightBearing(t) && policy != null) {
            ModelSlots.save(t.id, slot, policy, score);
        } else {
            ModelSlots.saveConfig(t.id, slot, hyperparamsOf(cfg), score);
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
    }
}
