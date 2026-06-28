package dev.suika.ai;

/**
 * A single (observation, action) pair from a human or expert demonstration.
 * Stored in a {@link DemoDataset} for Behavioral Cloning / DAgger.
 */
public record Demonstration(
        float[] observation,
        int     action,
        double  reward,
        boolean terminal
) {}
