package dev.suika.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModdingTest {

    @Test
    void standardLadderHas11Tiers() {
        FruitLadder ladder = FruitLadder.standard();
        assertEquals(11, ladder.size());
    }

    @Test
    void standardLadderFirstFiveDroppable() {
        FruitLadder ladder = FruitLadder.standard();
        for (int i = 0; i < 5; i++) {
            assertTrue(ladder.isDroppable(i), "Entry " + i + " should be droppable");
        }
        for (int i = 5; i < ladder.size(); i++) {
            assertFalse(ladder.isDroppable(i), "Entry " + i + " should not be droppable");
        }
    }

    @Test
    void compactLadderHas6Tiers() {
        FruitLadder ladder = FruitLadder.compact();
        assertEquals(6, ladder.size());
    }

    @Test
    void containerConfigValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> new ContainerConfig(-1, 10, 0.4, 8, 11, 0.2));
        assertThrows(IllegalArgumentException.class,
                () -> new ContainerConfig(10, 10, 0.4, 15, 11, 0.2));
    }

    @Test
    void containerDropXBoundsWithinWidth() {
        ContainerConfig cfg = ContainerConfig.standard();
        assertTrue(cfg.dropXMin() >= 0, "dropXMin must be >= 0");
        assertTrue(cfg.dropXMax() <= cfg.width(), "dropXMax must be <= width");
        assertTrue(cfg.dropXMin() < cfg.dropXMax(), "drop range must be non-empty");
    }

    @Test
    void modConfigStandardRoundtrip() {
        ModConfig mod = ModConfig.standard();
        assertEquals("standard", mod.name());
        assertEquals(11, mod.fruitLadder().size());
        assertEquals(PhysicsConfig.DOUBLE_WATERMELON_BONUS, mod.doubleTopBonus());
        assertEquals(PhysicsConfig.CONTAINER_WIDTH, mod.container().width());
    }

    @Test
    void modConfigCompactVariant() {
        ModConfig mod = ModConfig.compact();
        assertEquals(6, mod.fruitLadder().size());
        assertEquals(50, mod.doubleTopBonus());
    }

    @Test
    void modConfigNarrowHasSmallerWidth() {
        ModConfig narrow = ModConfig.narrow();
        ModConfig standard = ModConfig.standard();
        assertTrue(narrow.container().width() < standard.container().width());
    }
}
