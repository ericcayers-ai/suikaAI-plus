package dev.suika.ai;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link MlpPolicy#backpropCrossEntropyGradient} — the analytical gradient
 * that replaced a finite-difference approximation costing one forward pass per
 * parameter (tens of thousands of forward passes per training step for the network
 * size {@link BehavioralCloningTrainer} actually uses, ~6.7s/update measured before
 * the fix). A wrong gradient here would silently make Behavioral Cloning / DAgger
 * train toward garbage instead of just being slow, so this checks the analytical
 * gradient against an independent central-difference numerical gradient rather than
 * only checking that loss goes down (which can pass even with a subtly wrong sign or
 * scale on some parameters).
 */
class MlpPolicyTest {

    private static double crossEntropyLoss(MlpPolicy p, float[] x, int y) {
        double[] logits = p.forward(x);
        double maxL = Double.NEGATIVE_INFINITY;
        for (double l : logits) maxL = Math.max(maxL, l);
        double sumExp = 0;
        for (double l : logits) sumExp += Math.exp(l - maxL);
        return -((logits[y] - maxL) - Math.log(sumExp));
    }

    private static double batchLoss(MlpPolicy p, float[][] inputs, int[] targets) {
        double loss = 0;
        for (int b = 0; b < inputs.length; b++) loss += crossEntropyLoss(p, inputs[b], targets[b]);
        return loss / inputs.length;
    }

    @Test
    void backpropGradientMatchesFiniteDifference() {
        int in = 6, hidden = 5, out = 4;
        MlpPolicy policy = new MlpPolicy(in, hidden, out);
        policy.initRandom(new Random(7));

        Random rng = new Random(11);
        int batch = 3;
        float[][] inputs = new float[batch][in];
        int[] targets = new int[batch];
        for (int i = 0; i < batch; i++) {
            for (int j = 0; j < in; j++) inputs[i][j] = (float) rng.nextGaussian();
            targets[i] = rng.nextInt(out);
        }

        double[] analytical = policy.backpropCrossEntropyGradient(inputs, targets);
        double[] weights = policy.getWeights();
        double eps = 1e-5;

        for (int i = 0; i < weights.length; i++) {
            double orig = weights[i];

            weights[i] = orig + eps;
            policy.setWeights(weights);
            double lossPlus = batchLoss(policy, inputs, targets);

            weights[i] = orig - eps;
            policy.setWeights(weights);
            double lossMinus = batchLoss(policy, inputs, targets);

            weights[i] = orig;
            policy.setWeights(weights);

            double numerical = (lossPlus - lossMinus) / (2 * eps);
            assertEquals(numerical, analytical[i], 1e-4,
                    "backprop gradient mismatch at parameter " + i + " of " + weights.length);
        }
    }

    @Test
    void backpropGradientDescendsLossSubstantially() {
        MlpPolicy policy = new MlpPolicy(6, 5, 4);
        policy.initRandom(new Random(3));
        float[][] inputs = {{1, 0, -1, 0.5f, 0, 2}, {0, 1, 0, -1, 0.5f, 0}};
        int[] targets = {1, 2};

        double lossBefore = batchLoss(policy, inputs, targets);

        for (int step = 0; step < 50; step++) {
            double[] weights = policy.getWeights();
            double[] grad = policy.backpropCrossEntropyGradient(inputs, targets);
            for (int i = 0; i < weights.length; i++) weights[i] -= 0.1 * grad[i];
            policy.setWeights(weights);
        }

        double lossAfter = batchLoss(policy, inputs, targets);
        assertTrue(lossAfter < lossBefore * 0.5,
                "loss should drop substantially after 50 gradient steps: before=" + lossBefore + " after=" + lossAfter);
    }
}
