package dev.suika.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HyperparamSchemaTest {

    @Test
    void mctsLadderIncludesRolloutOptions() {
        List<HyperparamSchema> schema = HyperparamSchema.forMcts();
        HyperparamSchema rollouts = schema.stream()
                .filter(s -> s.key().equals("rollouts")).findFirst().orElseThrow();
        assertEquals(HyperparamSchema.Type.ENUM, rollouts.type());
        assertTrue(rollouts.enumOptions().length >= 5);
        assertEquals(80, ((Number) rollouts.defaultValue()).intValue());
    }

    @Test
    void techniqueIdLookupUsesKebabCaseIds() {
        assertFalse(HyperparamSchema.forTechniqueId("mcts").isEmpty());
        assertFalse(HyperparamSchema.forTechniqueId("cma-es").isEmpty());
        assertFalse(HyperparamSchema.forTechniqueId("ens-mcts-net").isEmpty());
        assertFalse(HyperparamSchema.forTechniqueId("dt").isEmpty());
        assertTrue(HyperparamSchema.forTechniqueId("unknown").isEmpty());
    }

    @Test
    void catalogueHasNoBlankKeys() {
        assertFalse(HyperparamSchema.catalogue().isEmpty());
        HyperparamSchema.catalogue().forEach((k, v) -> {
            assertFalse(k.isBlank());
            assertEquals(k, v.key());
        });
    }
}
