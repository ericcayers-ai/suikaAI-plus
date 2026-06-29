package dev.suika.game;

import dev.suika.ai.CmaEsTrainer;
import dev.suika.ai.FitnessEvaluator;
import dev.suika.ai.GeneticTrainer;

/**
 * Control center for the gradient-free learners (Neuroevolution GA, CMA-ES, PBT).
 *
 * <p>A background worker evolves the population generation-by-generation (each
 * generation evaluates the whole population in parallel on a virtual-thread pool).
 * After every generation the new champion is hot-swapped into the live board, and the
 * best/mean fitness curve streams to the diagnostics chart — you watch the AI get
 * better in real time.
 */
public final class EvolutionRunner extends AgentRunner {

    private GeneticTrainer ga;
    private CmaEsTrainer   cma;
    private final boolean  isCma;

    private final LiveChart fitnessChart = new LiveChart(260);
    private final FitnessEvaluator evaluator = new FitnessEvaluator(1, 250, 32);

    private volatile int    generation = 0;
    private volatile double bestFit = 0, meanFit = 0, bestSoFar = 0;
    private volatile boolean running = false;
    private Thread worker;

    public EvolutionRunner(SuikaGame game, PlaygroundConfig cfg) {
        super(game, cfg);
        this.isCma = cfg.technique == AiTechnique.CMA_ES;
    }

    @Override
    public void start() {
        super.start();
        if (isCma) {
            cma = new CmaEsTrainer(0.3, 2, seed);
        } else {
            int pop = Math.max(8, cfg.populationSize);
            ga = new GeneticTrainer(pop, Math.max(2, pop / 6), cfg.mutationSigma, 1, seed);
        }
        running = true;
        worker = new Thread(this::trainLoop, "evolution-trainer");
        worker.setDaemon(true);
        worker.start();
    }

    private void trainLoop() {
        while (running) {
            try {
                if (isCma) {
                    cma.update();
                    generation = cma.generation();
                    var champ = cma.bestAgent();
                    bestFit = evaluator.evaluate(champ, 9000L + generation);
                    setAgent(champ);
                } else {
                    ga.update();
                    generation = ga.generation();
                    bestFit = ga.bestFitness();
                    meanFit = ga.meanFitness();
                    setAgent(ga.bestAgent());
                }
                bestSoFar = Math.max(bestSoFar, bestFit);
                fitnessChart.add((float) bestSoFar);
            } catch (Exception e) {
                running = false;
            }
        }
    }

    @Override public String title()    { return cfg.technique.display; }
    @Override public String subtitle() { return "Evolution  ·  JVM  ·  gen " + generation; }

    @Override
    public String[] stats() {
        return new String[]{
            "generation   " + generation,
            "best fitness " + Math.round(bestSoFar),
            (isCma ? "mode         separable CMA-ES" : "mean fitness " + Math.round(meanFit)),
            "population   " + (isCma ? "auto (λ)" : Math.max(8, cfg.populationSize)),
            "eval threads " + cfg.parallelism + " (virtual pool)",
            "champion sc. " + core.getScore(),
            "speed        " + cfg.speedLabel(),
        };
    }

    @Override public LiveChart chart2()      { return fitnessChart; }
    @Override public String    chart2Label() { return "best fitness  ·  " + Math.round(bestSoFar); }

    @Override
    public void dispose() {
        running = false;
        if (worker != null) worker.interrupt();
        if (ga  != null) ga.close();
        if (cma != null) cma.close();
    }
}
