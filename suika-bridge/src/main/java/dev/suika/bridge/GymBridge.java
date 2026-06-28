package dev.suika.bridge;

import dev.suika.core.GameState;
import dev.suika.core.StepResult;
import dev.suika.env.SuikaEnv;

/**
 * Gymnasium-shaped adapter over {@link SuikaEnv} (ROADMAP §III.4).
 *
 * <p>This is the JVM half that a Python {@code gym.make("Suika-v0")} drives over a
 * {@link BridgeTransport}: {@code reset}/{@code step} return flat, serializable
 * tensors via {@link ObservationCodec}, matching the Gymnasium
 * {@code (obs, reward, terminated, truncated, info)} contract.
 */
public final class GymBridge {

    /** A serializable transition mirroring Gymnasium's step return. */
    public record Transition(
            float[]  observation,
            double   reward,
            boolean  terminated,
            boolean  truncated,
            int      mergesThisStep
    ) {
        public boolean done() { return terminated || truncated; }
    }

    private final SuikaEnv env;

    public GymBridge(SuikaEnv env) {
        this.env = env;
    }

    /** reset(seed) → initial observation as a flat float vector. */
    public float[] reset(long seed) {
        GameState s = env.reset(seed);
        return env.encode(s);
    }

    /** step(action) → a serializable {@link Transition}. */
    public Transition step(double action) {
        StepResult r = env.step(action);
        return new Transition(
                env.encode(r.observation()),
                r.reward(),
                r.terminated(),
                r.truncated(),
                r.mergesThisStep().size()
        );
    }

    /** The flat observation length the encoder produces (product of its shape). */
    public int observationSize() {
        int size = 1;
        for (int dim : env.observationShape()) size *= dim;
        return size;
    }
}
