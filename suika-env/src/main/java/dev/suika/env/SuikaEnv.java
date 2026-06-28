package dev.suika.env;

import dev.suika.core.GameCore;
import dev.suika.core.GameState;
import dev.suika.core.PhysicsConfig;
import dev.suika.core.StepResult;

/**
 * Single-agent Gymnasium-shaped environment wrapping {@link GameCore}.
 *
 * <p>This is the JVM side of the Environment Contract (ROADMAP §III).
 * The matching Python wrapper lives in {@code python/suika/env.py}.
 */
public class SuikaEnv {

    private final ObservationMode obsMode;
    private final ActionSpace     actionSpace;
    private final RewardConfig    rewardConfig;
    private GameCore core;
    private long     seed;

    public SuikaEnv(ObservationMode obsMode, ActionSpace actionSpace, RewardConfig rewardConfig) {
        this.obsMode      = obsMode;
        this.actionSpace  = actionSpace;
        this.rewardConfig = rewardConfig;
    }

    /** Gymnasium: reset(seed) → initial observation. */
    public GameState reset(long seed) {
        this.seed = seed;
        this.core = new GameCore(seed);
        return core.getState();
    }

    /** Gymnasium: step(action) → (obs, reward, terminated, truncated, info). */
    public StepResult step(Object action) {
        double x = resolveAction(action);
        StepResult raw = core.dropAndSettle(x);
        double reward = shapeReward(raw);
        return new StepResult(raw.observation(), reward, raw.terminated(), raw.truncated(), raw.mergesThisStep());
    }

    /** Fork this environment for planning (MCTS, MuZero). */
    public SuikaEnv fork() {
        SuikaEnv copy = new SuikaEnv(obsMode, actionSpace, rewardConfig);
        copy.seed = this.seed;
        copy.core = this.core.snapshot();
        return copy;
    }

    public GameState observation() {
        return core.getState();
    }

    // -----------------------------------------------------------------------

    private double resolveAction(Object action) {
        return switch (actionSpace) {
            case ActionSpace.Discrete d -> {
                int a = ((Number) action).intValue();
                yield d.toDropX(a, PhysicsConfig.DROP_X_MIN, PhysicsConfig.DROP_X_MAX);
            }
            case ActionSpace.Continuous c -> {
                double a = ((Number) action).doubleValue();
                yield c.toDropX(a, PhysicsConfig.DROP_X_MIN, PhysicsConfig.DROP_X_MAX);
            }
        };
    }

    private double shapeReward(StepResult raw) {
        double r = raw.reward() * rewardConfig.scoreDeltaWeight();
        if (raw.terminated()) r -= rewardConfig.gameOverPenalty();
        r += rewardConfig.survivalWeight();
        return r;
    }
}
