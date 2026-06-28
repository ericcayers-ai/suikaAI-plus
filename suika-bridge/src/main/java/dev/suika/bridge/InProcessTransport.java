package dev.suika.bridge;

import java.util.function.Function;

/**
 * Dependency-free {@link BridgeTransport} that evaluates a policy in-process.
 *
 * <p>Used for headless tests and for the JVM-native inference path (e.g. a policy
 * loaded via DJL/ONNX), where no Python process is involved. Supplying a policy
 * function makes the round-trip deterministic and testable without a sidecar.
 */
public final class InProcessTransport implements BridgeTransport {

    private final BridgeConfig                  config;
    private final Function<float[], double[]>   policy;
    private boolean                             connected = false;

    /**
     * @param config the (informational) config this transport represents
     * @param policy maps an observation to an action vector — stands in for the
     *               Python policy or a loaded ONNX model
     */
    public InProcessTransport(BridgeConfig config, Function<float[], double[]> policy) {
        this.config = config;
        this.policy = policy;
    }

    /** Convenience: an in-process transport that always drops in the centre (action 0.0). */
    public static InProcessTransport constantCentre() {
        return new InProcessTransport(BridgeConfig.embedded(), obs -> new double[]{0.0});
    }

    @Override public void connect()        { connected = true; }
    @Override public boolean isConnected() { return connected; }
    @Override public BridgeConfig config() { return config; }

    @Override
    public double[] requestAction(float[] observation) {
        if (!connected) throw new IllegalStateException("Transport not connected; call connect() first");
        return policy.apply(observation);
    }

    @Override
    public void close() { connected = false; }
}
