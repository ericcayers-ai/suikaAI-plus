package dev.suika.game;

import dev.suika.ai.AgentPlugin;
import dev.suika.ai.CmaEsTrainer;
import dev.suika.ai.GeneticTrainer;
import dev.suika.ai.MlpPolicy;
import dev.suika.ai.NeuralAgent;
import dev.suika.core.GameCore;
import dev.suika.core.GameState;
import dev.suika.core.PhysicsConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

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

    private final LiveChart fitnessChart   = new LiveChart(260);
    private final LiveChart meanFitChart   = new LiveChart(260);
    private final LiveChart diversityChart = new LiveChart(260);

    private volatile int    generation = 0;
    private volatile double bestFit = 0, meanFit = 0, bestSoFar = 0;
    private volatile boolean running = false;
    private Thread worker;
    private volatile long trainStartNs = 0;

    /**
     * True right after {@link #loadFromSlot}: the trainer skips publishing its own
     * champion so the just-loaded one keeps playing, WITHOUT stopping the board itself
     * (previously this used {@code setPaused(true)}, which froze physics entirely —
     * the loaded champion just sat there motionless, reading as "the app hung"). The
     * board, ghosts, and even the trainer's background generations all keep running;
     * only the one-line "adopt this generation's champion" call is skipped, and it
     * clears automatically the moment the user hits PAUSE/RESUME, SETUP-close, or
     * RESTART — whichever they reach for first to say "ok, resume normal play".
     */
    private volatile boolean holdChampion = false;

    /**
     * The trainer's most recently published best agent (highest fitness so far),
     * stashed by the worker thread so the render thread can re-adopt it WITHOUT reaching
     * into {@link GeneticTrainer}/{@link CmaEsTrainer} internals concurrently. Used by
     * {@link #onNewGame()} to guarantee that whenever the displayed champion board
     * fails and auto-restarts, it comes back playing the current best — you always keep
     * watching the highest-scoring agent so far, never a dead or stale one.
     */
    private volatile AgentPlugin latestChampion;

    /**
     * Distinguishes worker "generations" across a RESTART. A shared boolean alone is
     * racy here: an old worker thread, interrupted mid-iteration by restart(), could
     * still be inside its catch block setting {@code running = false} right as the new
     * worker (spawned moments later by the same restart()) sets it back to true —
     * whichever write lands last wins, and could silently kill the new trainer. Each
     * worker instead captures its own epoch snapshot at spawn time and only keeps
     * looping while the shared counter still matches it; a dying old worker can never
     * affect a newer one because it only ever compares against its own value.
     */
    private final AtomicLong epoch = new AtomicLong(0);

    // Ghost boards: extra parallel games driven by recent top agents, alongside the
    // champion. Count comes from cfg.eliteViewCount() (1-16, technique config) and is
    // (re)allocated fresh in start()/restart() rather than fixed at construction, since
    // the configured count can change between launches.
    private int ghostCount;
    private GameCore[]    ghostCores;
    private AgentPlugin[] ghostAgents;
    private double[]      ghostAccum;
    private float[]       ghostTimer;
    private int[]         ghostStartGen; // generation this ghost's game began
    private volatile List<AgentPlugin> topAgents = new ArrayList<>();

    public EvolutionRunner(SuikaGame game, PlaygroundConfig cfg) {
        super(game, cfg);
        this.isCma = cfg.technique == AiTechnique.CMA_ES;
    }

    @Override
    public void start() {
        super.start();
        int threads = cfg.evalThreads();
        int sims    = Math.max(1, cfg.simsPerGen());
        if (isCma) {
            cma = new CmaEsTrainer(0.3, sims, seed, threads);
        } else {
            int pop = Math.max(8, cfg.populationSize);
            ga = new GeneticTrainer(pop, Math.max(2, pop / 6), cfg.mutationSigma, sims, seed, threads,
                    cfg.selection(), cfg.crossover, cfg.sigmaAnneal);
        }
        ghostCount    = Math.max(0, cfg.eliteViewCount() - 1);
        ghostCores    = new GameCore[ghostCount];
        ghostAgents   = new AgentPlugin[ghostCount];
        ghostAccum    = new double[ghostCount];
        ghostTimer    = new float[ghostCount];
        ghostStartGen = new int[ghostCount];
        trainStartNs = System.nanoTime();
        running = true;
        long myEpoch = epoch.incrementAndGet();
        worker = new Thread(() -> trainLoop(myEpoch), "evolution-trainer");
        worker.setDaemon(true);
        worker.start();
        initGhosts();
    }

    /**
     * Unlike the base RESTART (which only resets the live board), evolution's RESTART
     * rebuilds the trainer from whatever the current config says — population size,
     * eval threads, sims/generation. Without this, changing those knobs via the
     * quick-settings hotswap would silently do nothing to an already-running trainer.
     */
    @Override
    public void restart() {
        epoch.incrementAndGet();   // old worker's loop condition goes false on its next check
        if (worker != null) worker.interrupt();
        if (ga  != null) { ga.close();  ga  = null; }
        if (cma != null) { cma.close(); cma = null; }
        generation = 0;
        bestFit = 0; meanFit = 0; bestSoFar = 0;
        fitnessChart.clear();
        meanFitChart.clear();
        diversityChart.clear();
        topAgents = new ArrayList<>();
        holdChampion = false;
        latestChampion = null;   // don't let onNewGame re-adopt a pre-restart champion
        start();
    }

    /** Pausing/resuming (the control bar's PAUSE button) is also how the user says
     *  "done looking at the loaded champion, resume normal evolving" — clears the hold
     *  on resume so the trainer's next generation naturally takes back over. */
    @Override
    public void setPaused(boolean p) {
        super.setPaused(p);
        if (!p) holdChampion = false;
    }

    /**
     * When the displayed champion board fails and the base runner auto-restarts it,
     * re-adopt the trainer's current best agent (and release any held loaded slot) so
     * you keep watching the highest-scoring agent so far rather than replaying the dead
     * one. Reads the volatile {@link #latestChampion} the worker publishes — never
     * touches trainer internals from this (render) thread.
     */
    @Override
    protected void onNewGame() {
        super.onNewGame();
        holdChampion = false;
        AgentPlugin best = latestChampion;
        if (best != null) setAgent(best);
    }

    private void initGhosts() {
        for (int i = 0; i < ghostCount; i++) {
            ghostCores[i] = new GameCore(seed + i + 1);
            ghostTimer[i] = (i + 1) * 0.3f;
            ghostStartGen[i] = 0;
            // Seed every ghost with the current live agent so it DROPS from frame one
            // instead of sitting empty until generation 1 finishes publishing its top
            // agents — the "ghosts don't appear" bug, worst at high sims/generation where
            // gen 0 can take many seconds. Once the trainer publishes elites, trainLoop
            // swaps each ghost onto its own elite; until then they all mirror the champion.
            ghostAgents[i] = agent();
        }
    }

    private void trainLoop(long myEpoch) {
        while (running && epoch.get() == myEpoch) {
            try {
                if (isCma) {
                    cma.update();
                    generation = cma.generation();
                    latestChampion = cma.bestAgent();
                    if (!holdChampion) setAgent(latestChampion);
                    bestFit = cma.bestFitness();
                    meanFit = cma.meanFitness();
                    // this generation's top offspring, skipping index 0 (redundant with
                    // the champion, already in agent())
                    var top = cma.topAgents(ghostCount + 1);
                    topAgents = top.size() > 1 ? top.subList(1, top.size()) : List.of();
                } else {
                    ga.update();
                    generation = ga.generation();
                    bestFit = ga.bestFitness();
                    meanFit = ga.meanFitness();
                    latestChampion = ga.bestAgent();
                    if (!holdChampion) setAgent(latestChampion);
                    // expose top agents for ghost view (champion is already in agent())
                    var elites = ga.eliteAgents(ghostCount + 1);
                    topAgents = elites.size() > 1 ? elites.subList(1, elites.size()) : List.of();
                }
                bestSoFar = Math.max(bestSoFar, bestFit);
                fitnessChart.add((float) bestSoFar);
                meanFitChart.add((float) meanFit);
                diversityChart.add((float) diversitySigma());
                // refresh ghost agents
                List<AgentPlugin> ta = topAgents;
                for (int i = 0; i < ghostCount; i++) {
                    ghostAgents[i] = i < ta.size() ? ta.get(i) : agent();
                }
            } catch (Exception e) {
                break; // this worker's run is over — don't touch the shared 'running' flag
            }
        }
    }

    // Ghost boards are fully independent GameCore instances — each iteration below
    // only ever touches its own index i across every array, so fanning them out across
    // a small worker pool is safe with no synchronization. At up to 16 elite views this
    // used to run all physics + agent-decision work for every ghost sequentially on the
    // RENDER thread each frame (up to 240 ticks x 15 ghosts = 3600 dyn4j steps before a
    // single frame could present) — real stutter at high elite-view counts, worse at
    // high speed. A dedicated pool (not the JVM-wide common ForkJoinPool, so this never
    // contends with unrelated parallel work elsewhere) spreads that across cores instead.
    private static final int GHOST_POOL_THREADS = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
    private final java.util.concurrent.ExecutorService ghostPool = java.util.concurrent.Executors.newFixedThreadPool(
            GHOST_POOL_THREADS, r -> { Thread t = new Thread(r, "evolution-ghost"); t.setDaemon(true); return t; });

    /**
     * Advance ghost games one physics tick + ghost-agent drop cadence.
     * Ghost cores always run — they feed both the overlay view (ghostView=true)
     * and the 4-quadrant grid view (ghostView=false).
     */
    @Override
    protected void onUpdate(float dt) {
        super.onUpdate(dt);
        if (ghostCount == 0) return;
        java.util.List<java.util.concurrent.Future<?>> futures = new ArrayList<>(ghostCount);
        for (int i = 0; i < ghostCount; i++) {
            final int idx = i;
            futures.add(ghostPool.submit(() -> updateGhost(idx, dt)));
        }
        for (var f : futures) {
            try { f.get(); } catch (Exception e) { /* a single ghost erroring shouldn't sink the frame */ }
        }
    }

    private void updateGhost(int i, float dt) {
        GameCore gc = ghostCores[i];
        if (gc == null) return;
        // physics (capped per frame so 1024× speed doesn't freeze the UI)
        ghostAccum[i] += Math.min(dt * speed, 4.0);
        int gsteps = 0;
        while (ghostAccum[i] >= PhysicsConfig.FIXED_DT && gsteps < 240) {
            gc.tick();
            ghostAccum[i] -= PhysicsConfig.FIXED_DT;
            gsteps++;
        }
        // Cull a ghost whose current game has outlived the configured lineage window,
        // so it restarts on the freshest elite instead of lingering for ever.
        int cullGens = Math.max(1, cfg.ghostCullGens());
        boolean tooOld = generation - ghostStartGen[i] >= cullGens;
        // auto-restart ghost if game over OR culled by age
        if (gc.isGameOver() || tooOld) {
            ghostCores[i] = new GameCore(seed + i + 1 + generation);
            ghostTimer[i] = 0.3f;
            ghostStartGen[i] = generation;
            return;
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

    /** Returns live game-states for the ghost boards (overlay mode; null entries skipped). */
    public GameState[] ghostStates() {
        if (!cfg.ghostView) return null;
        GameState[] arr = new GameState[ghostCount];
        for (int i = 0; i < ghostCount; i++) arr[i] = ghostCores[i] != null ? ghostCores[i].getState() : null;
        return arr;
    }

    /**
     * Returns all configured live game-states: [0]=champion, [1..]=top elites.
     * Used by the auto-grid when ghostView is off.
     */
    public GameState[] topStates() {
        GameState[] arr = new GameState[ghostCount + 1];
        arr[0] = core.getState();
        for (int i = 0; i < ghostCount; i++) arr[i + 1] = ghostCores[i] != null ? ghostCores[i].getState() : null;
        return arr;
    }

    @Override public GameState[] multiStates() { return topStates(); }

    @Override
    public String[] multiLabels() {
        String[] labels = new String[ghostCount + 1];
        labels[0] = "CHAMPION  ·  " + core.getScore();
        for (int i = 0; i < ghostCount; i++) {
            labels[i + 1] = "ELITE #" + (i + 2) + "  ·  " + (ghostCores[i] != null ? ghostCores[i].getScore() : 0);
        }
        return labels;
    }

    // Evolution's three charts are the convergence proof the spec asks for: best-so-far
    // (monotone envelope), mean (population-level progress), and diversity σ (collapse
    // toward convergence). The default score chart gives way to diversity here — the
    // champion's live score is already a stats line.
    @Override public LiveChart chart1()      { return diversityChart; }
    @Override public String    chart1Label() { return "diversity σ  ·  " + Math.round(diversitySigma()); }
    @Override public LiveChart chart3()      { return meanFitChart; }
    @Override public String    chart3Label() { return "mean fitness  ·  " + Math.round(meanFit); }

    /** Wall-clock seconds since this trainer started (resets on RESTART). */
    private double trainingElapsedSeconds() {
        return trainStartNs == 0 ? 0 : (System.nanoTime() - trainStartNs) / 1_000_000_000.0;
    }

    private String elapsedLabel() {
        long s = (long) trainingElapsedSeconds();
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    private double gensPerMin() {
        double mins = trainingElapsedSeconds() / 60.0;
        return mins > 0.01 ? generation / mins : 0;
    }

    /** Population size actually being evaluated each generation (λ for CMA-ES). */
    private int evalPopulation() {
        if (isCma) return cma != null ? cma.lambda() : 0;
        return Math.max(8, cfg.populationSize);
    }

    /** Cumulative average of full game simulations completed per second. */
    private double evalsPerSec() {
        double secs = trainingElapsedSeconds();
        if (secs < 0.5) return 0;
        long totalEpisodes = (long) generation * evalPopulation() * Math.max(1, cfg.simsPerGen());
        return totalEpisodes / secs;
    }

    /** Population fitness spread (std-dev) — a live diversity signal, not just the mean. */
    private double diversitySigma() {
        if (isCma) return cma != null ? cma.stdDevFitness() : 0;
        return ga != null ? ga.fitnessStdDev() : 0;
    }

    // The landscape panel scrolls with the mouse wheel once stats() + extendedStats()
    // exceed its visible height, so there's no hard line cap — see ControlCenterScreen's
    // drawPanelText()/maxStatsScroll().
    @Override
    public String[] extendedStats() {
        java.util.List<String> s = new java.util.ArrayList<>();
        s.add("elapsed      " + elapsedLabel() + "  ·  " + String.format("%.1f", gensPerMin()) + " gens/min");
        s.add("throughput   " + Math.round(evalsPerSec()) + " games/sec (parallel)");
        s.add("diversity σ  " + Math.round(diversitySigma()) + "  ·  mutation σ "
                + String.format("%.3f", ga != null ? ga.currentSigma() : cfg.mutationSigma)
                + (cfg.sigmaAnneal && !isCma ? " (annealing)" : ""));
        if (!isCma) {
            s.add("selection    " + cfg.selection().label
                    + "  ·  crossover " + (cfg.crossover ? "uniform" : "off"));
        }
        s.add("ghost lineage " + Math.max(1, cfg.ghostCullGens()) + " gens  ·  " + (ghostCount + 1) + " views");
        s.add("genome       one MLP's weights, flattened to a single number list");
        s.add("fitness      mean score over " + Math.max(1, cfg.simsPerGen())
                + " seeded game" + (cfg.simsPerGen() > 1 ? "s" : "") + " per genome");
        s.add("eval budget  " + (evalPopulation() * Math.max(1, cfg.simsPerGen())) + " games/gen  ("
                + evalPopulation() + " pop x " + Math.max(1, cfg.simsPerGen()) + " sims)");
        s.add(isCma
                ? "search       adapts a Gaussian cloud toward top performers (no genome list)"
                : "search       breeds elites + random mutation — gradient-free");
        s.add("reads        584 numbers, not pixels: 8 global (score/tiers/fill) +");
        s.add("             9 per fruit (position, speed, spin, tier, size, asleep)");
        s.add("picks        1 of 32 drop columns (highest-scoring output wins)");
        s.add("tendency     " + tendencyLabel());
        return s.toArray(new String[0]);
    }

    /** Short plain-language description of the live evolution step (for the subtitle). */
    private String doingNow() {
        if (generation == 0) return "evaluating gen 0";
        return isCma ? "adapting distribution" : "breeding next gen";
    }

    @Override public String title()    { return cfg.technique.display; }
    @Override public String subtitle() { return "Evolution  ·  gen " + generation + "  ·  " + doingNow(); }

    @Override
    public String[] stats() {
        return new String[]{
            "generation   " + generation,
            "best fitness " + Math.round(bestSoFar) + "  (this gen " + Math.round(bestFit) + ")",
            (isCma ? "mode         separable CMA-ES" : "mean fitness " + Math.round(meanFit)),
            "population   " + evalPopulation() + (isCma ? " (λ, auto)" : ""),
            "sims/genome  " + Math.max(1, cfg.simsPerGen()),
            "eval threads " + cfg.parallelismLabel(),
            "champion sc. " + core.getScore(),
            "speed        " + cfg.speedLabel(),
        };
    }

    @Override public LiveChart chart2()      { return fitnessChart; }
    @Override public String    chart2Label() { return "best fitness  ·  " + Math.round(bestSoFar); }

    @Override
    public void dispose() {
        running = false;
        epoch.incrementAndGet();
        if (worker != null) worker.interrupt();
        if (ga  != null) ga.close();
        if (cma != null) cma.close();
        ghostPool.shutdownNow();
    }

    // -------------------------------------------------------------------------
    // Save / load — 3 slots per technique, persisted to disk (see ModelSlots).
    // -------------------------------------------------------------------------

    /** Persists the CURRENT champion's weights + best-so-far fitness into a slot, along
     *  with the fitness/mean/diversity graph history so a reloaded slot restores its
     *  charts (written to the slot folder's progress.txt — see {@link ModelSlots}). */
    public boolean saveToSlot(int slot) {
        if (!(agent() instanceof NeuralAgent na)) return false;
        java.util.Map<String, float[]> graphs = new java.util.LinkedHashMap<>();
        graphs.put("bestFitness", fitnessChart.export());
        graphs.put("meanFitness", meanFitChart.export());
        graphs.put("diversity",   diversityChart.export());
        ModelSlots.save(cfg.technique.id, slot, na.policy(), bestSoFar,
                new ModelSlots.SaveExtras(graphs));
        return true;
    }

    /**
     * Loads a slot and immediately adopts it as the live-playing agent — the board
     * keeps running (physics, ghosts, and the background trainer all stay live), only
     * the trainer's own "adopt this generation's champion" step is held off (see
     * {@link #holdChampion}) so it doesn't immediately overwrite what was just loaded.
     * Hit PAUSE/RESUME (or RESTART) to hand control back to the trainer, or just keep
     * watching — GA/CMA-ES don't have a concept of "resume evolving from an externally
     * loaded genome" (there's no population slot to put it in), so the trainer
     * continues evolving its own population in the background the whole time; only the
     * live board's displayed agent is held.
     */
    public boolean loadFromSlot(int slot) {
        MlpPolicy p = ModelSlots.newCompatiblePolicy();
        if (!ModelSlots.load(cfg.technique.id, slot, p)) return false;
        setAgent(new NeuralAgent(p));
        holdChampion = true;
        // Restore the saved graph history so the charts continue from where the save was
        // taken instead of resetting to blank.
        var graphs = ModelSlots.loadGraphs(cfg.technique.id, slot);
        if (graphs.containsKey("bestFitness")) fitnessChart.importSeries(graphs.get("bestFitness"));
        if (graphs.containsKey("meanFitness")) meanFitChart.importSeries(graphs.get("meanFitness"));
        if (graphs.containsKey("diversity"))   diversityChart.importSeries(graphs.get("diversity"));
        return true;
    }

    public ModelSlots.SlotInfo slotInfo(int slot) { return ModelSlots.info(cfg.technique.id, slot); }
}
