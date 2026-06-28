package dev.suika.assets;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AssetRegistryTest {

    @Test
    void loads11Tiers() {
        assertEquals(11, AssetRegistry.get().fruits().size());
    }

    @Test
    void tier1IsCherry() {
        FruitDefinition cherry = AssetRegistry.get().byTier(1);
        assertNotNull(cherry);
        assertEquals("Cherry", cherry.name());
        assertTrue(cherry.droppable());
    }

    @Test
    void tier11IsWatermelon() {
        FruitDefinition wm = AssetRegistry.get().byTier(11);
        assertNotNull(wm);
        assertEquals("Watermelon", wm.name());
        assertFalse(wm.droppable());
    }

    @Test
    void droppableFruitsAreTiers1to5() {
        List<FruitDefinition> droppable = AssetRegistry.get().droppableFruits();
        assertEquals(5, droppable.size());
        droppable.forEach(d -> assertTrue(d.tier() <= 5, "Only tiers 1-5 are droppable"));
    }

    @Test
    void doubleWatermelonBonusIs100() {
        assertEquals(100, AssetRegistry.get().doubleWatermelonBonus());
    }

    @Test
    void radiiIncreaseWithTier() {
        List<FruitDefinition> fruits = AssetRegistry.get().fruits();
        for (int i = 1; i < fruits.size(); i++) {
            assertTrue(fruits.get(i).radius() > fruits.get(i - 1).radius(),
                    "Radius must increase with tier");
        }
    }
}
