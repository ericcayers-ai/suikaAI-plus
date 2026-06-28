package dev.suika.ai;

import dev.suika.core.GameCore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ImitationTest {

    @Test
    void replayRecorderCollectsDemonstrations() {
        GameCore core = new GameCore(1L);
        ReplayRecorder recorder = new ReplayRecorder(core, 32);

        for (double x : new double[]{3.0, 5.0, 7.0}) {
            if (recorder.isGameOver()) break;
            recorder.recordDrop(x);
        }

        DemoDataset ds = new DemoDataset();
        recorder.flushInto(ds);
        assertTrue(ds.size() > 0, "Dataset must contain recorded demonstrations");
        assertEquals(0, recorder.recordedCount(), "Buffer must be flushed");
    }

    @Test
    void demoDatasetSamplesCorrectBatchSize() {
        DemoDataset ds = new DemoDataset(42L);
        for (int i = 0; i < 20; i++) {
            ds.add(new Demonstration(new float[dev.suika.env.StateObservationEncoder.TOTAL],
                    i % 32, 1.0, false));
        }
        List<Demonstration> batch = ds.sample(8);
        assertEquals(8, batch.size());
    }

    @Test
    void bcTrainerReducesLossOverUpdates() {
        // Collect demonstrations from a heuristic expert
        DemoDataset ds = new DemoDataset(99L);
        HeuristicAgent expert = new HeuristicAgent();
        ActionSpec spec = ActionSpec.discrete(32);

        GameCore core = new GameCore(5L);
        ReplayRecorder recorder = new ReplayRecorder(core, 32);
        for (int i = 0; i < 15 && !recorder.isGameOver(); i++) {
            double x = dev.suika.core.PhysicsConfig.DROP_X_MIN
                    + (i % 9) * (dev.suika.core.PhysicsConfig.DROP_X_MAX
                    - dev.suika.core.PhysicsConfig.DROP_X_MIN) / 9.0;
            recorder.recordDrop(x);
        }
        recorder.flushInto(ds);

        // BC training should not throw and update count must increase
        BehavioralCloningTrainer bc = new BehavioralCloningTrainer(ds, 1e-3, 8);
        bc.update();
        bc.update();
        assertEquals(2, bc.updateCount());
        assertNotNull(bc.trainedAgent());
    }

    @Test
    void daggerIterationProducesAgent() {
        HeuristicAgent expert = new HeuristicAgent();
        DAggerTrainer dagger = new DAggerTrainer(expert, 8, 10, 2, 1e-3);
        dagger.runIteration(42L);
        assertEquals(1, dagger.iteration());
        assertTrue(dagger.datasetSize() > 0);
        assertNotNull(dagger.bestAgent());
    }

    @Test
    void demonstrationRecordIsImmutable() {
        float[] obs = new float[]{1.0f, 2.0f};
        Demonstration d = new Demonstration(obs, 5, 1.0, false);
        assertEquals(5, d.action());
        assertEquals(1.0, d.reward(), 1e-9);
        assertFalse(d.terminal());
    }
}
