package dev.suika.game;

import dev.suika.ai.AgentPlugin;
import dev.suika.ai.CmaEsTrainer;
import dev.suika.ai.FitnessEvaluator;
import dev.suika.ai.GeneticTrainer;
import dev.suika.core.GameCore;
import dev.suika.core.GameState;
import dev.suika.core.PhysicsConfig;

import java.util.ArrayList;
import java.util.List;

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

    // Ghost boards: up to 3 extra parallel games driven by recent top agents
    private static final int GHOST_COUNT = 3;
    private final GameCore[]   ghostCores   = new GameCore[GHOST_COUNT];
    private final AgentPlugin[] ghostAgents = new AgentPlugin[GHOST_COUNT];
    private final double[]  ghostAccum      = new double[GHOST_COUNT];
    private final float[]   ghostTimer      = new float[GHOST_COUNT];
    private volatile List<AgentPlugin> topAgents = new ArrayList<>();

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
        initGhosts();
    }

    private void initGhosts() {
        for (int i = 0; i < GHOST_COUNT; i++) {
            ghostCores[i] = new GameCore(seed + i + 1);
            ghostTimer[i] = (i + 1) * 0.3f;
        }
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
                    topAgents = List.of(champ);
                } else {
                    ga.update();
                    generation = ga.generation();
                    bestFit = ga.bestFitness();
                    meanFit = ga.meanFitness();
                    setAgent(ga.bestAgent());
                    // expose top agents for ghost view (champion is already in agent())
                    var elites = ga.eliteAgents();
                    topAgents = elites != null && elites.size() > 1
                            ? elites.subList(1, Math.min(elites.size(), GHOST_COUNT + 1))
                            : List.of();
                }
                bestSoFar = Math.max(bestSoFar, bestFit);
                fitnessChart.add((float) bestSoFar);
                // refresh ghost agents
                List<AgentPlugin> ta = topAgents;
                for (int i = 0; i < GHOST_COUNT; i++) {
                    ghostAgents[i] = i < ta.size() ? ta.get(i) : agent();
                }
            } catch (Exception e) {
                running = false;
            }
        }
    }

    /**
     * Advance ghost games one physics tick + ghost-agent drop cadence.
     * Ghost cores always run — they feed both the overlay view (ghostView=true)
     * and the 4-quadrant grid view (ghostView=false).
     */
    @Override
    protected void onUpdate(float dt) {
        super.onUpdate(dt);
        for (int i = 0; i < GHOST_COUNT; i++) {
            GameCore gc = ghostCores[i];
            if (gc == null) continue;
            // physics
            ghostAccum[i] += dt * speed;
            while (ghostAccum[i] >= PhysicsConfig.FIXED_DT) {
                gc.tick();
                ghostAccum[i] -= PhysicsConfig.FIXED_DT;
            }
            // auto-restart ghost if game over
            if (gc.isGameOver()) {
                ghostCores[i] = new GameCore(seed + i + 1 + generation);
                ghostTimer[i] = 0.3f;
                continue;
            }
            // ghost agent drops
            ghostTimer[i] -= dt;
            if (ghostTimer[i] <= 0f && ghostAgents[i] != null) {
                AgentPlugin ghostAgent = ghostAgents[i];
                GameCore snap = gc.snapshot();
                dev.suika.ai.ActionSpec spec = dev.suika.ai.ActionSpec.discrete(cfg.actionBins);
                Object act = ghostAgent.selectAction(snap, spec);
                double dropX = spec.toDropX(act, PhysicsConfig.DROP_X_MIN, PhysicsConfig.DROP_X_MAX);
                gc.spawnDrop(dropX);
                ghostTimer[i] = baseDelay() * 1.2f;
            }
        }
    }

    /** Returns live game-states for the ghost boards (overlay mode; null entries skipped). */
    public GameState[] ghostStates() {
        if (!cfg.ghostView) return null;
        GameState[] arr = new GameState[GHOST_COUNT];
        for (int i = 0; i < GHOST_COUNT; i++) arr[i] = ghostCores[i] != null ? ghostCores[i].getState() : null;
        return arr;
    }

    /**
     * Returns all 4 live game-states: [0]=champion, [1-3]=top elites.
     * Used by the 4-quadrant grid when ghostView is off.
     */
    public GameState[] topStates() {
        GameState[] arr = new GameState[4];
        arr[0] = core.getState();
        for (int i = 0; i < GHOST_COUNT; i++) arr[i + 1] = ghostCores[i] != null ? ghostCores[i].getState() : null;
        return arr;
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
