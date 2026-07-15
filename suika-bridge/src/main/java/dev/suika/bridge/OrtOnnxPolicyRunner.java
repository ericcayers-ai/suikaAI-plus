package dev.suika.bridge;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ONNX Runtime implementation of {@link OnnxPolicyRunner}.
 *
 * <p>Natives are loaded lazily on the first {@link #load(BridgeConfig)} call.
 * Session creation prefers CUDA when available and falls back to CPU. The action
 * head's last dimension must match the expected bin count (PPO/BC export contract:
 * {@code policy_logits} of shape {@code [batch, bins]}).
 */
public final class OrtOnnxPolicyRunner implements OnnxPolicyRunner {

    private final int expectedActions;
    private OrtEnvironment env;
    private OrtSession session;
    private String inputName;
    private String outputName;
    private boolean loaded;
    private String backendUsed = "none";

    public OrtOnnxPolicyRunner(int expectedActions) {
        if (expectedActions < 1) {
            throw new IllegalArgumentException("expectedActions must be >= 1");
        }
        this.expectedActions = expectedActions;
    }

    /** Probe whether ONNX Runtime natives can initialize in this JVM. */
    public static boolean nativesAvailable() {
        try {
            return OrtEnvironment.getEnvironment() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Prefer a real ORT runner; fall back to {@link OnnxPolicyRunner.StubOnnxPolicyRunner}
     * when natives are missing (tests / restricted environments).
     */
    public static OnnxPolicyRunner create(int expectedActions) {
        if (nativesAvailable()) {
            return new OrtOnnxPolicyRunner(expectedActions);
        }
        return new OnnxPolicyRunner.StubOnnxPolicyRunner(expectedActions);
    }

    public int expectedActions() { return expectedActions; }

    /** {@code cpu}, {@code cuda}, or {@code none} after load attempt. */
    public String backendUsed() { return backendUsed; }

    @Override
    public void load(BridgeConfig config) {
        if (config.modelPath() == null || config.modelPath().isBlank()) {
            throw new IllegalArgumentException("BridgeConfig.modelPath is required for ORT load");
        }
        Path path = Path.of(config.modelPath());
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("ONNX model not found: " + path);
        }
        closeSession();
        try {
            env = OrtEnvironment.getEnvironment();
            session = openSession(env, path);
            resolveIONames(session);
            validateActionHead(session, expectedActions);
            loaded = true;
        } catch (OrtException e) {
            closeSession();
            throw new IllegalStateException("Failed to load ONNX model: " + path + " — " + e.getMessage(), e);
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            closeSession();
            throw new IllegalStateException(
                    "ONNX Runtime natives unavailable; cannot load " + path, e);
        }
    }

    private OrtSession openSession(OrtEnvironment environment, Path path) throws OrtException {
        try {
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            try {
                opts.addCUDA(0);
                OrtSession s = environment.createSession(path.toString(), opts);
                backendUsed = "cuda";
                return s;
            } catch (OrtException cudaFail) {
                opts.close();
            }
        } catch (Throwable ignored) {
            // CUDA EP may be absent on the CPU-only ORT jar.
        }
        OrtSession.SessionOptions cpu = new OrtSession.SessionOptions();
        OrtSession s = environment.createSession(path.toString(), cpu);
        backendUsed = "cpu";
        return s;
    }

    private void resolveIONames(OrtSession s) throws OrtException {
        inputName = s.getInputNames().iterator().next();
        if (s.getOutputNames().contains("policy_logits")) {
            outputName = "policy_logits";
        } else {
            outputName = s.getOutputNames().iterator().next();
        }
    }

    /**
     * Ensures the policy head's last dimension equals {@code expectedActions}.
     * Dynamic batch ({@code -1}) on dim 0 is allowed.
     */
    static void validateActionHeadShapes(long[] shape, int expectedActions) {
        if (shape == null || shape.length < 1) {
            throw new IllegalStateException("ONNX policy output has empty shape");
        }
        long last = shape[shape.length - 1];
        if (last > 0 && last != expectedActions) {
            throw new IllegalStateException(
                    "ONNX action head width " + last + " != expected " + expectedActions
                            + " (PPO/BC export must emit policy_logits of size action bins)");
        }
    }

    static void validateActionHead(OrtSession s, int expectedActions) throws OrtException {
        String outName = s.getOutputNames().contains("policy_logits")
                ? "policy_logits"
                : s.getOutputNames().iterator().next();
        ai.onnxruntime.NodeInfo node = s.getOutputInfo().get(outName);
        if (node == null) {
            throw new IllegalStateException("Missing ONNX output info for " + outName);
        }
        long[] shape = ((TensorInfo) node.getInfo()).getShape();
        validateActionHeadShapes(shape, expectedActions);
    }

    @Override
    public boolean isLoaded() {
        return loaded && session != null;
    }

    @Override
    public Output run(float[] observation) {
        if (!isLoaded()) {
            throw new IllegalStateException("Model not loaded; call load() first");
        }
        try {
            long[] shape = new long[]{1, observation.length};
            FloatBuffer buf = FloatBuffer.allocate(observation.length);
            buf.put(observation);
            buf.rewind();
            try (OnnxTensor input = OnnxTensor.createTensor(env, buf, shape);
                 OrtSession.Result result = session.run(java.util.Collections.singletonMap(inputName, input))) {
                OnnxTensor outTensor = (OnnxTensor) result.get(outputName)
                        .orElseThrow(() -> new IllegalStateException("Missing output " + outputName));
                float[] logits = flattenLast(outTensor, expectedActions);
                return new Output(logits, 0.0f);
            }
        } catch (OrtException e) {
            throw new IllegalStateException("ONNX inference failed: " + e.getMessage(), e);
        }
    }

    static float[] flattenLast(OnnxTensor tensor, int expectedActions) throws OrtException {
        Object value = tensor.getValue();
        if (value instanceof float[] flat) {
            return copyExact(flat, expectedActions);
        }
        if (value instanceof float[][] batched) {
            if (batched.length == 0) {
                throw new IllegalStateException("Empty ONNX batch output");
            }
            return copyExact(batched[0], expectedActions);
        }
        throw new IllegalStateException("Unsupported ONNX output type: "
                + (value == null ? "null" : value.getClass().getName()));
    }

    private static float[] copyExact(float[] src, int expected) {
        if (src.length == expected) return src.clone();
        if (src.length > expected) {
            float[] out = new float[expected];
            System.arraycopy(src, 0, out, 0, expected);
            return out;
        }
        throw new IllegalStateException(
                "ONNX logits length " + src.length + " < expected " + expected);
    }

    private void closeSession() {
        loaded = false;
        backendUsed = "none";
        if (session != null) {
            try { session.close(); } catch (OrtException ignored) {}
            session = null;
        }
        env = null;
        inputName = null;
        outputName = null;
    }

    @Override
    public void close() {
        closeSession();
    }
}
