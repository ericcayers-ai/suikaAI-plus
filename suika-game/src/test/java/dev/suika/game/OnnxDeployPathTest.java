package dev.suika.game;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused deploy-path tests: ONNX slot detection and donor list include PPO when ORT works.
 */
class OnnxDeployPathTest {

    @TempDir
    Path tempHome;

    @Test
    void ensembleDonorsIncludePpo() {
        boolean found = false;
        for (AiTechnique t : PlaygroundConfig.ENSEMBLE_DONORS) {
            if (t == AiTechnique.PPO) found = true;
        }
        assertTrue(found, "PPO should be an ensemble donor now that ONNX play works");
    }

    @Test
    void hasOnnxAndPlayablePolicy(@TempDir Path dir) throws Exception {
        // Redirect ~/.suikai by writing into a real slot under a temp technique via reflection
        // of the path contract: slotDir uses user.home — set it for this test.
        String prev = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        try {
            Path slot = ModelSlots.slotDir("ppo", 1);
            Files.createDirectories(slot);
            assertFalse(ModelSlots.hasOnnx("ppo", 1));

            var fixture = OnnxDeployPathTest.class.getResource("/dev/suika/bridge/tiny_policy.onnx");
            if (fixture == null) {
                // Fixture lives in suika-bridge test resources; copy from sibling if needed.
                Path bridgeFixture = Path.of("suika-bridge/src/test/resources/dev/suika/bridge/tiny_policy.onnx");
                if (!Files.exists(bridgeFixture)) {
                    bridgeFixture = Path.of("../suika-bridge/src/test/resources/dev/suika/bridge/tiny_policy.onnx");
                }
                if (!Files.exists(bridgeFixture)) {
                    org.junit.jupiter.api.Assumptions.assumeTrue(false, "tiny_policy.onnx fixture not on classpath");
                }
                Files.copy(bridgeFixture, slot.resolve("model.onnx"), StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.copy(Path.of(fixture.toURI()), slot.resolve("model.onnx"), StandardCopyOption.REPLACE_EXISTING);
            }

            assertTrue(ModelSlots.hasOnnx("ppo", 1));
            assertTrue(ModelSlots.hasPlayablePolicy("ppo", 1));
            assertEquals(1, ModelSlots.firstOnnxSlot("ppo"));

            if (dev.suika.bridge.OrtOnnxPolicyRunner.nativesAvailable()) {
                OnnxAgent agent = ModelSlots.tryLoadOnnxAgent("ppo", 1, 32);
                assertNotNull(agent, "ORT should load the tiny fixture");
                assertTrue(agent.usingOrt());
                agent.close();
            }
        } finally {
            if (prev != null) System.setProperty("user.home", prev);
            else System.clearProperty("user.home");
        }
    }

    @Test
    void agentsPpoFallsBackToHeuristicWithoutOnnx() {
        String prev = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        try {
            PlaygroundConfig cfg = new PlaygroundConfig();
            cfg.selectDefaultsFor(AiTechnique.PPO);
            var agent = Agents.build(cfg);
            assertEquals("heuristic", agent.id());
        } finally {
            if (prev != null) System.setProperty("user.home", prev);
            else System.clearProperty("user.home");
        }
    }
}
