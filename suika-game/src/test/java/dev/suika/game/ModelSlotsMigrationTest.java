package dev.suika.game;

import dev.suika.ai.MlpPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Migration / compatibility: pre-v0.13 binary {@code .dat} slots and the current
 * folder format both remain loadable. See docs/contracts.md.
 */
class ModelSlotsMigrationTest {

    private static final String TEST_ID = "test-only-model-slots-migration";

    @AfterEach
    void cleanup() throws IOException {
        Path dir = Path.of(System.getProperty("user.home"), ".suikai", "saves", TEST_ID);
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                });
            }
        }
    }

    @Test
    void architectureConstantsMatchEncoderContract() {
        assertEquals(3, ModelSlots.SLOT_COUNT);
        assertEquals(64, ModelSlots.HIDDEN_SIZE);
        assertEquals(32, ModelSlots.OUTPUT_BINS);
        MlpPolicy p = ModelSlots.newCompatiblePolicy();
        int expected = MlpPolicy.paramCount(
                dev.suika.env.StateObservationEncoder.TOTAL,
                ModelSlots.HIDDEN_SIZE,
                ModelSlots.OUTPUT_BINS);
        assertEquals(expected, p.paramCount());
    }

    @Test
    void legacyBinaryWeightsStillLoad() throws IOException {
        MlpPolicy source = ModelSlots.newCompatiblePolicy();
        source.initRandom(new Random(99));
        double[] original = source.getWeights();

        Path legacy = Path.of(System.getProperty("user.home"), ".suikai", "saves",
                TEST_ID, "slot1.dat");
        Files.createDirectories(legacy.getParent());
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(legacy))) {
            out.writeInt(original.length); // paramCount
            out.writeInt(original.length); // len
            for (double v : original) out.writeDouble(v);
            out.writeLong(1_700_000_000_000L);
            out.writeDouble(42.5);
        }

        MlpPolicy loaded = ModelSlots.newCompatiblePolicy();
        assertTrue(ModelSlots.load(TEST_ID, 1, loaded), "legacy .dat must still load");
        assertArrayEquals(original, loaded.getWeights(), 1e-12);

        ModelSlots.SlotInfo info = ModelSlots.info(TEST_ID, 1);
        assertTrue(info.present());
        assertEquals(42.5, info.score(), 1e-9);
        assertEquals(1_700_000_000_000L, info.savedAtMillis());
        assertEquals(ModelSlots.kindWeights(), ModelSlots.slotKind(TEST_ID, 1));
    }

    @Test
    void legacyConfigBinaryStillLoads() throws IOException {
        Path legacy = Path.of(System.getProperty("user.home"), ".suikai", "saves",
                TEST_ID, "slot2.dat");
        Files.createDirectories(legacy.getParent());
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(legacy))) {
            out.writeInt(-2); // MAGIC_CONFIG
            out.writeInt(0);
            out.writeLong(123L);
            out.writeDouble(9.0);
            out.writeInt(2);
            out.writeUTF("rollouts");
            out.writeDouble(50.0);
            out.writeUTF("c");
            out.writeDouble(1.4);
        }

        ModelSlots.ConfigSlot cfg = ModelSlots.loadConfig(TEST_ID, 2);
        assertNotNull(cfg);
        assertEquals(9.0, cfg.score(), 1e-9);
        assertEquals(123L, cfg.savedAtMillis());
        assertEquals(50.0, cfg.params().get("rollouts"), 1e-9);
        assertEquals(1.4, cfg.params().get("c"), 1e-9);
        assertEquals(ModelSlots.kindConfig(), ModelSlots.slotKind(TEST_ID, 2));
    }

    @Test
    void folderFormatTakesPrecedenceOverLegacySibling() throws IOException {
        MlpPolicy legacyPolicy = ModelSlots.newCompatiblePolicy();
        legacyPolicy.initRandom(new Random(1));
        Path legacy = Path.of(System.getProperty("user.home"), ".suikai", "saves",
                TEST_ID, "slot1.dat");
        Files.createDirectories(legacy.getParent());
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(legacy))) {
            double[] w = legacyPolicy.getWeights();
            out.writeInt(w.length);
            out.writeInt(w.length);
            for (double v : w) out.writeDouble(v);
            out.writeLong(1L);
            out.writeDouble(1.0);
        }

        MlpPolicy modern = ModelSlots.newCompatiblePolicy();
        modern.initRandom(new Random(2));
        ModelSlots.save(TEST_ID, 1, modern, 99.0);

        MlpPolicy loaded = ModelSlots.newCompatiblePolicy();
        assertTrue(ModelSlots.load(TEST_ID, 1, loaded));
        assertArrayEquals(modern.getWeights(), loaded.getWeights(), 1e-12);
        assertEquals(99.0, ModelSlots.info(TEST_ID, 1).score(), 1e-9);
    }
}
