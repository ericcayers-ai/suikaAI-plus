package dev.suika.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Genetic Algorithm over MLP weights (ROADMAP §IV.5).
 *
 * <p>Runs 100% on the JVM — no Python dependency. Each generation:
 * <ol>
 *   <li>Evaluate all genomes in parallel (headless envs on a bounded thread pool).</li>
 *   <li>Select the top {@code eliteCount} survivors.</li>
 *   <li>Fill the rest of the population via the configured {@link Selection}
 *       strategy (+ optional uniform crossover) and Gaussian mutation
 *       (optionally annealed).</li>
 * </ol>
 *
 * <p>The selection strategies are the three classic, mathematically-grounded
 * parent-selection rules from the GA literature:
 * <ul>
 *   <li>{@link Selection#TOURNAMENT} — sample k, keep the fittest. Selection
 *       pressure is set by k; robust to fitness scaling.</li>
 *   <li>{@link Selection#RANK} — roulette over rank (linear ranking, Baker 1985):
 *       probability proportional to position, immune to outlier fitness values.</li>
 *   <li>{@link Selection#BOLTZMANN} — softmax over fitness (Boltzmann selection):
 *       P(i) ∝ exp(f_i / T), the maximum-entropy "top mathematical probability"
 *       assignment for a given expected fitness; temperature T is derived from the
 *       population's own fitness spread so it self-scales as scores grow.</li>
 * </ul>
 */
public final class GeneticTrainer implements TrainerPlugin, AutoCloseable {

    /** Parent-selection mathematics — see the class doc for each rule's definition. */
    public enum Selection {
        TOURNAMENT("Tournament"), RANK("Rank roulette"), BOLTZMANN("Boltzmann (softmax)");
        public final String label;
        Selection(String label) { this.label = label; }
    }

    // --- Architecture ---
    private static final int INPUT_SIZE  = dev.suika.env.StateObservationEncoder.TOTAL;
    private static final int HIDDEN_SIZE = 64;
    private static final int OUTPUT_BINS = 32;

    // --- GA hyper-params ---
    private final int    populationSize;
    private final int    eliteCount;
    private final double mutationSigma;
    private final int    episodesPerEval;
    private final int    tournamentSize;
    private final Selection selection;
    private final boolean crossover;
    private final boolean sigmaAnneal;

    private final Random           rng;
    private final FitnessEvaluator evaluator;
    private final ExecutorService  pool;

    private double[][] population; // each row = flat weight vector
    private double[]   fitness;
    private int        generation = 0;

    /** No-arg constructor for {@link java.util.ServiceLoader} discovery; uses default hyperparams. */
    public GeneticTrainer() { this(20, 4, 0.1, 2, 0L); }

    public GeneticTrainer(int populationSize, int eliteCount, double mutationSigma,
                          int episodesPerEval, long seed) {
        this(populationSize, eliteCount, mutationSigma, episodesPerEval, seed, 0);
    }

    public GeneticTrainer(int populationSize, int eliteCount, double mutationSigma,
                          int episodesPerEval, long seed, int threads) {
        this(populationSize, eliteCount, mutationSigma, episodesPerEval, seed, threads,
                Selection.TOURNAMENT, true, false);
    }

    /**
     * @param threads worker threads for the evaluation pool. {@code 0} = auto (all cores).
     *                A <em>bounded</em> pool is used so large populations (≤1000) evaluate
     *                in capped-concurrency waves instead of spawning one live simulation per
     *                genome at once — which previously exhausted the heap (OOM).
     * @param selection  parent-selection strategy (see {@link Selection})
     * @param crossover  when true, each child recombines TWO selected parents via uniform
     *                   crossover before mutation; when false, reproduction is asexual
     *                   (single parent + mutation), the pre-v0.12 behavior
     * @param sigmaAnneal when true, mutation σ decays as σ·0.995^generation (floored at
     *                    σ/10) — broad exploration early, fine-tuning once converging
     */
    public GeneticTrainer(int populationSize, int eliteCount, double mutationSigma,
                          int episodesPerEval, long seed, int threads,
                          Selection selection, boolean crossover, boolean sigmaAnneal) {
        this.populationSize  = populationSize;
        this.eliteCount      = eliteCount;
        this.mutationSigma   = mutationSigma;
        this.episodesPerEval = Math.max(1, episodesPerEval);
        this.tournamentSize  = Math.max(2, populationSize / 10);
        this.selection       = selection;
        this.crossover       = crossover;
        this.sigmaAnneal     = sigmaAnneal;
        this.rng             = new Random(seed);
        this.evaluator       = new FitnessEvaluator(this.episodesPerEval, 300, OUTPUT_BINS);
        int workers          = threads > 0 ? threads : Math.max(1, Runtime.getRuntime().availableProcessors());
        this.pool            = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "ga-eval");
            t.setDaemon(true);
            return t;
        });
        initPopulation();
    }

    @Override public String id() { return "genetic"; }

    @Override
    public void observe(dev.suika.core.StepResult transition) { /* GA ignores online transitions */ }

    @Override
    public void update() { evolveOneGeneration(); }

    // -------------------------------------------------------------------------

    private void initPopulation() {
        int nParams = MlpPolicy.paramCount(INPUT_SIZE, HIDDEN_SIZE, OUTPUT_BINS);
        population  = new double[populationSize][nParams];
        fitness     = new double[populationSize];

        for (double[] genome : population) {
            MlpPolicy tmp = new MlpPolicy(INPUT_SIZE, HIDDEN_SIZE, OUTPUT_BINS);
            tmp.initRandom(rng);
            System.arraycopy(tmp.getWeights(), 0, genome, 0, nParams);
        }
    }

    private void evolveOneGeneration() {
        // 1. Evaluate in parallel. Every (genome, episode) pair is its own task, so all
        //    sims for the generation run simultaneously — bounded by the pool's thread
        //    count rather than spawning one live simulation per genome at once.
        long[][] scores = new long[populationSize][episodesPerEval];
        List<Future<?>> futures = new ArrayList<>(populationSize * episodesPerEval);
        for (int i = 0; i < populationSize; i++) {
            final int idx = i;
            for (int ep = 0; ep < episodesPerEval; ep++) {
                final int e = ep;
                // Common Random Numbers (see CmaEsTrainer): every genome in a generation is
                // scored on the SAME boards so selection compares policies, not board luck;
                // the seed rotates per generation so the population can't overfit one board.
                final long seed = (long) generation * episodesPerEval + e;
                futures.add(pool.submit(() ->
                        scores[idx][e] = evaluator.runSingleEpisode(buildAgent(population[idx]), seed)));
            }
        }
        for (Future<?> f : futures) {
            try { f.get(); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
        for (int i = 0; i < populationSize; i++) {
            long sum = 0; for (long v : scores[i]) sum += v;
            fitness[i] = (double) sum / episodesPerEval;
        }

        // 2. Sort by fitness descending
        Integer[] order = new Integer[populationSize];
        for (int i = 0; i < populationSize; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingDouble((Integer i) -> fitness[i]).reversed());

        double[][] next    = new double[populationSize][];
        double[]   nextFit = new double[populationSize];

        // 3. Elite pass-through
        for (int e = 0; e < eliteCount; e++) {
            next[e]    = Arrays.copyOf(population[order[e]], population[order[e]].length);
            nextFit[e] = fitness[order[e]];
        }

        // 4. Selection (+ optional crossover) + mutation
        double sigma = currentSigma();
        for (int i = eliteCount; i < populationSize; i++) {
            int parentA = select(order);
            double[] genome;
            if (crossover) {
                int parentB = select(order);
                genome = uniformCrossover(population[parentA], population[parentB]);
            } else {
                genome = Arrays.copyOf(population[parentA], population[parentA].length);
            }
            mutateInPlace(genome, sigma);
            next[i] = genome;
        }

        population = next;
        fitness    = nextFit;
        generation++;
    }

    /** Mutation σ for THIS generation, honouring the anneal schedule. */
    public double currentSigma() {
        if (!sigmaAnneal) return mutationSigma;
        return Math.max(mutationSigma / 10.0, mutationSigma * Math.pow(0.995, generation));
    }

    private int select(Integer[] sortedOrder) {
        return switch (selection) {
            case TOURNAMENT -> tournamentSelect(sortedOrder);
            case RANK       -> rankSelect(sortedOrder);
            case BOLTZMANN  -> boltzmannSelect();
        };
    }

    private int tournamentSelect(Integer[] sortedOrder) {
        int best = sortedOrder[rng.nextInt(sortedOrder.length)];
        for (int k = 1; k < tournamentSize; k++) {
            int candidate = sortedOrder[rng.nextInt(sortedOrder.length)];
            if (fitness[candidate] > fitness[best]) best = candidate;
        }
        return best;
    }

    /** Linear ranking (Baker 1985): the best-ranked genome gets weight n, the worst 1;
     *  a roulette spin over those weights picks the parent. Scale-free. */
    private int rankSelect(Integer[] sortedOrder) {
        int n = sortedOrder.length;
        long total = (long) n * (n + 1) / 2;
        long spin = (long) (rng.nextDouble() * total);
        long acc = 0;
        for (int r = 0; r < n; r++) {
            acc += n - r;                 // rank 0 (best) weighs n, last weighs 1
            if (spin < acc) return sortedOrder[r];
        }
        return sortedOrder[0];
    }

    /** Boltzmann/softmax selection: P(i) ∝ exp((f_i − f_max)/T) with T set to the
     *  population's fitness std-dev (floored), so pressure self-scales as scores grow. */
    private int boltzmannSelect() {
        double max = Arrays.stream(fitness).max().orElse(0);
        double t = Math.max(1.0, fitnessStdDev());
        double[] p = new double[populationSize];
        double sum = 0;
        for (int i = 0; i < populationSize; i++) {
            p[i] = Math.exp((fitness[i] - max) / t);
            sum += p[i];
        }
        double spin = rng.nextDouble() * sum, acc = 0;
        for (int i = 0; i < populationSize; i++) {
            acc += p[i];
            if (spin < acc) return i;
        }
        return populationSize - 1;
    }

    /** Uniform crossover: each gene comes from parent A or B with equal probability. */
    private double[] uniformCrossover(double[] a, double[] b) {
        double[] child = new double[a.length];
        for (int j = 0; j < a.length; j++) child[j] = rng.nextBoolean() ? a[j] : b[j];
        return child;
    }

    private void mutateInPlace(double[] genome, double sigma) {
        for (int j = 0; j < genome.length; j++) genome[j] += rng.nextGaussian() * sigma;
    }

    private NeuralAgent buildAgent(double[] weights) {
        MlpPolicy p = new MlpPolicy(INPUT_SIZE, HIDDEN_SIZE, OUTPUT_BINS);
        p.setWeights(weights);
        return new NeuralAgent(p);
    }

    /** Returns the current best genome as a ready-to-use agent. */
    public NeuralAgent bestAgent() {
        int best = 0;
        for (int i = 1; i < populationSize; i++) if (fitness[i] > fitness[best]) best = i;
        return buildAgent(population[best]);
    }

    public double bestFitness()  { return Arrays.stream(fitness).max().orElse(0); }
    public double meanFitness()  { return Arrays.stream(fitness).average().orElse(0); }
    /** Population fitness spread (standard deviation) — a live diversity signal. */
    public double fitnessStdDev() {
        double mean = meanFitness();
        double sq = Arrays.stream(fitness).map(v -> (v - mean) * (v - mean)).sum();
        return Math.sqrt(sq / Math.max(1, fitness.length));
    }
    /** Configured population size. */
    public int populationSize() { return populationSize; }
    public int    generation()   { return generation; }
    public Selection selectionStrategy() { return selection; }
    public boolean crossoverEnabled()    { return crossover; }
    public boolean sigmaAnnealEnabled()  { return sigmaAnneal; }

    /** Returns the top-{@link #eliteCount} agents by fitness (index 0 = champion). */
    public List<AgentPlugin> eliteAgents() { return eliteAgents(eliteCount); }

    /**
     * Returns the top-{@code topN} agents by fitness (index 0 = champion), independent
     * of the breeding elite count — used so the UI can show more (or fewer) live
     * "ghost" boards than the GA actually keeps for reproduction.
     */
    public List<AgentPlugin> eliteAgents(int topN) {
        Integer[] order = new Integer[populationSize];
        for (int i = 0; i < populationSize; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingDouble((Integer i) -> fitness[i]).reversed());
        int n = Math.min(Math.max(0, topN), populationSize);
        List<AgentPlugin> result = new ArrayList<>(n);
        for (int e = 0; e < n; e++) result.add(buildAgent(population[order[e]]));
        return result;
    }

    @Override
    public void close() { pool.shutdownNow(); }
}
