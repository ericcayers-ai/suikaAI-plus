package dev.suika.app;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {

    @Test
    void allPresetsHaveNonNullConfig() {
        for (AgentPreset p : AgentPreset.values()) {
            assertNotNull(p.config, p.displayName + " config must not be null");
            assertNotNull(p.displayName);
            assertFalse(p.description.isBlank());
        }
    }

    @Test
    void geneticSchemaHasRequiredKeys() {
        List<HyperparamSchema> schema = HyperparamSchema.forGenetic();
        assertFalse(schema.isEmpty());
        boolean hasPopSize = schema.stream().anyMatch(s -> s.key().equals("population_size"));
        assertTrue(hasPopSize, "Genetic schema must include population_size");
    }

    @Test
    void mctsSchemaHasRolloutsParam() {
        List<HyperparamSchema> schema = HyperparamSchema.forMcts();
        boolean hasRollouts = schema.stream().anyMatch(s -> s.key().equals("rollouts"));
        assertTrue(hasRollouts, "MCTS schema must include rollouts param");
    }

    @Test
    void onnxExportConfigDefaults() {
        OnnxExportConfig cfg = OnnxExportConfig.defaults(Path.of("/tmp"));
        assertEquals("policy.onnx", cfg.outputPath().getFileName().toString());
        assertEquals(dev.suika.env.StateObservationEncoder.TOTAL, cfg.inputDim());
        assertEquals(32, cfg.outputDim());
    }

    @Test
    void appModeEnumHasTwoValues() {
        assertEquals(2, AppMode.values().length);
    }
}
