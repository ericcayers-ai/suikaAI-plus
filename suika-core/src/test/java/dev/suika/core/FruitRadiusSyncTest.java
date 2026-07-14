package dev.suika.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Guards the duplicated radius tables (FruitTier ↔ FruitLadder.standard). */
class FruitRadiusSyncTest {

    @Test
    void standardLadderMatchesFruitTierRadiiAndScores() {
        FruitLadder ladder = FruitLadder.standard();
        FruitTier[] tiers = FruitTier.values();
        assertEquals(tiers.length, ladder.size());
        for (int i = 0; i < tiers.length; i++) {
            FruitLadder.Entry e = ladder.get(i);
            assertEquals(tiers[i].radius, e.radius(), 1e-5f,
                    "radius mismatch at " + tiers[i].name());
            assertEquals(tiers[i].mergeScore, e.mergeScore(),
                    "mergeScore mismatch at " + tiers[i].name());
            assertEquals(tiers[i].isDroppable(), e.droppable(),
                    "droppable mismatch at " + tiers[i].name());
        }
    }
}
