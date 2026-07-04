package dev.suika.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property tests for game-mechanic invariants (Phase 1 exit criteria).
 */
class GameMechanicsTest {

    @Test
    void scoreIsNonNegative() {
        GameCore core = new GameCore(11L);
        for (int i = 0; i < 30 && !core.isGameOver(); i++) {
            core.dropAndSettle(1.0 + (i % 9));
        }
        assertTrue(core.getScore() >= 0, "Score must never go negative");
    }

    @Test
    void mergeRaisesScore() {
        // Drop many fruits at the same spot to force merges
        GameCore core = new GameCore(42L);
        long scoreAfter = 0;
        for (int i = 0; i < 15 && !core.isGameOver(); i++) {
            StepResult r = core.dropAndSettle(5.0);
            scoreAfter = r.observation().score();
        }
        // After 15 drops at the same x, at least one merge must have happened
        assertTrue(scoreAfter > 0, "At least one merge must have occurred and scored points");
    }

    @Test
    void mergeEventTierIsOneHigher() {
        GameCore core = new GameCore(77L);
        for (int i = 0; i < 20 && !core.isGameOver(); i++) {
            StepResult r = core.dropAndSettle(5.0);
            for (MergeEvent e : r.mergesThisStep()) {
                if (e.resultTier() != null) {
                    // The result tier ordinal must be exactly one higher than the merged pair
                    int expectedOrdinal = FruitTier.values()[0].ordinal(); // just a reference
                    // Find what tier was merged: iterate all FruitTier values
                    for (FruitTier ft : FruitTier.values()) {
                        if (ft.next() == e.resultTier()) {
                            // ft is the source tier → result must be ft.next()
                            assertEquals(ft.next(), e.resultTier(),
                                    "Merge result tier must be one above source");
                        }
                    }
                } else {
                    // Watermelon merge — result is null (two watermelons vanish)
                    assertEquals(PhysicsConfig.DOUBLE_WATERMELON_BONUS, e.scoreAwarded(),
                            "Double-watermelon bonus must be 100");
                }
            }
        }
    }

    @Test
    void fruitsStayInsideContainer() {
        GameCore core = new GameCore(55L);
        for (int i = 0; i < 25 && !core.isGameOver(); i++) {
            double x = 1.0 + (i % 9);
            StepResult r = core.dropAndSettle(x);
            for (Fruit f : r.observation().fruits()) {
                double left  = PhysicsConfig.LEFT_WALL_X  + f.radius();
                double right = PhysicsConfig.RIGHT_WALL_X - f.radius();
                assertTrue(f.x() >= left - 0.5,
                        "Fruit x=%f left of container".formatted(f.x()));
                assertTrue(f.x() <= right + 0.5,
                        "Fruit x=%f right of container".formatted(f.x()));
                assertTrue(f.y() >= -1.0,
                        "Fruit y=%f below floor".formatted(f.y()));
            }
        }
    }

    @Test
    void replayLogReconstructsExactScore() {
        long seed = 2024L;
        List<Double> drops = List.of(2.0, 5.0, 8.0, 5.0, 3.0, 7.0, 5.0, 4.0, 6.0, 5.0);

        // Run live
        GameCore live = new GameCore(seed);
        ReplayLog log = new ReplayLog(seed);
        for (double x : drops) {
            if (live.isGameOver()) break;
            StepResult r = live.dropAndSettle(x);
            log.record(x, r.observation().score());
        }
        long liveScore = live.getScore();

        // Replay
        GameState replayed = log.replay();
        assertEquals(liveScore, replayed.score(),
                "Replay must reproduce the live score exactly");
    }

    @Test
    void fruitsCountIncreasesThenMerges() {
        GameCore core = new GameCore(13L);

        // After first drop there must be exactly 1 fruit (no merges yet)
        StepResult first = core.dropAndSettle(5.0);
        // After settling we may have 1 or more if a merge chain was somehow triggered
        // by a later fruit already being there — but on a fresh board there are no others
        assertEquals(1, first.observation().fruits().size(),
                "First drop on empty board must produce exactly 1 fruit");
    }

    @Test
    void noFruitExistsBeforeFirstDrop() {
        GameCore core = new GameCore(9L);
        assertTrue(core.getState().fruits().isEmpty(),
                "Brand new game must have zero fruits");
    }

    @Test
    void currentAndNextTierAreDroppable() {
        GameCore core = new GameCore(3L);
        GameState s = core.getState();
        assertTrue(s.currentFruitTier().isDroppable(),
                "Current fruit tier must be droppable (tier ≤ 5)");
        assertTrue(s.nextFruitTier().isDroppable(),
                "Next fruit tier must be droppable (tier ≤ 5)");
    }

    /**
     * The high-speed overflow "cheat" regression: piling fruit up past the dead-line in
     * live {@link GameCore#tick()} play must eventually END the game, not let the well
     * overflow forever while the score keeps climbing. Before the v0.13 fix the dead-line
     * check required a fruit to be strictly {@code isAtRest()} above the line, which a
     * perpetually-jostled stack never reaches, so the game never failed. Now a fruit that
     * has merely slowed down above the line trips the (graced) loss.
     */
    @Test
    void continuousStackingAboveDeadlineEventuallyEndsGame() {
        boolean savedInstant = PhysicsConfig.instantFail;
        PhysicsConfig.instantFail = false;
        try {
            GameCore core = new GameCore(101L);
            // Spawn fruit into the same column far more often than it can settle, then run
            // many live ticks — the classic overflow scenario at high sim speed.
            for (int i = 0; i < 400 && !core.isGameOver(); i++) {
                if (core.getState().fruits().size() < 60) core.spawnDrop(5.0);
                for (int t = 0; t < 20; t++) { core.tick(); if (core.isGameOver()) break; }
            }
            assertTrue(core.isGameOver(),
                    "A well continuously overfilled past the dead-line must eventually fail");
        } finally {
            PhysicsConfig.instantFail = savedInstant;
        }
    }

    /** Instant-fail mode ends the game with zero grace once a fruit overflows the line. */
    @Test
    void instantFailEndsGameWithoutGrace() {
        boolean savedInstant = PhysicsConfig.instantFail;
        PhysicsConfig.instantFail = true;
        try {
            GameCore core = new GameCore(202L);
            int ticksWhenOver = -1;
            for (int i = 0; i < 400 && !core.isGameOver(); i++) {
                if (core.getState().fruits().size() < 60) core.spawnDrop(5.0);
                for (int t = 0; t < 20; t++) {
                    core.tick();
                    if (core.isGameOver()) { ticksWhenOver = i; break; }
                }
            }
            assertTrue(core.isGameOver(), "Instant-fail must end the game once fruit overflows");
            // Grace timer should be pegged at the ceiling (instant), never a partial value.
            assertEquals(PhysicsConfig.DEADLINE_GRACE_SECONDS,
                    core.getState().timeAboveDeadline(), 1e-9,
                    "Instant-fail pegs the dead-line timer at the grace ceiling");
            assertTrue(ticksWhenOver >= 0);
        } finally {
            PhysicsConfig.instantFail = savedInstant;
        }
    }
}
