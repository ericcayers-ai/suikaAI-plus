package dev.suika.ai;

/**
 * Configuration for Inverse RL methods (MaxEnt IRL, GAIL, AIRL) — ROADMAP §IV.7.
 *
 * <p>Instead of copying the expert's actions directly (BC), IRL infers the
 * <em>reward function</em> that explains the demonstrations. The recovered reward
 * can then be optimised by any RL algorithm, generalising beyond seen states.
 *
 * <p>GAIL and AIRL train a discriminator alongside the policy; training runs in
 * the Python sidecar and is configured by this record.
 */
public record InverseRlConfig(
        String method,          // "maxent_irl" | "gail" | "airl"
        int    discriminatorHidden,
        double discriminatorLr,
        double generatorLr,
        int    discriminatorSteps,
        int    generatorSteps,
        double entropyCoef
) {
    public static InverseRlConfig gailDefaults() {
        return new InverseRlConfig("gail", 256, 3e-4, 3e-4, 1, 10, 0.1);
    }

    public static InverseRlConfig airlDefaults() {
        return new InverseRlConfig("airl", 256, 3e-4, 3e-4, 5, 10, 0.05);
    }
}
