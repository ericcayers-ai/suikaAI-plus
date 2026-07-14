package dev.suika.ai;

import dev.suika.core.GameCore;
import dev.suika.core.PhysicsConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BoardEvalTest {

    @Test
    void gameOverIsWorseThanAnyHealthyBoard() {
        GameCore alive = new GameCore(42L);
        alive.dropAndSettle(5.0);
        double health = BoardEval.placement(alive, 0, false);

        GameCore doomed = new GameCore(42L);
        // Keep stacking until game-over; then score that terminal board.
        int guard = 0;
        while (!doomed.isGameOver() && guard++ < 400) {
            doomed.dropAndSettle(5.0);
        }
        assertTrue(doomed.isGameOver(), "expected a game-over within 400 drops");
        double over = BoardEval.placement(doomed, 10, true);
        assertTrue(over < health, "game-over placement (" + over + ") must lose to healthy (" + health + ")");
        assertTrue(over < -BoardEval.GAME_OVER_PENALTY + 100);
    }

    @Test
    void realizedMergeBeatsMereTidiness() {
        // Fresh board: health-only score is small/negative; a +6 merge×MERGE_WEIGHT
        // must dominate that.
        GameCore core = new GameCore(7L);
        core.dropAndSettle(5.0);
        double tidy = BoardEval.placement(core, 0, false);
        double merged = BoardEval.placement(core, 6, false);
        assertTrue(merged > tidy + 20, "merge weight must dominate height/health delta");
    }

    @Test
    void dangerBandConstantIsPositive() {
        assertTrue(BoardEval.DANGER_BAND > 0);
        assertTrue(BoardEval.DANGER_BAND < PhysicsConfig.DEADLINE_Y);
    }
}
