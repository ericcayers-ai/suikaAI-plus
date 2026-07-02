package dev.suika.game;

import dev.suika.ai.MlpPolicy;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

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
            in.readInt(); // paramCount (or MAGIC_CONFIG for a config-only save — either way, ignored here)
            int len = in.readInt();
            in.skipBytes(len * Double.BYTES);
            long ts = in.readLong();
            double score = in.readDouble();
            return new SlotInfo(true, ts, score);
        } catch (IOException e) {
            return SlotInfo.EMPTY;
        }
    }

    // -------------------------------------------------------------------------
    // Config-only saves — for techniques with no learnable weights (planning,
    // baselines, and the JVM surrogates for Python-family techniques). Behavior for
    // these is fully determined by technique + hyperparameters, so persisting that
    // pair is a complete "save"; there's simply no weight array to write. Shares the
    // same slot files as {@link #save}/{@link #load} above (one save per technique
    // per slot, whichever kind applies), distinguished by a negative sentinel where
    // the weight format always writes a positive paramCount — so {@link #load} on a
    // config-only file, and {@link #loadConfig} on a weights file, both cleanly fail
    // closed instead of misreading bytes.
    // -------------------------------------------------------------------------

    private static final int MAGIC_CONFIG = -2;

    /** What a config-only slot holds: technique hyperparameters plus the same
     *  timestamp/score metadata a weights slot carries. */
    record ConfigSlot(Map<String, Double> params, long savedAtMillis, double score) {}

    static void saveConfig(String techniqueId, int slot, Map<String, Double> params, double score) {
        try {
            Path dir = saveDir(techniqueId);
            Files.createDirectories(dir);
            Path target = slotFile(techniqueId, slot);
            Path tmp = dir.resolve("slot" + slot + ".dat.tmp");
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(tmp))) {
                out.writeInt(MAGIC_CONFIG);
                out.writeInt(0);                 // no weight array
                out.writeLong(System.currentTimeMillis());
                out.writeDouble(score);
                out.writeInt(params.size());
                for (var e : params.entrySet()) {
                    out.writeUTF(e.getKey());
                    out.writeDouble(e.getValue());
                }
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code null} if the slot doesn't exist or doesn't hold a config-only save
     *  (e.g. it holds trained weights instead — use {@link #load} for those). */
    static ConfigSlot loadConfig(String techniqueId, int slot) {
        Path f = slotFile(techniqueId, slot);
        if (!Files.exists(f)) return null;
        try (DataInputStream in = new DataInputStream(Files.newInputStream(f))) {
            int magic = in.readInt();
            if (magic != MAGIC_CONFIG) return null;
            in.readInt(); // weight length, always 0 here
            long ts = in.readLong();
            double score = in.readDouble();
            int n = in.readInt();
            Map<String, Double> params = new LinkedHashMap<>(Math.max(4, n * 2));
            for (int i = 0; i < n; i++) params.put(in.readUTF(), in.readDouble());
            return new ConfigSlot(params, ts, score);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
