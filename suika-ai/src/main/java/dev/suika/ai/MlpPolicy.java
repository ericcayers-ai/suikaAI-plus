package dev.suika.ai;

import java.util.Arrays;
import java.util.Random;

/**
 * Minimal multi-layer perceptron: input → hidden (tanh) → output (softmax / linear).
 *
 * <p>Weights are a single flat array so neuroevolution can treat the whole network
 * as one continuous parameter vector. Architecture is fixed at construction time.
 */
public final class MlpPolicy {

    private final int   inputSize;
    private final int   hiddenSize;
    private final int   outputSize;
    private final double[] weights; // [W1 | b1 | W2 | b2]

    public MlpPolicy(int inputSize, int hiddenSize, int outputSize) {
        this.inputSize  = inputSize;
        this.hiddenSize = hiddenSize;
        this.outputSize = outputSize;
        this.weights    = new double[paramCount(inputSize, hiddenSize, outputSize)];
    }

    /** Number of parameters for this architecture. */
    public static int paramCount(int in, int hidden, int out) {
        return in * hidden + hidden + hidden * out + out;
    }

    public int paramCount() { return weights.length; }

    /** Replace all weights from the given array. */
    public void setWeights(double[] w) {
        if (w.length != weights.length) throw new IllegalArgumentException("weight size mismatch");
        System.arraycopy(w, 0, weights, 0, weights.length);
    }

    public double[] getWeights() { return Arrays.copyOf(weights, weights.length); }

    /** Randomly initialise weights with small Gaussian values (Xavier-ish). */
    public void initRandom(Random rng) {
        double scale = Math.sqrt(2.0 / (inputSize + hiddenSize));
        for (int i = 0; i < weights.length; i++) weights[i] = rng.nextGaussian() * scale;
    }

    /**
     * Forward pass.
     *
     * @param input  float[] of length {@code inputSize} (observation vector)
     * @return       output logits of length {@code outputSize} (action probabilities / Q-values)
     */
    public double[] forward(float[] input) {
        // Layer 1: hidden = tanh(W1 * input + b1)
        int w1Offset = 0;
        int b1Offset = inputSize * hiddenSize;
        double[] hidden = new double[hiddenSize];
        for (int h = 0; h < hiddenSize; h++) {
            double sum = weights[b1Offset + h];
            for (int i = 0; i < inputSize; i++) sum += weights[w1Offset + h * inputSize + i] * input[i];
            hidden[h] = Math.tanh(sum);
        }

        // Layer 2: output = W2 * hidden + b2
        int w2Offset = b1Offset + hiddenSize;
        int b2Offset = w2Offset + hiddenSize * outputSize;
        double[] out = new double[outputSize];
        for (int o = 0; o < outputSize; o++) {
            double sum = weights[b2Offset + o];
            for (int h = 0; h < hiddenSize; h++) sum += weights[w2Offset + o * hiddenSize + h] * hidden[h];
            out[o] = sum;
        }
        return out;
    }

    /** Argmax of the output logits (greedy discrete action). */
    public int greedyAction(float[] input) {
        double[] logits = forward(input);
        int best = 0;
        for (int i = 1; i < logits.length; i++) if (logits[i] > logits[best]) best = i;
        return best;
    }

    /**
     * Analytical gradient of the average softmax cross-entropy loss over a batch, with
     * respect to every weight — one forward pass + one backward pass per sample.
     *
     * <p>Replaces a finite-difference approximation that took a forward pass <em>per
     * parameter</em> ({@link #paramCount()} of them — tens of thousands for this
     * architecture's typical observation size) to estimate a single gradient; that made
     * one training update take several seconds, which is why live imitation-learning
     * training barely produced an update per session. This is the exact same gradient,
     * computed the standard way, at a small constant multiple of one forward pass
     * instead of {@code paramCount()} of them.
     *
     * @param inputs  batch of observation vectors
     * @param targets batch of target action indices, one per input
     * @return flat gradient array in the same layout as {@link #getWeights()}
     */
    public double[] backpropCrossEntropyGradient(float[][] inputs, int[] targets) {
        int n = inputs.length;
        double[] grad = new double[weights.length];
        if (n == 0) return grad;

        int w1Offset = 0;
        int b1Offset = inputSize * hiddenSize;
        int w2Offset = b1Offset + hiddenSize;
        int b2Offset = w2Offset + hiddenSize * outputSize;

        double[] hidden = new double[hiddenSize];
        double[] logits = new double[outputSize];
        double[] dz2    = new double[outputSize];
        double[] da1    = new double[hiddenSize];

        for (int s = 0; s < n; s++) {
            float[] x = inputs[s];
            int y = targets[s];

            // ---- forward ----
            for (int h = 0; h < hiddenSize; h++) {
                double sum = weights[b1Offset + h];
                for (int i = 0; i < inputSize; i++) sum += weights[w1Offset + h * inputSize + i] * x[i];
                hidden[h] = Math.tanh(sum);
            }
            double maxL = Double.NEGATIVE_INFINITY;
            for (int o = 0; o < outputSize; o++) {
                double sum = weights[b2Offset + o];
                for (int h = 0; h < hiddenSize; h++) sum += weights[w2Offset + o * hiddenSize + h] * hidden[h];
                logits[o] = sum;
                if (sum > maxL) maxL = sum;
            }
            double sumExp = 0;
            for (int o = 0; o < outputSize; o++) { dz2[o] = Math.exp(logits[o] - maxL); sumExp += dz2[o]; }
            // dz2 currently holds unnormalised exp(logit); turn it into the softmax
            // cross-entropy gradient dL/dz2 = softmax(logits) - one_hot(y) in place.
            for (int o = 0; o < outputSize; o++) dz2[o] = dz2[o] / sumExp - (o == y ? 1.0 : 0.0);

            // ---- backward ----
            java.util.Arrays.fill(da1, 0.0);
            for (int o = 0; o < outputSize; o++) {
                double d = dz2[o];
                for (int h = 0; h < hiddenSize; h++) {
                    grad[w2Offset + o * hiddenSize + h] += d * hidden[h];
                    da1[h] += d * weights[w2Offset + o * hiddenSize + h];
                }
                grad[b2Offset + o] += d;
            }
            for (int h = 0; h < hiddenSize; h++) {
                double dz1 = da1[h] * (1.0 - hidden[h] * hidden[h]); // tanh'(z) = 1 - tanh(z)^2
                for (int i = 0; i < inputSize; i++) grad[w1Offset + h * inputSize + i] += dz1 * x[i];
                grad[b1Offset + h] += dz1;
            }
        }
        for (int i = 0; i < grad.length; i++) grad[i] /= n;
        return grad;
    }
}
