package dev.suika.bridge;

/**
 * Configuration for the Java↔Python boundary (ROADMAP §II.4).
 *
 * <p>The roadmap recommends a hybrid: embedded interpreters (JEP/GraalPy) for the
 * in-app "Python Lab" and tight inference loops, a gRPC/shared-memory sidecar for
 * heavy parallel training, and DJL/ONNX for the no-Python deployment path.
 * This record selects which mechanism a given {@link BridgeTransport} should use.
 *
 * @param transport         the boundary mechanism
 * @param host              sidecar host (gRPC/ZeroMQ transports); ignored otherwise
 * @param port              sidecar port (gRPC/ZeroMQ transports); ignored otherwise
 * @param pythonExecutable  interpreter to launch for embedded/sidecar modes
 * @param modelPath         path to an exported model for {@link Transport#DJL_ONNX}
 */
public record BridgeConfig(
        Transport transport,
        String    host,
        int       port,
        String    pythonExecutable,
        String    modelPath
) {
    /** The supported Java↔Python boundary mechanisms (ROADMAP §II.4 table). */
    public enum Transport {
        /** CPython in-process via JNI — real numpy/torch, shared process. */
        JEP,
        /** Python on the JVM (GraalVM polyglot) — true shared objects, no IPC. */
        GRAALPY,
        /** Separate Python process over gRPC — clean isolation, multi-worker scale. */
        GRPC_SIDECAR,
        /** Zero-copy buffers between processes (Apache Arrow / shared memory). */
        SHARED_MEMORY,
        /** Run an exported model on the JVM with no Python at all (DJL + ONNX Runtime). */
        DJL_ONNX
    }

    /** Embedded in-process Python (JEP) for the "Python Lab" console and tight inference. */
    public static BridgeConfig embedded() {
        return new BridgeConfig(Transport.JEP, "localhost", 0, "python3", null);
    }

    /** gRPC sidecar for heavy parallel training. */
    public static BridgeConfig sidecar(String host, int port) {
        return new BridgeConfig(Transport.GRPC_SIDECAR, host, port, "python3", null);
    }

    /** No-Python deployment: load an exported ONNX policy on the JVM. */
    public static BridgeConfig onnx(String modelPath) {
        return new BridgeConfig(Transport.DJL_ONNX, "localhost", 0, null, modelPath);
    }
}
