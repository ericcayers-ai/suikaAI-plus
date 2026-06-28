package dev.suika.env;

import dev.suika.core.GameCore;
import dev.suika.core.GameState;
import dev.suika.core.PhysicsConfig;
import dev.suika.core.StepResult;

/**
 * Single-agent Gymnasium-shaped environment wrapping {@link GameCore}.
 * Implements the Environment Contract defined in ROADMAP §III.
 */
public class SuikaEnv {

    private final ObservationMode    obsMode;
    private final ActionSpace        actionSpace;
    private final RewardConfig       rewardConfig;
    private final ObservationEncoder encoder;
    private final ComposableReward   reward;

    private GameCore core;
    private long     seed;

    public SuikaEnv(ObservationMode obsMode, ActionSpace actionSpace, RewardConfig rewardConfig) {
        this.obsMode      = obsMode;
        this.actionSpace  = actionSpace;
        this.rewardConfig = rewardConfig;
        this.encoder      = ObservationEncoderFactory.create(obsMode);
        this.reward       = new ComposableReward(rewardConfig);
    }

    // -----------------------------------------------------------------------
    // Gymnasium API
    // -----------------------------------------------------------------------

    /** reset(seed) → initial GameState. */
    public GameState reset(long seed) {
        this.seed = seed;
        this.core = new GameCore(seed);
        return core.getState();
    }

    /**
     * step(action) → (obs, reward, terminated, truncated, info).
     * Accepts int (discrete) or double (continuous) actions.
     */
    public StepResult step(double action) {
        double x = resolveAction(action);
        StepResult raw = core.dropAndSettle(x);
        RewardBreakdown rb = reward.compute(raw);
        return new StepResult(raw.observation(), rb.total(),
                raw.terminated(), raw.truncated(), raw.mergesThisStep());
    }

    /** Fork this environment's core for planning (MCTS, MuZero). */
    public SuikaEnv fork() {
        SuikaEnv copy = new SuikaEnv(obsMode, actionSpace, rewardConfig);
        copy.seed = this.seed;
        copy.core = this.core.snapshot();
        return copy;
    }

    /** Encode a {@link GameState} into the float observation vector. */
    public float[] encode(GameState state) {
        return encoder.encode(state);
    }

    /** Current observation shape, e.g. [584] for STATE or [14,24,16] for HYBRID. */
    public int[] observationShape() { return encoder.shape(); }

    public GameState observation() { return core.getState(); }
    public long getSeed()          { return seed; }
    public boolean isGameOver()    { return core == null || core.isGameOver(); }

    // -----------------------------------------------------------------------

    private double resolveAction(double action) {
        return switch (actionSpace) {
            case ActionSpace.Discrete d -> {
                int a = (int) Math.round(action);
                yield d.toDropX(Math.max(0, Math.min(d.bins() - 1, a)),
                        PhysicsConfig.DROP_X_MIN, PhysicsConfig.DROP_X_MAX);
            }
            case ActionSpace.Continuous c ->
                    c.toDropX(action, PhysicsConfig.DROP_X_MIN, PhysicsConfig.DROP_X_MAX);
        };
    }
}
