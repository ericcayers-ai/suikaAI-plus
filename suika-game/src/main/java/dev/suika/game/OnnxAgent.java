package dev.suika.game;

import dev.suika.ai.ActionSpec;
import dev.suika.ai.AgentPlugin;
import dev.suika.bridge.BridgeConfig;
import dev.suika.bridge.OnnxPolicyRunner;
import dev.suika.bridge.OrtOnnxPolicyRunner;
import dev.suika.core.GameState;
import dev.suika.env.StateObservationEncoder;

import java.nio.file.Path;

/**
 * {@link AgentPlugin} backed by an exported ONNX policy — the no-Python play path
 * for PPO / BC (and any other technique that drops {@code model.onnx} in a slot).
 */
public final class OnnxAgent implements AgentPlugin, AutoCloseable {

    private final OnnxPolicyRunner runner;
    private final String techniqueId;
    private final StateObservationEncoder encoder = new StateObservationEncoder();
    private final boolean ownsRunner;

    public OnnxAgent(OnnxPolicyRunner runner, String techniqueId, boolean ownsRunner) {
        this.runner = runner;
        this.techniqueId = techniqueId == null ? "onnx" : techniqueId;
        this.ownsRunner = ownsRunner;
        if (!runner.isLoaded()) {
            throw new IllegalStateException("OnnxPolicyRunner must be loaded before constructing OnnxAgent");
        }
    }

    /**
     * Load {@code modelPath} with ORT (or the stub when natives are missing).
     * Returns {@code null} when the file cannot be loaded or the action head mismatches.
     */
    public static OnnxAgent tryLoad(Path modelPath, int actionBins, String techniqueId) {
        if (modelPath == null) return null;
        OnnxPolicyRunner runner = OnnxPolicyRunner.create(actionBins);
        try {
            runner.load(BridgeConfig.onnx(modelPath.toAbsolutePath().toString()));
            return new OnnxAgent(runner, techniqueId, true);
        } catch (RuntimeException e) {
            try { runner.close(); } catch (Exception ignored) {}
            return null;
        }
    }

    /** True when this agent is using real ORT (not the uniform-logit stub). */
    public boolean usingOrt() {
        return runner instanceof OrtOnnxPolicyRunner;
    }

    public OnnxPolicyRunner runner() { return runner; }

    @Override public String id() { return "onnx-" + techniqueId; }
    @Override public String displayName() { return "ONNX (" + techniqueId + ")"; }

    @Override
    public Object selectAction(GameState state, ActionSpec spec) {
        float[] obs = encoder.encode(state);
        OnnxPolicyRunner.Output out = runner.run(obs);
        if (spec.discrete()) {
            int bins = spec.bins();
            float[] logits = out.policyLogits();
            int best = 0;
            int limit = Math.min(bins, logits.length);
            for (int i = 1; i < limit; i++) {
                if (logits[i] > logits[best]) best = i;
            }
            return best;
        }
        // Continuous: map greedy bin centre or first logit through tanh.
        if (out.policyLogits().length == 1) {
            return Math.tanh(out.policyLogits()[0]);
        }
        int a = out.argmaxAction();
        int bins = Math.max(2, out.policyLogits().length);
        return -1.0 + 2.0 * (a / (double) (bins - 1));
    }

    @Override
    public void close() {
        if (ownsRunner) runner.close();
    }
}
