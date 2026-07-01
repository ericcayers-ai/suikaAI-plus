package dev.suika.game;

import dev.suika.ai.MlpPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link ModelSlots} save/load round-tripping, since it's a new file-I/O path
 * with no prior coverage. Uses a distinctively-named test "technique id" (never a real
 * one) under the real {@code ~/.suikai/saves/} so it exercises the actual save
 * directory logic, and removes everything it wrote afterward.
 */
class ModelSlotsTest {

    private static final String TEST_ID = "test-only-model-slots-junit";

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
    void missingSlotReportsEmptyAndFailsToLoad() {
        ModelSlots.SlotInfo info = ModelSlots.info(TEST_ID, 1);
        assertFalse(info.present());

        MlpPolicy target = ModelSlots.newCompatiblePolicy();
        assertFalse(ModelSlots.load(TEST_ID, 1, target));
    }

    @Test
    void saveThenLoadRoundTripsWeightsExactly() {
        MlpPolicy source = ModelSlots.newCompatiblePolicy();
        source.initRandom(new Random(42));
        double[] original = source.getWeights();

        ModelSlots.save(TEST_ID, 1, source, 1234.5);

        ModelSlots.SlotInfo info = ModelSlots.info(TEST_ID, 1);
        assertTrue(info.present());
        assertEquals(1234.5, info.score(), 1e-9);
        assertTrue(info.savedAtMillis() > 0);

        MlpPolicy loaded = ModelSlots.newCompatiblePolicy();
        assertTrue(ModelSlots.load(TEST_ID, 1, loaded));
        assertArrayEquals(original, loaded.getWeights(), 1e-12);
    }

    @Test
    void slotsAreIndependent() {
        MlpPolicy a = ModelSlots.newCompatiblePolicy();
        a.initRandom(new Random(1));
        MlpPolicy b = ModelSlots.newCompatiblePolicy();
        b.initRandom(new Random(2));

        ModelSlots.save(TEST_ID, 1, a, 10);
        ModelSlots.save(TEST_ID, 2, b, 20);

        assertFalse(ModelSlots.info(TEST_ID, 3).present(), "slot 3 was never written");

        MlpPolicy loadedA = ModelSlots.newCompatiblePolicy();
        ModelSlots.load(TEST_ID, 1, loadedA);
        assertArrayEquals(a.getWeights(), loadedA.getWeights(), 1e-12);

        MlpPolicy loadedB = ModelSlots.newCompatiblePolicy();
        ModelSlots.load(TEST_ID, 2, loadedB);
        assertArrayEquals(b.getWeights(), loadedB.getWeights(), 1e-12);
    }

    @Test
    void loadRejectsArchitectureMismatchInsteadOfCorrupting() {
        MlpPolicy source = ModelSlots.newCompatiblePolicy();
        source.initRandom(new Random(7));
        ModelSlots.save(TEST_ID, 1, source, 0);

        // A differently-shaped policy must not be silently (and incorrectly) loaded into.
        MlpPolicy wrongShape = new MlpPolicy(10, 8, 4);
        double[] before = wrongShape.getWeights();
        assertFalse(ModelSlots.load(TEST_ID, 1, wrongShape));
        assertArrayEquals(before, wrongShape.getWeights(), 1e-12, "weights must be untouched on a rejected load");
    }

    @Test
    void resavingOverwritesRatherThanCorruptingTheSlot() {
        MlpPolicy first = ModelSlots.newCompatiblePolicy();
        first.initRandom(new Random(3));
        ModelSlots.save(TEST_ID, 1, first, 1);

        MlpPolicy second = ModelSlots.newCompatiblePolicy();
        second.initRandom(new Random(9));
        ModelSlots.save(TEST_ID, 1, second, 2);

        MlpPolicy loaded = ModelSlots.newCompatiblePolicy();
        assertTrue(ModelSlots.load(TEST_ID, 1, loaded));
        assertArrayEquals(second.getWeights(), loaded.getWeights(), 1e-12);
        assertEquals(2.0, ModelSlots.info(TEST_ID, 1).score(), 1e-9);
    }
}
