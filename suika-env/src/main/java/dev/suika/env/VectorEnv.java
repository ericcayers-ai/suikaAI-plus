package dev.suika.env;

import dev.suika.core.GameState;
import dev.suika.core.StepResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntToLongFunction;

/**
 * K parallel environments stepped in lock-step using Project Loom virtual threads.
 *
 * <p>Usage pattern mirrors Gymnasium VectorEnv:
 * <pre>{@code
 *   var vec = new VectorEnv(64, mode, space, reward);
 *   float[][] obs = vec.reset(seed -> seed + i);   // per-env seed
 *   double[] actions = policy.act(obs);
 *   var results = vec.step(actions);
 * }</pre>
 */
public final class VectorEnv implements AutoCloseable {

    private final int               numEnvs;
    private final SuikaEnv[]        envs;
    private final ExecutorService   pool;

    public VectorEnv(int numEnvs,
                     ObservationMode mode,
                     ActionSpace actionSpace,
                     RewardConfig rewardConfig) {
        this.numEnvs = numEnvs;
        this.envs    = new SuikaEnv[numEnvs];
        for (int i = 0; i < numEnvs; i++) {
            envs[i] = new SuikaEnv(mode, actionSpace, rewardConfig);
        }
        this.pool = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Reset all environments.
     *
     * @param seedFn maps env index → seed; use {@code i -> baseSeed + i} for independent seeds
     * @return array of initial observations, shape [numEnvs][obsSize]
     */
    public float[][] reset(IntToLongFunction seedFn) {
        float[][] obs = new float[numEnvs][];
        List<Future<float[]>> futures = new ArrayList<>(numEnvs);
        for (int i = 0; i < numEnvs; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                GameState s = envs[idx].reset(seedFn.applyAsLong(idx));
                return envs[idx].encode(s);
            }));
        }
        for (int i = 0; i < numEnvs; i++) {
            try { obs[i] = futures.get(i).get(); }
            catch (InterruptedException | ExecutionException e) { throw new RuntimeException(e); }
        }
        return obs;
    }

    /** Step record carrying per-env results. */
    public record VecStepResult(
            float[][] observations,
            double[]  rewards,
            boolean[] terminated,
            boolean[] truncated,
            GameState[] infos
    ) {
        public int numEnvs() { return rewards.length; }
    }

    /**
     * Step all environments in parallel.
     *
     * @param actions array of per-env actions (same length as numEnvs)
     */
    public VecStepResult step(double[] actions) {
        if (actions.length != numEnvs)
            throw new IllegalArgumentException("actions.length must equal numEnvs");

        float[][] obs       = new float[numEnvs][];
        double[]  rewards   = new double[numEnvs];
        boolean[] terminated = new boolean[numEnvs];
        boolean[] truncated  = new boolean[numEnvs];
        GameState[] infos    = new GameState[numEnvs];

        List<Future<StepResult>> futures = new ArrayList<>(numEnvs);
        for (int i = 0; i < numEnvs; i++) {
            final int    idx = i;
            final double act = actions[i];
            futures.add(pool.submit(() -> envs[idx].step(act)));
        }

        for (int i = 0; i < numEnvs; i++) {
            try {
                StepResult r = futures.get(i).get();
                obs[i]        = envs[i].encode(r.observation());
                rewards[i]    = r.reward();
                terminated[i] = r.terminated();
                truncated[i]  = r.truncated();
                infos[i]      = r.observation();
                if (r.terminated()) {
                    // Auto-reset on termination (Gymnasium VectorEnv convention)
                    GameState init = envs[i].reset(envs[i].getSeed() + 1);
                    obs[i] = envs[i].encode(init);
                }
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        return new VecStepResult(obs, rewards, terminated, truncated, infos);
    }

    public int numEnvs() { return numEnvs; }

    @Override
    public void close() { pool.shutdownNow(); }
}
