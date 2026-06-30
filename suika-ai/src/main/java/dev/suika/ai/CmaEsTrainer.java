package dev.suika.ai;

import dev.suika.core.StepResult;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;

/**
 * CMA-ES (Covariance Matrix Adaptation Evolution Strategy) — ROADMAP §IV.5.
 *
 * <p>This is a lightweight (μ/λ)-CMA-ES without the full covariance update for now;
 * it uses the diagonal approximation (separable CMA-ES) which is O(n) per generation
 * and already dramatically outperforms the basic GA on high-dimensional spaces.
 *
 * <p>The same trainer is reused for physics calibration (ROADMAP §I.3 Appendix B).
 */
public final class CmaEsTrainer implements TrainerPlugin, AutoCloseable {

    private static final int INPUT_SIZE  = dev.suika.env.StateObservationEncoder.TOTAL;
    private static final int HIDDEN_SIZE = 64;
    private static final int OUTPUT_BINS = 32;

    private final int    lambda;   // population size (typically 4 + floor(3 ln n))
    private final int    mu;       // number of parents selected
    private final double sigma0;   // initial step size
    private final int    episodesPerEval;

    private final Random          rng;
    private final FitnessEvaluator evaluator;
    private final ExecutorService  pool;

    private double[] mean;         // distribution mean
    private double[] sigma;        // per-dimension step sizes (separable)
    private double[] recombWeights;// normalised recombination weights
    private int      generation = 0;

    /** No-arg constructor for {@link java.util.ServiceLoader} discovery; uses default hyperparams. */
    public CmaEsTrainer() { this(0.3, 2, 0L); }

    public CmaEsTrainer(double sigma0, int episodesPerEval, long seed) {
        this(sigma0, episodesPerEval, seed, 0);
    }

    /** @param threads evaluation worker threads ({@code 0} = auto / all cores). A bounded
     *                 pool caps concurrent live simulations to avoid heap exhaustion. */
    public CmaEsTrainer(double sigma0, int episodesPerEval, long seed, int threads) {
        int n         = MlpPolicy.paramCount(INPUT_SIZE, HIDDEN_SIZE, OUTPUT_BINS);
        this.sigma0   = sigma0;
        this.lambda   = 4 + (int) Math.floor(3 * Math.log(n));
        this.mu       = lambda / 2;
        this.episodesPerEval = episodesPerEval;
        this.rng      = new Random(seed);
        this.evaluator = new FitnessEvaluator(episodesPerEval, 300, OUTPUT_BINS);
        int workers   = threads > 0 ? threads : Math.max(1, Runtime.getRuntime().availableProcessors());
        this.pool     = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "cma-eval");
            t.setDaemon(true);
            return t;
        });

        // Initialise mean to zero, sigma uniform
        this.mean  = new double[n];
        this.sigma = new double[n];
        Arrays.fill(sigma, sigma0);

        // Logarithmic recombination weights
        this.recombWeights = new double[mu];
        double sum = 0;
        for (int i = 0; i < mu; i++) { recombWeights[i] = Math.log(mu + 0.5) - Math.log(i + 1); sum += recombWeights[i]; }
        for (int i = 0; i < mu; i++) recombWeights[i] /= sum;
    }

    @Override public String id() { return "cma-es"; }
    @Override public void observe(StepResult t) {}

    @Override
    public void update() {
        int n = mean.length;

        // 1. Sample λ offspring
        double[][] samples = new double[lambda][n];
        for (int k = 0; k < lambda; k++) {
            for (int i = 0; i < n; i++) samples[k][i] = mean[i] + sigma[i] * rng.nextGaussian();
        }

        // 2. Evaluate in parallel
        double[] fitness = new double[lambda];
        List<Future<Double>> fts = new ArrayList<>(lambda);
        for (int k = 0; k < lambda; k++) {
            final double[] s = samples[k];
            final int gen = generation;
            final int idx = k;
            fts.add(pool.submit(() -> {
                NeuralAgent a = buildAgent(s);
                return evaluator.evaluate(a, (long) gen * lambda + idx);
            }));
        }
        for (int k = 0; k < lambda; k++) {
            try { fitness[k] = fts.get(k).get(); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
        double bsum = 0, bmax = Double.NEGATIVE_INFINITY;
        for (double v : fitness) { bsum += v; bmax = Math.max(bmax, v); }
        this.lastBestFitness = bmax;
        this.lastMeanFitness = bsum / lambda;

        // 3. Sort by fitness descending
        Integer[] order = new Integer[lambda];
        for (int k = 0; k < lambda; k++) order[k] = k;
        Arrays.sort(order, (a, b) -> Double.compare(fitness[b], fitness[a]));

        // 4. Recombine: new mean = weighted sum of top-μ
        double[] newMean = new double[n];
        for (int i = 0; i < mu; i++) {
            double w = recombWeights[i];
            double[] s = samples[order[i]];
            for (int j = 0; j < n; j++) newMean[j] += w * s[j];
        }

        // 5. Update per-dim sigma (cumulative step-size adaptation, simplified)
        for (int j = 0; j < n; j++) {
            double step = Math.abs(newMean[j] - mean[j]);
            sigma[j] = 0.8 * sigma[j] + 0.2 * Math.max(step, 1e-8);
        }

        mean = newMean;
        generation++;
    }

    private volatile double lastBestFitness = 0, lastMeanFitness = 0;

    public NeuralAgent bestAgent()  { return buildAgent(mean); }
    public int         generation() { return generation; }
    public double      bestFitness(){ return lastBestFitness; }
    public double      meanFitness(){ return lastMeanFitness; }

    private NeuralAgent buildAgent(double[] weights) {
        MlpPolicy p = new MlpPolicy(INPUT_SIZE, HIDDEN_SIZE, OUTPUT_BINS);
        p.setWeights(weights);
        return new NeuralAgent(p);
    }

    @Override
    public void close() { pool.shutdownNow(); }
}
