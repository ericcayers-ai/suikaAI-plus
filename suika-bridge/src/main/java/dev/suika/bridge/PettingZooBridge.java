package dev.suika.bridge;

import dev.suika.core.GameState;
import dev.suika.core.StepResult;
import dev.suika.env.SuikaEnv;

import java.util.Map;

/**
 * PettingZoo-shaped adapter for two-player racing self-play (ROADMAP §III.4, §IV.10).
 *
 * <p>Both agents play the <em>same</em> seeded fruit sequence; reward is each player's
 * own step reward. This is the JVM half a PettingZoo {@code parallel_env} would drive
 * over a {@link BridgeTransport} for competitive/league training.
 */
public final class PettingZooBridge {

    /** Stable agent identifiers, mirroring PettingZoo's {@code agents} list. */
    public static final String AGENT_A = "player_0";
    public static final String AGENT_B = "player_1";

    private final SuikaEnv envA;
    private final SuikaEnv envB;

    public PettingZooBridge(SuikaEnv envA, SuikaEnv envB) {
        this.envA = envA;
        this.envB = envB;
    }

    /** reset(seed) → per-agent initial observations (same seed for a fair race). */
    public Map<String, float[]> reset(long seed) {
        GameState a = envA.reset(seed);
        GameState b = envB.reset(seed);
        return Map.of(AGENT_A, envA.encode(a), AGENT_B, envB.encode(b));
    }

    /** step(actions) → per-agent transitions, keyed by agent id. */
    public Map<String, GymBridge.Transition> step(double actionA, double actionB) {
        StepResult ra = envA.step(actionA);
        StepResult rb = envB.step(actionB);
        return Map.of(
                AGENT_A, toTransition(envA, ra),
                AGENT_B, toTransition(envB, rb)
        );
    }

    /** True once both players have terminated/truncated — the race is over. */
    public boolean allDone(Map<String, GymBridge.Transition> step) {
        return step.values().stream().allMatch(GymBridge.Transition::done);
    }

    private GymBridge.Transition toTransition(SuikaEnv env, StepResult r) {
        return new GymBridge.Transition(
                env.encode(r.observation()),
                r.reward(),
                r.terminated(),
                r.truncated(),
                r.mergesThisStep().size()
        );
    }
}
