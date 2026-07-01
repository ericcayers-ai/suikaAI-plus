package dev.suika.core;

import org.dyn4j.dynamics.Body;
import org.dyn4j.dynamics.BodyFixture;
import org.dyn4j.geometry.Geometry;
import org.dyn4j.geometry.MassType;
import org.dyn4j.geometry.Vector2;
import org.dyn4j.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Headless, deterministic Suika Game engine.
 *
 * <p>Design invariants:
 * <ul>
 *   <li>No rendering, no input, no threading inside this class.</li>
 *   <li>{@link #snapshot()} produces a deep-copy suitable for MCTS forks.</li>
 *   <li>Same seed + same action sequence ⇒ same final {@link GameState} on the same JVM.</li>
 * </ul>
 */
public class GameCore {

    private static final Logger log = LoggerFactory.getLogger(GameCore.class);

    /**
     * Fruits closer than (r1 + r2) × CONTACT_TOLERANCE are considered touching.
     * A small slack compensates for solver penetration and rounding.
     */
    private static final double CONTACT_TOLERANCE = 1.08;

    // --- Physics world ---
    private final World<Body> world;

    // --- Live state ---
    private final Map<Body, Integer> bodyToId  = new HashMap<>();
    private final Map<Integer, Body> idToBody  = new HashMap<>();
    private final Map<Integer, FruitTier> idToTier = new HashMap<>();
    private int nextId = 0;

    private long    score = 0;
    private long    bestScore = 0;
    private boolean gameOver = false;
    private double  timeAboveDeadline = 0.0;
    private long    stepCount = 0;

    private FruitTier currentTier;
    private FruitTier nextTier;

    private final Random rng;
    private final long   seed;

    // --- Pending removals (to avoid ConcurrentModificationException) ---
    private final Set<Integer> pendingRemoval = new HashSet<>();

    public GameCore(long seed) {
        this.seed = seed;
        this.rng  = new Random(seed);
        this.world = new World<>();
        init();
    }

    /** Private copy-constructor used by {@link #snapshot()}. */
    private GameCore(long seed, long rngSeed, long score, long bestScore, boolean gameOver,
                     double timeAboveDeadline, long stepCount,
                     FruitTier currentTier, FruitTier nextTier) {
        this.seed  = seed;
        this.rng   = new Random(rngSeed);
        this.world = new World<>();
        this.score             = score;
        this.bestScore         = bestScore;
        this.gameOver          = gameOver;
        this.timeAboveDeadline = timeAboveDeadline;
        this.stepCount         = stepCount;
        this.currentTier       = currentTier;
        this.nextTier          = nextTier;
        configureWorld();
        buildContainer();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Resets the game to its initial state using the original seed.
     */
    public void reset() {
        world.removeAllBodies();
        bodyToId.clear();
        idToBody.clear();
        idToTier.clear();
        pendingRemoval.clear();
        nextId             = 0;
        score              = 0;
        gameOver           = false;
        timeAboveDeadline  = 0.0;
        stepCount          = 0;
        rng.setSeed(seed);
        init();
    }

    /**
     * Drops the current fruit at {@code x} and simulates until all bodies settle.
     *
     * @param x  horizontal drop position, clamped to valid range
     * @return   result with updated game state and this step's merge events
     */
    public StepResult dropAndSettle(double x) {
        if (gameOver) {
            return new StepResult(buildState(), 0.0, true, false, List.of());
        }

        x = Math.clamp(x, PhysicsConfig.DROP_X_MIN, PhysicsConfig.DROP_X_MAX);

        Body dropped = createFruitBody(currentTier, x, PhysicsConfig.DROP_Y);
        world.addBody(dropped);
        register(nextId++, dropped, currentTier);

        List<MergeEvent> allMerges = new ArrayList<>();
        simulateUntilSettled(allMerges);

        long gained = allMerges.stream().mapToLong(MergeEvent::scoreAwarded).sum();
        score += gained;
        if (score > bestScore) bestScore = score;

        advanceFruitQueue();
        stepCount++;

        double reward = gained + (gameOver ? -10.0 : 0.0);
        return new StepResult(buildState(), reward, gameOver, false, allMerges);
    }

    /**
     * Spawns the current fruit at {@code x} for <em>live</em>, frame-by-frame
     * simulation and advances the preview queue immediately. Unlike
     * {@link #dropAndSettle(double)} this does <strong>not</strong> run the physics
     * to rest — call {@link #tick()} once per physics frame to watch the fruit fall
     * and settle. Used by the windowed game so the player and the AI-watch viewer
     * see real-time motion instead of a teleport to the settled position.
     *
     * @param x horizontal drop position, clamped to the valid range
     */
    public void spawnDrop(double x) {
        if (gameOver) return;
        x = clampDropForRadius(x, currentTier.radius);
        Body dropped = createFruitBody(currentTier, x, PhysicsConfig.DROP_Y);
        world.addBody(dropped);
        register(nextId++, dropped, currentTier);
        advanceFruitQueue();
        stepCount++;
    }

    /**
     * Advances the live physics world by exactly one fixed timestep, applying any
     * merges and updating the score and dead-line timer. Returns the merge events
     * that occurred this tick so the renderer can spawn particle / pop feedback.
     *
     * <p>Pair with {@link #spawnDrop(double)} and {@link #allAtRest()} to drive a
     * real-time game loop. {@link #dropAndSettle(double)} remains the headless,
     * settle-in-one-call path used by training and planning.
     */
    public List<MergeEvent> tick() {
        if (gameOver) return List.of();
        world.step(1, PhysicsConfig.FIXED_DT);
        checkDeadLine();
        if (gameOver) return List.of();

        List<MergeEvent> merges = detectAndApplyMerges();
        if (!merges.isEmpty()) {
            long gained = merges.stream().mapToLong(MergeEvent::scoreAwarded).sum();
            score += gained;
            if (score > bestScore) bestScore = score;
        }
        return merges;
    }

    /**
     * Clamp a drop x so a fruit of the given {@code radius} lands fully inside the walls
     * (its centre kept at least one radius from each inner wall). Prevents the fruit from
     * spawning overlapping the side ledge and being shoved sideways by the physics.
     */
    public static double clampDropForRadius(double x, double radius) {
        double lo = PhysicsConfig.LEFT_WALL_X  + radius;
        double hi = PhysicsConfig.RIGHT_WALL_X - radius;
        if (lo > hi) { double mid = (PhysicsConfig.LEFT_WALL_X + PhysicsConfig.RIGHT_WALL_X) / 2.0; lo = hi = mid; }
        return Math.clamp(x, lo, hi);
    }

    /** Radius of the fruit that will drop next (for radius-aware drop-position guides). */
    public double currentFruitRadius() { return currentTier.radius; }

    /** True when every live fruit body has settled — i.e. ready for the next drop. */
    public boolean allAtRest() { return isSettled(); }

    /**
     * Returns a deep copy of this core for MCTS / planning forks.
     * The fork's physics steps are fully independent of the original.
     */
    public GameCore snapshot() {
        GameCore copy = new GameCore(
                seed, rng.nextLong(), score, bestScore,
                gameOver, timeAboveDeadline, stepCount,
                currentTier, nextTier
        );
        // Clone all fruit bodies
        for (Map.Entry<Integer, Body> e : idToBody.entrySet()) {
            int id      = e.getKey();
            Body src    = e.getValue();
            FruitTier t = idToTier.get(id);
            Body copy2  = createFruitBody(t,
                    src.getWorldCenter().x, src.getWorldCenter().y);
            copy2.getLinearVelocity().set(src.getLinearVelocity());
            copy2.setAngularVelocity(src.getAngularVelocity());
            copy2.getTransform().setRotation(src.getTransform().getRotationAngle());
            copy.world.addBody(copy2);
            copy.register(id, copy2, t);
        }
        copy.nextId = nextId;
        return copy;
    }

    /** Returns an immutable snapshot of the current game state. */
    public GameState getState() { return buildState(); }

    public boolean isGameOver() { return gameOver; }
    public long    getScore()   { return score; }
    public long    getSeed()    { return seed; }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private void init() {
        configureWorld();
        buildContainer();
        currentTier = drawDroppableTier();
        nextTier    = drawDroppableTier();
    }

    private void configureWorld() {
        world.setGravity(new Vector2(0.0, PhysicsConfig.GRAVITY_Y));
        world.getSettings().setMaximumTranslation(1.2); // raised for g=43; prevents tunneling
        world.getSettings().setAtRestDetectionEnabled(true);
        world.getSettings().setMaximumAtRestLinearVelocity(PhysicsConfig.SLEEP_LINEAR_VELOCITY);
        world.getSettings().setMaximumAtRestAngularVelocity(PhysicsConfig.SLEEP_ANGULAR_VELOCITY);
        world.getSettings().setMinimumAtRestTime(PhysicsConfig.SLEEP_TIME);
    }

    private void buildContainer() {
        double hw = PhysicsConfig.WALL_THICKNESS / 2.0;
        double cw = PhysicsConfig.CONTAINER_WIDTH;
        double ch = PhysicsConfig.CONTAINER_HEIGHT;

        world.addBody(staticRect(cw + PhysicsConfig.WALL_THICKNESS * 2,
                PhysicsConfig.WALL_THICKNESS, cw / 2.0, -hw));
        world.addBody(staticRect(PhysicsConfig.WALL_THICKNESS, ch,
                PhysicsConfig.LEFT_WALL_X  - hw, ch / 2.0));
        world.addBody(staticRect(PhysicsConfig.WALL_THICKNESS, ch,
                PhysicsConfig.RIGHT_WALL_X + hw, ch / 2.0));
    }

    private Body staticRect(double w, double h, double cx, double cy) {
        Body b = new Body();
        BodyFixture f = new BodyFixture(Geometry.createRectangle(w, h));
        f.setFriction(PhysicsConfig.FRICTION_STATIC);
        // Walls/floor share the same toggle as fruit — otherwise a 0-restitution wall
        // could still fully absorb bounce on impact regardless of the fruit's own value.
        f.setRestitution(PhysicsConfig.restitution);
        b.addFixture(f);
        b.setMass(MassType.INFINITE);
        b.translate(cx, cy);
        return b;
    }

    private Body createFruitBody(FruitTier tier, double x, double y) {
        Body b = new Body();
        BodyFixture f = new BodyFixture(Geometry.createCircle(tier.radius));
        f.setRestitution(PhysicsConfig.restitution);
        f.setFriction(PhysicsConfig.FRICTION_DYNAMIC);
        f.setDensity(PhysicsConfig.BASE_DENSITY * tier.radius * tier.radius);
        b.addFixture(f);
        b.setMass(MassType.NORMAL);
        b.translate(x, y);
        return b;
    }

    private void register(int id, Body body, FruitTier tier) {
        bodyToId.put(body, id);
        idToBody.put(id, body);
        idToTier.put(id, tier);
    }

    private void unregister(int id) {
        Body b = idToBody.remove(id);
        if (b != null) {
            bodyToId.remove(b);
            world.removeBody(b);
        }
        idToTier.remove(id);
    }

    private void simulateUntilSettled(List<MergeEvent> accumulated) {
        int maxSteps = 600; // safety cap — ~10 s at 60 Hz
        for (int i = 0; i < maxSteps; i++) {
            world.step(1, PhysicsConfig.FIXED_DT);
            checkDeadLine();
            if (gameOver) break;

            List<MergeEvent> merges = detectAndApplyMerges();
            accumulated.addAll(merges);

            if (!merges.isEmpty()) continue; // keep stepping after a merge chain
            if (isSettled()) break;
        }
    }

    private boolean isSettled() {
        for (Body b : idToBody.values()) {
            if (b.isAtRest()) continue;
            double lv = b.getLinearVelocity().getMagnitude();
            double av = Math.abs(b.getAngularVelocity());
            if (lv > PhysicsConfig.SLEEP_LINEAR_VELOCITY
                    || av > PhysicsConfig.SLEEP_ANGULAR_VELOCITY) {
                return false;
            }
        }
        return true;
    }

    /**
     * Distance-based same-tier contact detection.
     * Using geometric proximity instead of the physics contact graph avoids
     * depending on dyn4j internal contact iteration APIs that vary by version.
     */
    private List<MergeEvent> detectAndApplyMerges() {
        List<MergeEvent> events = new ArrayList<>();
        List<Integer> ids = new ArrayList<>(idToBody.keySet());

        for (int i = 0; i < ids.size(); i++) {
            int id1 = ids.get(i);
            if (pendingRemoval.contains(id1)) continue;

            for (int j = i + 1; j < ids.size(); j++) {
                int id2 = ids.get(j);
                if (pendingRemoval.contains(id2)) continue;

                FruitTier t1 = idToTier.get(id1);
                FruitTier t2 = idToTier.get(id2);
                if (t1 != t2) continue;

                Body b1 = idToBody.get(id1);
                Body b2 = idToBody.get(id2);
                double dx   = b1.getWorldCenter().x - b2.getWorldCenter().x;
                double dy   = b1.getWorldCenter().y - b2.getWorldCenter().y;
                double dist = Math.sqrt(dx * dx + dy * dy);
                double sumR = (t1.radius + t2.radius) * CONTACT_TOLERANCE;

                if (dist > sumR) continue;

                // Same tier, touching → merge
                double spawnX = (b1.getWorldCenter().x + b2.getWorldCenter().x) / 2.0;
                double spawnY = (b1.getWorldCenter().y + b2.getWorldCenter().y) / 2.0;
                FruitTier result = t1.next();
                int awarded = (result != null) ? result.mergeScore
                                               : PhysicsConfig.DOUBLE_WATERMELON_BONUS;

                pendingRemoval.add(id1);
                pendingRemoval.add(id2);
                events.add(new MergeEvent(id1, id2, result, spawnX, spawnY, awarded));
                break; // id1 consumed — stop inner loop
            }
        }

        for (MergeEvent e : events) {
            unregister(e.idA());
            unregister(e.idB());
            pendingRemoval.remove(e.idA());
            pendingRemoval.remove(e.idB());

            if (e.resultTier() != null) {
                Body nb = createFruitBody(e.resultTier(), e.spawnX(), e.spawnY());
                world.addBody(nb);
                int newId = nextId++;
                register(newId, nb, e.resultTier());
            }
        }

        return events;
    }

    private void checkDeadLine() {
        boolean anyResting = false;
        for (Map.Entry<Integer, Body> e : idToBody.entrySet()) {
            Body b = e.getValue();
            if (!b.isAtRest()) continue;
            double topY = b.getWorldCenter().y + idToTier.get(e.getKey()).radius;
            if (topY > PhysicsConfig.DEADLINE_Y) {
                anyResting = true;
                break;
            }
        }
        if (anyResting) {
            timeAboveDeadline += PhysicsConfig.FIXED_DT;
            if (timeAboveDeadline >= PhysicsConfig.DEADLINE_GRACE_SECONDS) {
                gameOver = true;
            }
        } else {
            timeAboveDeadline = Math.max(0.0, timeAboveDeadline - PhysicsConfig.FIXED_DT);
        }
    }

    private void advanceFruitQueue() {
        currentTier = nextTier;
        nextTier    = drawDroppableTier();
    }

    private FruitTier drawDroppableTier() {
        int roll = rng.nextInt(10);
        return switch (roll) {
            case 0, 1, 2, 3 -> FruitTier.CHERRY;
            case 4, 5, 6    -> FruitTier.STRAWBERRY;
            case 7, 8       -> FruitTier.GRAPE;
            case 9          -> FruitTier.DEKOPON;
            default         -> FruitTier.CHERRY;
        };
    }

    private GameState buildState() {
        List<Fruit> fruits = new ArrayList<>();
        for (Map.Entry<Integer, Body> e : idToBody.entrySet()) {
            int id      = e.getKey();
            Body b      = e.getValue();
            FruitTier t = idToTier.get(id);
            fruits.add(new Fruit(
                    id, t,
                    b.getWorldCenter().x, b.getWorldCenter().y,
                    b.getLinearVelocity().x, b.getLinearVelocity().y,
                    b.getTransform().getRotationAngle(),
                    b.getAngularVelocity(),
                    b.isAtRest()
            ));
        }
        return new GameState(
                fruits, currentTier, nextTier,
                score, bestScore, gameOver,
                timeAboveDeadline, stepCount, seed
        );
    }
}
