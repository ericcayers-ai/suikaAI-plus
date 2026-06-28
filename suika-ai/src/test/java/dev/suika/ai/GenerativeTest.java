package dev.suika.ai;

import dev.suika.core.GameCore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GenerativeTest {

    @Test
    void diffusionConfigDefaultsAreValid() {
        DiffusionPolicyConfig cfg = DiffusionPolicyConfig.defaults();
        assertTrue(cfg.noiseSteps() > 0);
        assertTrue(cfg.betaEnd() > cfg.betaStart());
        assertEquals(1, cfg.actionDim());
    }

    @Test
    void flowMatchingConfigDefaultsAreValid() {
        FlowMatchingConfig cfg = FlowMatchingConfig.defaults();
        assertTrue(cfg.inferenceSteps() < DiffusionPolicyConfig.defaults().noiseSteps(),
                "Flow matching should use fewer steps than DDPM");
        assertEquals("euler", cfg.solver());
    }

    @Test
    void worldModelConfigMuZeroValid() {
        WorldModelConfig cfg = WorldModelConfig.muZeroDefaults();
        assertEquals("muzero", cfg.type());
        assertTrue(cfg.latentDim() > 0);
    }

    @Test
    void generativeBridgeSampleIsInRange() {
        GameCore core = new GameCore(1L);
        GenerativeModelBridge bridge = new GenerativeModelBridge(
                GenerativeModelBridge.ModelType.DIFFUSION_POLICY);
        int action = bridge.sampleAction(core.getState(), 32);
        assertTrue(action >= 0 && action < 32);
    }

    @Test
    void generativeBridgeBatchSizeCorrect() {
        GameCore core = new GameCore(2L);
        GenerativeModelBridge bridge = new GenerativeModelBridge(
                GenerativeModelBridge.ModelType.FLOW_MATCHING);
        int[] batch = bridge.sampleBatch(core.getState(), 32, 10);
        assertEquals(10, batch.length);
    }
}
