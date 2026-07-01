package dev.suika.game;

import dev.suika.ai.MlpPolicy;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Save/load for trained {@link MlpPolicy} weights — the champion of an evolution run,
 * or a Behavioral Cloning / DAgger policy. 3 slots per technique, on disk under
 * {@code ~/.suikai/saves/<technique-id>/slot<N>.dat} so progress survives restarting
 * the app, not just this session.
 *
 * <p>File format (all big-endian via {@link DataOutputStream}): inputSize, hiddenSize,
 * outputSize (ints), weight count (int), weights (doubles), then a timestamp (epoch
 * millis, long) and the score/fitness at save time (double) — the two the UI shows
 * without needing to load the (potentially large) weight array first.
 */
final class ModelSlots {

    private ModelSlots() {}

    static final int SLOT_COUNT = 3;

    // GeneticTrainer / CmaEsTrainer / BehavioralCloningTrainer all hard-code this same
    // hidden/output architecture internally (only the input size, which they all take
    // from StateObservationEncoder.TOTAL, is shared via a public constant) — centralised
    // here so save/load has exactly one place that needs to stay in sync with them.
    static final int HIDDEN_SIZE = 64;
    static final int OUTPUT_BINS = 32;

    /** A freshly-constructed policy with the exact architecture every save uses. */
    static MlpPolicy newCompatiblePolicy() {
        return new MlpPolicy(dev.suika.env.StateObservationEncoder.TOTAL, HIDDEN_SIZE, OUTPUT_BINS);
    }

    /** What a slot holds, without loading the (potentially large) weight array. */
    record SlotInfo(boolean present, long savedAtMillis, double score) {
        static final SlotInfo EMPTY = new SlotInfo(false, 0L, 0.0);
    }

    private static Path saveDir(String techniqueId) {
        return Path.of(System.getProperty("user.home"), ".suikai", "saves", techniqueId);
    }

    private static Path slotFile(String techniqueId, int slot) {
        return saveDir(techniqueId).resolve("slot" + slot + ".dat");
    }

    static void save(String techniqueId, int slot, MlpPolicy policy, double score) {
        try {
            Path dir = saveDir(techniqueId);
            Files.createDirectories(dir);
            Path target = slotFile(techniqueId, slot);
            Path tmp = dir.resolve("slot" + slot + ".dat.tmp");
            double[] w = policy.getWeights();
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(tmp))) {
                out.writeInt(policy.paramCount()); // sanity check on load
                out.writeInt(w.length);
                for (double v : w) out.writeDouble(v);
                out.writeLong(System.currentTimeMillis());
                out.writeDouble(score);
            }
            // Write-then-rename so a crash mid-write never corrupts an existing save.
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code null} if the slot doesn't exist, weight-count doesn't match {@code policy}'s
     *  architecture, or the file is unreadable. */
    static boolean load(String techniqueId, int slot, MlpPolicy policy) {
        Path f = slotFile(techniqueId, slot);
        if (!Files.exists(f)) return false;
        try (DataInputStream in = new DataInputStream(Files.newInputStream(f))) {
            int paramCount = in.readInt();
            int len = in.readInt();
            if (paramCount != policy.paramCount() || len != policy.paramCount()) return false;
            double[] w = new double[len];
            for (int i = 0; i < len; i++) w[i] = in.readDouble();
            policy.setWeights(w);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    static SlotInfo info(String techniqueId, int slot) {
        Path f = slotFile(techniqueId, slot);
        if (!Files.exists(f)) return SlotInfo.EMPTY;
        try (DataInputStream in = new DataInputStream(Files.newInputStream(f))) {
            in.readInt(); // paramCount
            int len = in.readInt();
            in.skipBytes(len * Double.BYTES);
            long ts = in.readLong();
            double score = in.readDouble();
            return new SlotInfo(true, ts, score);
        } catch (IOException e) {
            return SlotInfo.EMPTY;
        }
    }
}
