package dev.suika.ai;

import dev.suika.core.GameState;

import java.util.Random;

/**
 * Baseline random agent — validates env/dashboard plumbing (ROADMAP §IV.1).
 */
public class RandomAgent implements AgentPlugin {

    private final Random rng = new Random();

    @Override public String id()          { return "random"; }
    @Override public String displayName() { return "Random"; }

    @Override
    public Object selectAction(GameState state, ActionSpec spec) {
        if (spec.discrete()) {
            return rng.nextInt(spec.bins());
        } else {
            double range = spec.continuousMax() - spec.continuousMin();
            return spec.continuousMin() + rng.nextDouble() * range;
        }
    }
}
