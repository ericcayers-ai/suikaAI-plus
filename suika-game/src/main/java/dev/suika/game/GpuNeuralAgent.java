package dev.suika.game;

import dev.suika.ai.ActionSpec;
import dev.suika.ai.AgentPlugin;
import dev.suika.ai.MlpPolicy;
import dev.suika.ai.NeuralAgent;
import dev.suika.core.GameCore;
import dev.suika.core.GameState;
import dev.suika.env.StateObservationEncoder;

/**
 * A saved-policy agent that runs its forward pass on the GPU (see
 * {@link GpuInferenceBridge}) when GPU inference is active, and falls back — seamlessly and
 * per-call — to the exact JVM {@link NeuralAgent} otherwise. Used by {@link AiSlotPlayer}
 * for played-back trained models (RT Lab autoplay, ensemble donors), the load-once/
 * infer-many path where GPU offload is real and its latency irrelevant.
 *
 * <p>Because the bridge replicates {@code MlpPolicy.forward} exactly, the GPU and JVM
 * decisions are identical; this wrapper only changes WHERE the arithmetic runs, never the
 * result — so a bridge failure downgrades performance, never correctness.
 */
final class GpuNeuralAgent implements AgentPlugin, AutoCloseable {

    private static final int IN = StateObservationEncoder.TOTAL, HID = ModelSlots.HIDDEN_SIZE, OUT = ModelSlots.OUTPUT_BINS;

    private final NeuralAgent jvmFallback;
    private final StateObservationEncoder encoder = new StateObservationEncoder();
    private final GpuInferenceBridge bridge;
    private volatile boolean gpuUsable;

    GpuNeuralAgent(MlpPolicy policy) {
        this.jvmFallback = new NeuralAgent(policy);
        GpuInferenceBridge b = GpuInferenceBridge.start(IN, HID, OUT, policy.getWeights(), "cuda");
        this.bridge = b.healthy() ? b : null;
        this.gpuUsable = this.bridge != null;
        if (b != this.bridge) b.close();
    }

    /** True when this agent is genuinely running inference on the GPU right now. */
    boolean onGpu() { return gpuUsable && bridge != null && bridge.healthy(); }

    @Override public String id()          { return "gpu-neural"; }
    @Override public String displayName() { return "Neural (GPU)"; }

    @Override
    public Object selectAction(GameState state, ActionSpec spec) {
        if (onGpu()) {
            int a = bridge.argmax(encoder.encode(state));
            if (a >= 0) return clampToBins(a, spec);
            gpuUsable = false; // bridge died — fall back for the rest of the session
        }
        return jvmFallback.selectAction(state, spec);
    }

    @Override
    public Object selectAction(GameCore core, ActionSpec spec) {
        return selectAction(core.getState(), spec);
    }

    private static int clampToBins(int a, ActionSpec spec) {
        if (!spec.discrete()) return a;
        int bins = spec.bins();
        return Math.max(0, Math.min(bins - 1, a));
    }

    @Override
    public void close() {
        gpuUsable = false;
        if (bridge != null) bridge.close();
    }
}
