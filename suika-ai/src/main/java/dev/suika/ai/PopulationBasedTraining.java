package dev.suika.ai;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Population-Based Training (PBT) — ROADMAP §IV.10.
 *
 * <p>Runs K agents in parallel. Periodically:
 * <ul>
 *   <li>The bottom 20% of performers copy the top 20%'s weights.</li>
 *   <li>Hyperparameters are perturbed by a small factor.</li>
 * </ul>
 * Jointly optimises policy weights and hyperparameters; requires no additional HPO loop.
 */
public final class PopulationBasedTraining {

    public record Member(
            int      id,
            MlpPolicy policy,
            AgentConfig config,
            double   fitness
    ) {}

    private final int                   populationSize;
    private final List<Member>          population;
    private final FitnessEvaluator      evaluator;
    private final Random                rng;
    private int                         generation = 0;

    private static final double PERTURB_FACTOR = 0.2;
    private static final double REPLACE_FRACTION = 0.2;

    public PopulationBasedTraining(int populationSize, long seed) {
        this.populationSize = populationSize;
        this.evaluator = new FitnessEvaluator(2, 100, 32);
        this.rng       = new Random(seed);
        this.population = new CopyOnWriteArrayList<>();
        initPopulation();
    }

    private void initPopulation() {
        for (int i = 0; i < populationSize; i++) {
            MlpPolicy p = new MlpPolicy(
                    dev.suika.env.StateObservationEncoder.TOTAL, 64, 32);
            p.initRandom(rng);
            double lr = 1e-4 + rng.nextDouble() * 9e-4;
            AgentConfig cfg = new AgentConfig("neural-mlp", Map.of("learning_rate", lr));
            population.add(new Member(i, p, cfg, 0.0));
        }
    }

    /**
     * Run one PBT iteration: evaluate all members, then exploit+explore.
     */
    public void step(long baseSeed) {
        // Evaluate
        List<Member> evaluated = new ArrayList<>();
        for (Member m : population) {
            NeuralAgent a = new NeuralAgent(m.policy());
            double fit = evaluator.evaluate(a, baseSeed + m.id());
            evaluated.add(new Member(m.id(), m.policy(), m.config(), fit));
        }

        // Sort by fitness
        evaluated.sort((a, b) -> Double.compare(b.fitness(), a.fitness()));

        // Replace bottom fraction with top fraction (exploit)
        int replaceCount = Math.max(1, (int) (populationSize * REPLACE_FRACTION));
        for (int i = 0; i < replaceCount; i++) {
            Member top    = evaluated.get(i);
            int bottomIdx = populationSize - 1 - i;
            Member bottom = evaluated.get(bottomIdx);

            // Copy weights
            double[] w = top.policy().getWeights();
            bottom.policy().setWeights(Arrays.copyOf(w, w.length));

            // Perturb hyperparams (explore)
            Map<String, Object> newHp = new HashMap<>(bottom.config().hyperparameters());
            double oldLr = ((Number) newHp.getOrDefault("learning_rate", 3e-4)).doubleValue();
            double factor = 1.0 + (rng.nextDouble() * 2 - 1.0) * PERTURB_FACTOR;
            newHp.put("learning_rate", oldLr * factor);

            // Re-create the config and member to apply the changes
            AgentConfig newCfg = new AgentConfig(bottom.config().agentId(), newHp);
            Member perturbedBottom = new Member(bottom.id(), bottom.policy(), newCfg, bottom.fitness());
            evaluated.set(bottomIdx, perturbedBottom);
        }

        population.clear();
        population.addAll(evaluated);
        generation++;
    }

    public Member bestMember() { return population.get(0); }
    public int    generation() { return generation; }
    public List<Member> members() { return Collections.unmodifiableList(population); }
}