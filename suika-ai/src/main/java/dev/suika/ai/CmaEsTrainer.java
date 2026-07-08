package dev.suika.ai;

import dev.suika.core.StepResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Separable CMA-ES (Covariance Matrix Adaptation Evolution Strategy) — ROADMAP §IV.5.
 *
 * <p>This is the standard sep-CMA-ES (Ros &amp; Hansen 2008): a diagonal covariance
 * approximation that is O(n) per generation, with the <b>full</b> step-size and
 * covariance adaptation machinery of the reference algorithm:
 * <ul>
 *   <li><b>Global step-size σ</b> adapted by cumulative step-size adaptation (CSA) via
 *       the evolution path {@code pSigma} — σ grows while progress keeps pointing the
 *       same way and shrinks near an optimum, instead of decaying monotonically.</li>
 *   <li><b>Per-dimension variances C</b> adapted by rank-1 (evolution path {@code pC})
 *       plus rank-μ updates, with the sep-CMA learning-rate boost of (n+2)/3.</li>
 *   <li><b>Stagnation escape</b>: if the best fitness hasn't improved for a window of
 *       generations, σ is re-inflated and the paths reset so the search re-diversifies
 *       instead of flatlining forever.</li>
 * </ul>
 *
 * <p>The previous implementation adapted per-dimension σ as
 * {@code 0.8·σ + 0.2·|Δmean|} — since |Δmean| is typically a small fraction of σ, that
 * contracted the whole search geometrically toward zero within tens of generations and
 * the fitness curve flatlined. CSA fixes this: the expected step length under random
 * selection is the neutral point, so σ only shrinks when selection genuinely stops
 * making directed progress.
 *
 * <p>The same trainer is reused for physics calibration (ROADMAP §I.3 Appendix B).
 */
public final class CmaEsTrainer implements TrainerPlugin, AutoCloseable {

    private static final int INPUT_SIZE  = dev.suika.env.StateObservationEncoder.TOTAL;
    private static final int HIDDEN_SIZE = 64;
    private static final int OUTPUT_BINS = 32;

    /** Generations without best-fitness improvement before σ is re-inflated. */
    private static final int STAGNATION_WINDOW = 25;
    private static final double STAGNATION_SIGMA_BOOST = 3.0;

    private final int    lambda;   // population size
    private final int    mu;       // number of parents selected
    private final double sigma0;   // initial step size
    private final int    episodesPerEval;

    private final Random           rng;
    private final FitnessEvaluator evaluator;
    private final ExecutorService  pool;

    // --- CMA-ES state ---
    private double[] mean;          // distribution mean
    private double   sigma;         // global step size
    private double[] diagC;         // per-dimension variances (diagonal covariance)
    private double[] pSigma;        // step-size evolution path
    private double[] pC;            // covariance evolution path
    private double[] recombWeights; // normalised recombination weights (length mu)
    private double   muEff;         // variance-effective selection mass
    private double   cSigma, dSigma, cc, c1, cMu, chiN;
    private int      generation = 0;

    // --- Stagnation tracking ---
    private double bestEver = Double.NEGATIVE_INFINITY;
    private int    gensSinceImprovement = 0;

    /** No-arg constructor for {@link java.util.ServiceLoader} discovery; uses default hyperparams. */
    public CmaEsTrainer() { this(0.3, 2, 0L); }

    public CmaEsTrainer(double sigma0, int episodesPerEval, long seed) {
        this(sigma0, episodesPerEval, seed, 0);
    }

    public CmaEsTrainer(double sigma0, int episodesPerEval, long seed, int threads) {
        this(sigma0, episodesPerEval, seed, threads, 0);
    }

    /**
     * @param threads evaluation worker threads ({@code 0} = auto / all cores). A bounded
     *                pool caps concurrent live simulations to avoid heap exhaustion.
     * @param lambda  population size λ ({@code <= 0} = the CMA-ES default
     *                {@code 4 + floor(3 ln n)}). Wired to the UI's Population knob so the
     *                control actually does something for CMA-ES.
     */
    public CmaEsTrainer(double sigma0, int episodesPerEval, long seed, int threads, int lambda) {
        int n = MlpPolicy.paramCount(INPUT_SIZE, HIDDEN_SIZE, OUTPUT_BINS);
        this.sigma0   = sigma0;
        this.lambda   = lambda > 1 ? lambda : 4 + (int) Math.floor(3 * Math.log(n));
        this.mu       = Math.max(1, this.lambda / 2);
        this.episodesPerEval = Math.max(1, episodesPerEval);
        this.rng      = new Random(seed);
        this.evaluator = new FitnessEvaluator(this.episodesPerEval, 300, OUTPUT_BINS);
        int workers   = threads > 0 ? threads : Math.max(1, Runtime.getRuntime().availableProcessors());
        this.pool     = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "cma-eval");
            t.setDaemon(true);
            return t;
        });

        // Mean starts at a Xavier-style random policy (like the GA's population init) —
        // an all-zero MLP is a degenerate saddle where every output ties, which wastes
        // the first generations breaking symmetry.
        MlpPolicy init = new MlpPolicy(INPUT_SIZE, HIDDEN_SIZE, OUTPUT_BINS);
        init.initRandom(rng);
        this.mean  = init.getWeights();
        this.sigma = sigma0;
        this.diagC = new double[n];
        Arrays.fill(diagC, 1.0);
        this.pSigma = new double[n];
        this.pC     = new double[n];

        // Logarithmic recombination weights + variance-effective selection mass.
        this.recombWeights = new double[mu];
        double sum = 0;
        for (int i = 0; i < mu; i++) { recombWeights[i] = Math.log(mu + 0.5) - Math.log(i + 1); sum += recombWeights[i]; }
        double sumSq = 0;
        for (int i = 0; i < mu; i++) { recombWeights[i] /= sum; sumSq += recombWeights[i] * recombWeights[i]; }
        this.muEff = 1.0 / sumSq;

        // Standard CMA-ES strategy parameters (Hansen's tutorial), with the sep-CMA
        // learning-rate boost of (n+2)/3 on the covariance rates.
        this.cSigma = (muEff + 2.0) / (n + muEff + 5.0);
        this.dSigma = 1.0 + 2.0 * Math.max(0.0, Math.sqrt((muEff - 1.0) / (n + 1.0)) - 1.0) + cSigma;
        this.cc     = (4.0 + muEff / n) / (n + 4.0 + 2.0 * muEff / n);
        double sepBoost = (n + 2.0) / 3.0;
        this.c1  = Math.min(1.0, sepBoost * 2.0 / ((n + 1.3) * (n + 1.3) + muEff));
        this.cMu = Math.min(1.0 - c1, sepBoost * 2.0 * (muEff - 2.0 + 1.0 / muEff) / ((n + 2.0) * (n + 2.0) + muEff));
        this.chiN = Math.sqrt(n) * (1.0 - 1.0 / (4.0 * n) + 1.0 / (21.0 * n * n));
    }

    @Override public String id() { return "cma-es"; }
    @Override public void observe(StepResult t) {}

    @Override
    public void update() {
        int n = mean.length;

        // 1. Sample λ offspring: x_k = mean + σ · sqrt(C) ∘ z_k with z_k ~ N(0, I).
        double[][] zs      = new double[lambda][n];
        double[][] ys      = new double[lambda][n];
        double[][] samples = new double[lambda][n];
        for (int k = 0; k < lambda; k++) {
            for (int i = 0; i < n; i++) {
                double z = rng.nextGaussian();
                zs[k][i] = z;
                ys[k][i] = Math.sqrt(diagC[i]) * z;
                samples[k][i] = mean[i] + sigma * ys[k][i];
            }
        }

        // 2. Evaluate in parallel — every (offspring, episode) pair is its own task, so
        //    all sims for the generation run simultaneously (bounded by the pool's
        //    thread count). Mirrors GeneticTrainer's evaluation strategy.
        double[] fitness = new double[lambda];
        long[][] scores = new long[lambda][episodesPerEval];
        List<Future<?>> fts = new ArrayList<>(lambda * episodesPerEval);
        for (int k = 0; k < lambda; k++) {
            final double[] s = samples[k];
            final int gen = generation;
            final int idx = k;
            for (int e = 0; e < episodesPerEval; e++) {
                final int ep = e;
                final long epSeed = (long) gen * lambda * episodesPerEval + (long) idx * episodesPerEval + ep;
                fts.add(pool.submit(() -> scores[idx][ep] = evaluator.runSingleEpisode(buildAgent(s), epSeed)));
            }
        }
        for (Future<?> f : fts) {
            try { f.get(); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
        for (int k = 0; k < lambda; k++) {
            long sum = 0; for (long v : scores[k]) sum += v;
            fitness[k] = (double) sum / episodesPerEval;
        }
        double bsum = 0, bmax = Double.NEGATIVE_INFINITY;
        for (double v : fitness) { bsum += v; bmax = Math.max(bmax, v); }
        double bmean = bsum / lambda;
        double bsq = 0; for (double v : fitness) bsq += (v - bmean) * (v - bmean);
        this.lastBestFitness = bmax;
        this.lastMeanFitness = bmean;
        this.lastStdDev      = Math.sqrt(bsq / lambda);

        // 3. Sort by fitness descending (we maximise score).
        Integer[] order = new Integer[lambda];
        for (int k = 0; k < lambda; k++) order[k] = k;
        Arrays.sort(order, (a, b) -> Double.compare(fitness[b], fitness[a]));

        // Snapshot this generation's top offspring for the UI's "elite views".
        List<NeuralAgent> top = new ArrayList<>(Math.min(lambda, 20));
        for (int k = 0; k < Math.min(lambda, 20); k++) top.add(buildAgent(samples[order[k]]));
        this.lastTopAgents = top;
        // The champion the UI adopts is the best evaluated offspring — a real, tested
        // individual (the mean itself is never evaluated).
        this.lastBestSample = samples[order[0]].clone();

        // 4. Recombination: weighted means of the top-μ in y- and z-space.
        double[] yW = new double[n];
        double[] zW = new double[n];
        for (int i = 0; i < mu; i++) {
            double w = recombWeights[i];
            double[] y = ys[order[i]];
            double[] z = zs[order[i]];
            for (int j = 0; j < n; j++) { yW[j] += w * y[j]; zW[j] += w * z[j]; }
        }
        for (int j = 0; j < n; j++) mean[j] += sigma * yW[j];

        // 5. Step-size path + CSA update. Because C is diagonal, C^(-1/2)·yW == zW.
        double psNormSq = 0;
        double csComp = Math.sqrt(cSigma * (2.0 - cSigma) * muEff);
        for (int j = 0; j < n; j++) {
            pSigma[j] = (1.0 - cSigma) * pSigma[j] + csComp * zW[j];
            psNormSq += pSigma[j] * pSigma[j];
        }
        double psNorm = Math.sqrt(psNormSq);
        sigma *= Math.exp((cSigma / dSigma) * (psNorm / chiN - 1.0));
        // Keep σ in a sane band: never collapse to numerically-dead steps, never explode.
        sigma = Math.max(sigma0 * 1e-3, Math.min(sigma0 * 100.0, sigma));

        // 6. Covariance path (with stall guard h_σ) + rank-1/rank-μ diagonal update.
        double expectedLen = Math.sqrt(1.0 - Math.pow(1.0 - cSigma, 2.0 * (generation + 1)));
        boolean hSig = psNorm / (expectedLen * chiN) < 1.4 + 2.0 / (n + 1.0);
        double ccComp = Math.sqrt(cc * (2.0 - cc) * muEff);
        for (int j = 0; j < n; j++) {
            pC[j] = (1.0 - cc) * pC[j] + (hSig ? ccComp * yW[j] : 0.0);
        }
        for (int j = 0; j < n; j++) {
            double rankMu = 0;
            for (int i = 0; i < mu; i++) {
                double y = ys[order[i]][j];
                rankMu += recombWeights[i] * y * y;
            }
            // (1-h_σ) correction keeps total variance neutral when the path was stalled.
            double hSigCorrection = hSig ? 0.0 : cc * (2.0 - cc) * diagC[j];
            diagC[j] = (1.0 - c1 - cMu) * diagC[j]
                     + c1 * (pC[j] * pC[j] + hSigCorrection)
                     + cMu * rankMu;
            diagC[j] = Math.max(1e-12, diagC[j]);
        }

        // 7. Stagnation escape: no best-ever improvement for a whole window → the
        //    distribution has very likely contracted into a local basin; re-inflate σ
        //    and reset the paths so the search genuinely re-diversifies. (The mean and
        //    best-so-far champion are kept — this never throws progress away.)
        if (bmax > bestEver + 1e-9) {
            bestEver = bmax;
            gensSinceImprovement = 0;
        } else if (++gensSinceImprovement >= STAGNATION_WINDOW) {
            sigma = Math.min(sigma0 * 100.0, Math.max(sigma * STAGNATION_SIGMA_BOOST, sigma0));
            Arrays.fill(pSigma, 0.0);
            Arrays.fill(pC, 0.0);
            gensSinceImprovement = 0;
            restarts++;
        }

        generation++;
    }

    private volatile double lastBestFitness = 0, lastMeanFitness = 0, lastStdDev = 0;
    private volatile List<NeuralAgent> lastTopAgents = List.of();
    private volatile double[] lastBestSample = null;
    private volatile int restarts = 0;

    /** The best evaluated individual from the latest generation (falls back to the
     *  distribution mean before generation 1). */
    public NeuralAgent bestAgent() {
        double[] s = lastBestSample;
        return buildAgent(s != null ? s : mean);
    }
    public int         generation()  { return generation; }
    public double      bestFitness() { return lastBestFitness; }
    public double      meanFitness() { return lastMeanFitness; }
    /** Population fitness spread (standard deviation) from the most recent generation. */
    public double      stdDevFitness() { return lastStdDev; }
    /** Offspring population size (λ). */
    public int         lambda() { return lambda; }
    /** Current global step size σ — the live "how wide is the search" signal. */
    public double      currentSigma() { return sigma; }
    /** How many times stagnation forced a σ re-inflation. */
    public int         restartCount() { return restarts; }

    /** Top-{@code topN} offspring from the most recently completed generation. */
    public List<AgentPlugin> topAgents(int topN) {
        List<NeuralAgent> snap = lastTopAgents;
        int n = Math.min(Math.max(0, topN), snap.size());
        return new ArrayList<>(snap.subList(0, n));
    }

    private NeuralAgent buildAgent(double[] weights) {
        MlpPolicy p = new MlpPolicy(INPUT_SIZE, HIDDEN_SIZE, OUTPUT_BINS);
        p.setWeights(weights);
        return new NeuralAgent(p);
    }

    @Override
    public void close() { pool.shutdownNow(); }
}
