package dev.suika.game;

import dev.suika.core.GameCore;
import dev.suika.core.GameState;
import dev.suika.core.MergeEvent;
import dev.suika.core.PhysicsConfig;

import java.util.List;

/**
 * Base for runners that show a live, frame-by-frame game on the board. Owns the
 * {@link GameCore}, fixed-step physics, merge → particle feedback, the drop chute
 * gate, and a rolling score chart. Subclasses decide who drops (an agent or a human).
 */
public abstract class LiveBoardRunner implements TechniqueRunner {

    protected final SuikaGame    game;
    protected final PlaygroundConfig cfg;

    protected GameCore core;
    protected long     seed;
    protected int      drops = 0;
    protected float    speed = 1f;
    protected boolean  paused = false;

    private double accumulator = 0;
    protected final LiveChart scoreChart = new LiveChart(260);

    // Board-space transform for merge feedback (particle bursts, score pops). Defaults
    // to the portrait constants so SuikaScreen (always portrait, never calls
    // setPopTransform) keeps working unchanged; ControlCenterScreen calls
    // setPopTransform once per frame with whichever orientation is actually on screen —
    // without this, pops/particles for a landscape single-board view were computed with
    // portrait's OX/SCALE and rendered far from the (differently-positioned) board.
    private float popOx = BoardRenderer.OX, popOy = BoardRenderer.OY, popScale = BoardRenderer.SCALE;

    protected LiveBoardRunner(SuikaGame game, PlaygroundConfig cfg) {
        this.game = game;
        this.cfg  = cfg;
        this.speed = cfg.speed();
    }

    @Override
    public void setPopTransform(float ox, float oy, float scale) {
        popOx = ox; popOy = oy; popScale = scale;
    }

    protected void newGame() {
        seed = game.settings.resolveSeed();
        core = new GameCore(seed);
        drops = 0;
        scoreChart.clear();
        game.particles.clear();
        game.scorePops.clear();
        onNewGame();
    }

    /** Hook for subclasses to reset their own state when a fresh game starts. */
    protected void onNewGame() {}

    @Override public void start() { newGame(); }

    @Override public GameState board() { return core.getState(); }

    @Override
    public void update(float dt) {
        if (paused || core == null) return;
        stepPhysics(dt * speed);
        onUpdate(dt);
    }

    /** Subclass logic each frame (e.g. trigger an agent drop, advance training). */
    protected abstract void onUpdate(float dt);

    /** Hard ceiling on physics ticks per render frame. At extreme speeds (≥128×) we run
     *  as many sub-steps as this allows and let the rest spill to the next frame, keeping
     *  the UI responsive instead of freezing while thousands of ticks run. */
    private static final int MAX_TICKS_PER_FRAME = 240;

    protected void stepPhysics(float dt) {
        // dt already carries the speed multiplier (caller passes dt*speed) — clamp to
        // 4.0 sim-seconds of headroom per frame like every other board (ghost/rival/
        // clone) does, NOT the old 0.10, which silently throttled this board to ~6x
        // real time no matter how high "speed" was set: at 64x-1024x the champion/
        // human board fell far behind the ghost boards, which read as it "getting
        // stuck" relative to them. MAX_TICKS_PER_FRAME is the real ceiling.
        accumulator += Math.min(dt, 4.0);
        int maxSteps = Math.min(MAX_TICKS_PER_FRAME,
                (int) (PhysicsConfig.MAX_SUB_STEPS * Math.ceil(speed)));
        int steps = 0;
        while (accumulator >= PhysicsConfig.FIXED_DT && steps < maxSteps) {
            List<MergeEvent> merges = core.tick();
            for (MergeEvent m : merges) onMerge(m);
            accumulator -= PhysicsConfig.FIXED_DT;
            steps++;
        }
        game.particles.update(dt);
    }

    protected void onMerge(MergeEvent m) {
        if (m.resultTier() != null) {
            float vpx = popOx + (float) m.spawnX() * popScale;
            float vpy = popOy + (float) m.spawnY() * popScale;
            if (game.settings.particles) {
                game.particles.burst(vpx, vpy, FruitColors.of(m.resultTier()), 10 + m.resultTier().tier * 2);
            }
            game.scorePops.add(vpx, vpy, m.scoreAwarded());
        }
    }

    /** True once every fruit's TOP SURFACE (not just its center) has cleared the drop
     *  zone below {@code PhysicsConfig.DROP_Y}. Checking only center-y let a fruit
     *  count as "clear" while its top edge still reached into the spawn point — with
     *  bigger tiers (radius up to ~1.5+ for droppable ones), the next spawn could land
     *  already overlapping it, and dyn4j's solver would violently separate the two
     *  overlapping bodies, flinging one out the side of the container. A flat 2-unit
     *  margin below DROP_Y comfortably clears the largest droppable tier's radius
     *  (PERSIMMON ≈1.54) plus the incoming fruit's own radius. */
    protected boolean chuteClear() {
        double thresh = PhysicsConfig.CONTAINER_HEIGHT - 1.0;
        for (var f : core.getState().fruits()) if (f.y() + f.radius() > thresh) return false;
        return true;
    }

    // Positional bias tracking: which columns this technique actually favors, observed
    // over every drop it's made this session — not hard-coded, and meaningful even for
    // non-learning planners (MCTS/Greedy/Heuristic never adapt, but still show a
    // consistent positional lean worth surfacing, per the "does it just always drop on
    // the left" question).
    private static final int TENDENCY_BUCKETS = 8;
    private final int[] tendencyHist = new int[TENDENCY_BUCKETS];
    private int tendencyTotal = 0;

    protected void doDrop(double gx) {
        core.spawnDrop(gx);
        drops++;
        scoreChart.add(core.getScore());
        recordTendency(gx);
    }

    private void recordTendency(double gx) {
        double t = (gx - PhysicsConfig.DROP_X_MIN) / (PhysicsConfig.DROP_X_MAX - PhysicsConfig.DROP_X_MIN);
        int b = (int) Math.round(Math.max(0.0, Math.min(1.0, t)) * (TENDENCY_BUCKETS - 1));
        tendencyHist[b]++;
        tendencyTotal++;
    }

    /** Plain-language readout of where this technique tends to drop, built from every
     *  drop observed this session. */
    protected String tendencyLabel() {
        if (tendencyTotal < 8) return "not enough drops yet (" + tendencyTotal + ")";
        int left = 0, half = TENDENCY_BUCKETS / 2;
        for (int i = 0; i < half; i++) left += tendencyHist[i];
        int favBucket = 0;
        for (int i = 1; i < TENDENCY_BUCKETS; i++) if (tendencyHist[i] > tendencyHist[favBucket]) favBucket = i;
        double leftPct = 100.0 * left / tendencyTotal;
        String side = leftPct >= 60 ? "left-leaning" : leftPct <= 40 ? "right-leaning" : "balanced";
        return String.format("%.0f%% left half (%s), favors col %d/%d",
                leftPct, side, favBucket + 1, TENDENCY_BUCKETS);
    }

    @Override public LiveChart chart1()      { return scoreChart; }
    @Override public String    chart1Label() { return "score  ·  " + (core != null ? core.getScore() : 0); }
    @Override public LiveChart chart2()      { return null; }
    @Override public String    chart2Label() { return null; }

    @Override public void setPaused(boolean p) { this.paused = p; }
    @Override public boolean paused()          { return paused; }
    @Override public void restart()            { newGame(); }
    @Override public void setSpeed(float m)    { this.speed = m; }
    @Override public void setParallelism(int t){ /* default: nothing extra */ }
    @Override public void dispose()            { }
}
