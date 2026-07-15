package dev.suika.bridge;

/**
 * The no-Python deployment seam (ROADMAP §II.4): run an exported policy on the JVM.
 *
 * <p>Workflow: train in Python → export to ONNX (see {@code OnnxExportConfig} in
 * suika-app) → load here via ONNX Runtime → run inference in the shipped game with
 * no Python dependency for the end-user.
 *
 * <p>{@link OrtOnnxPolicyRunner} is the shipping implementation (lazy native load,
 * action-head shape checks, CUDA→CPU fallback). {@link StubOnnxPolicyRunner} remains
 * for dependency-free headless tests when natives are unavailable.
 */
public interface OnnxPolicyRunner extends AutoCloseable {

    /**
     * Preferred factory: real ORT when natives load, otherwise the deterministic stub.
     *
     * @param numActions expected {@code policy_logits} width (usually 32 bins)
     */
    static OnnxPolicyRunner create(int numActions) {
        return OrtOnnxPolicyRunner.create(numActions);
    }

    /** Policy network output: per-action logits and an optional state value. */
    record Output(float[] policyLogits, float value) {

        /** The greedy action: argmax over the policy logits. */
        public int argmaxAction() {
            int best = 0;
            for (int i = 1; i < policyLogits.length; i++) {
                if (policyLogits[i] > policyLogits[best]) best = i;
            }
            return best;
        }
    }

    /** Load the model from {@link BridgeConfig#modelPath()}. */
    void load(BridgeConfig config);

    /** True once a model is loaded and ready for inference. */
    boolean isLoaded();

    /** Run a forward pass on one observation. */
    Output run(float[] observation);

    @Override
    void close();

    /**
     * Deterministic stub: emits uniform logits of the requested width, so
     * {@link Output#argmaxAction()} is stable and the deploy plumbing is testable.
     */
    final class StubOnnxPolicyRunner implements OnnxPolicyRunner {
        private final int numActions;
        private boolean   loaded = false;

        public StubOnnxPolicyRunner(int numActions) { this.numActions = numActions; }

        @Override public void load(BridgeConfig config) { loaded = true; }
        @Override public boolean isLoaded()             { return loaded; }

        @Override
        public Output run(float[] observation) {
            if (!loaded) throw new IllegalStateException("Model not loaded; call load() first");
            return new Output(new float[numActions], 0.0f);
        }

        @Override public void close() { loaded = false; }
    }
}
