package dev.suika.ai;

import dev.suika.env.ActionSpace;

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
 *   <li>Evaluate all genomes in parallel (headless envs on a virtual-thread pool).</li>
 *   <li>Select the top {@code eliteCount} survivors.</li>
 *   <li>Fill the rest of the population via tournament selection + Gaussian mutation.</li>
 * </ol>
 */
public final class GeneticTrainer implements TrainerPlugin, AutoCloseable {

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
        this.populationSize  = populationSize;
        this.eliteCount      = eliteCount;
        this.mutationSigma   = mutationSigma;
        this.episodesPerEval = episodesPerEval;
        this.tournamentSize  = Math.max(2, populationSize / 10);
        this.rng             = new Random(seed);
        this.evaluator       = new FitnessEvaluator(episodesPerEval, 300, OUTPUT_BINS);
        this.pool            = Executors.newVirtualThreadPerTaskExecutor();
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
        // 1. Evaluate in parallel
        List<Future<Double>> futures = new ArrayList<>(populationSize);
        for (int i = 0; i < populationSize; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                NeuralAgent agent = buildAgent(population[idx]);
                return evaluator.evaluate(agent, (long) generation * populationSize + idx);
            }));
        }
        for (int i = 0; i < populationSize; i++) {
            try { fitness[i] = futures.get(i).get(); }
            catch (Exception e) { throw new RuntimeException(e); }
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

        // 4. Tournament selection + mutation
        for (int i = eliteCount; i < populationSize; i++) {
            int winner = tournamentSelect(order);
            next[i]    = mutate(population[winner]);
        }

        population = next;
        fitness    = nextFit;
        generation++;
    }

    private int tournamentSelect(Integer[] sortedOrder) {
        int best = sortedOrder[rng.nextInt(sortedOrder.length)];
        for (int k = 1; k < tournamentSize; k++) {
            int candidate = sortedOrder[rng.nextInt(sortedOrder.length)];
            if (fitness[candidate] > fitness[best]) best = candidate;
        }
        return best;
    }

    private double[] mutate(double[] parent) {
        double[] child = Arrays.copyOf(parent, parent.length);
        for (int j = 0; j < child.length; j++) child[j] += rng.nextGaussian() * mutationSigma;
        return child;
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
    public int    generation()   { return generation; }

    @Override
    public void close() { pool.shutdownNow(); }
}
