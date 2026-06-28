package dev.suika.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Length-prefixed little-endian binary codec for observation/action tensors
 * crossing the Java↔Python boundary (ROADMAP §V.2).
 *
 * <p>This is the dependency-free stand-in for the Apache Arrow / shared-memory
 * zero-copy path: a flat {@code float[]} becomes a compact byte buffer that any
 * language can read. The real high-throughput path memory-maps an Arrow buffer
 * instead of copying, but the wire shape (int32 length + float32 payload) matches.
 */
public final class ObservationCodec {

    private ObservationCodec() {}

    /** Encode a float vector as [int32 length][float32 × length], little-endian. */
    public static byte[] encode(float[] values) {
        ByteBuffer buf = ByteBuffer.allocate(Integer.BYTES + values.length * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(values.length);
        for (float v : values) buf.putFloat(v);
        return buf.array();
    }

    /** Decode a buffer produced by {@link #encode(float[])} back into a float vector. */
    public static float[] decode(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int n = buf.getInt();
        if (n < 0 || Integer.BYTES + (long) n * Float.BYTES > bytes.length) {
            throw new IllegalArgumentException("Corrupt buffer: declared length " + n
                    + " exceeds payload (" + bytes.length + " bytes)");
        }
        float[] out = new float[n];
        for (int i = 0; i < n; i++) out[i] = buf.getFloat();
        return out;
    }

    /** Convenience: encode a double action vector (downcast to float for the wire). */
    public static byte[] encodeAction(double[] action) {
        float[] f = new float[action.length];
        for (int i = 0; i < action.length; i++) f[i] = (float) action[i];
        return encode(f);
    }

    /** Convenience: decode an action vector back to doubles. */
    public static double[] decodeAction(byte[] bytes) {
        float[] f = decode(bytes);
        double[] d = new double[f.length];
        for (int i = 0; i < f.length; i++) d[i] = f[i];
        return d;
    }
}
