package dev.suika.bridge;

/**
 * The Java↔Python channel that carries observations out and actions back
 * (ROADMAP §II.4, §V.2).
 *
 * <p>Concrete implementations wrap JEP, GraalPy, a gRPC/ZeroMQ sidecar, or a
 * shared-memory ring buffer. Observations and actions cross as flat float arrays so
 * the same contract serves pixel batches (zero-copy via Arrow) and symbolic state.
 *
 * <p>{@link InProcessTransport} provides a dependency-free implementation for
 * headless tests and the JVM-native inference path.
 */
public interface BridgeTransport extends AutoCloseable {

    /** Open the channel (launch the sidecar / attach the interpreter). */
    void connect();

    /** True once {@link #connect()} has succeeded and the channel is usable. */
    boolean isConnected();

    /**
     * Send one observation and receive the policy's action vector.
     * For discrete action spaces the caller rounds the first element to a bin.
     *
     * @param observation flat observation (state vector or flattened pixels)
     * @return action vector (length 1 for single-x drop policies)
     */
    double[] requestAction(float[] observation);

    /** The configuration this transport was created from. */
    BridgeConfig config();

    @Override
    void close();
}
