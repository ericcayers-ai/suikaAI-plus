package dev.suika.game;

import dev.suika.ai.MlpPolicy;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Save/load for trained models and technique progress.
 *
 * <p><b>v0.13 format (human-readable folders):</b> every slot is its own folder under
 * {@code ~/.suikai/saves/<technique-id>/slot<N>/}, holding plain-text files so a save
 * can be inspected, diffed, and copied without the app:
 * <ul>
 *   <li>{@code info.txt} — model information (technique, kind, date, score, architecture).</li>
 *   <li>{@code progress.txt} — game progress + previous graph data (fitness/mean/diversity
 *       series as CSV rows).</li>
 *   <li>{@code model.txt} — the raw model/network data: one weight per line for a trained
 *       {@link MlpPolicy}, or {@code key = value} hyperparameters for a config-only
 *       technique. If a real ONNX export exists it is kept verbatim as {@code model.onnx}.</li>
 *   <li>{@code <technique-id>-slot<N>.sav} — a small quickstart manifest that the app's
 *       front menu can open to load this save directly (see {@link MainMenuScreen}).</li>
 * </ul>
 *
 * <p><b>Backwards compatibility:</b> the pre-v0.13 single binary file
 * ({@code slot<N>.dat}) is still read when no folder exists, so existing saves keep
 * working; the next save of that slot upgrades it to the folder format.
 */
final class ModelSlots {

    private ModelSlots() {}

    static final int SLOT_COUNT = 3;

    // GeneticTrainer / CmaEsTrainer / BehavioralCloningTrainer all hard-code this same
    // hidden/output architecture internally — centralised here so save/load has exactly
    // one place that needs to stay in sync with them.
    static final int HIDDEN_SIZE = 64;
    static final int OUTPUT_BINS = 32;

    static final String KIND_WEIGHTS = "weights";
    static final String KIND_CONFIG  = "config";

    /** A freshly-constructed policy with the exact architecture every save uses. */
    static MlpPolicy newCompatiblePolicy() {
        return new MlpPolicy(dev.suika.env.StateObservationEncoder.TOTAL, HIDDEN_SIZE, OUTPUT_BINS);
    }

    /** What a slot holds, without loading the (potentially large) weight array. */
    record SlotInfo(boolean present, long savedAtMillis, double score) {
        static final SlotInfo EMPTY = new SlotInfo(false, 0L, 0.0);
    }

    /** What a config-only slot holds: technique hyperparameters plus timestamp/score. */
    record ConfigSlot(Map<String, Double> params, long savedAtMillis, double score) {}

    /**
     * Optional extra data attached to a save: named graph series (fitness/mean/diversity
     * histories) written into {@code progress.txt} so a reloaded slot can restore its
     * charts. Empty by default — callers that have no history pass {@link #NONE}.
     */
    record SaveExtras(Map<String, float[]> graphs) {
        static final SaveExtras NONE = new SaveExtras(Map.of());
    }

    private static Path baseDir(String techniqueId) {
        return Path.of(System.getProperty("user.home"), ".suikai", "saves", techniqueId);
    }

    /** The folder for one slot in the v0.13 format. Public-ish for the UI's "reveal folder". */
    static Path slotDir(String techniqueId, int slot) {
        return baseDir(techniqueId).resolve("slot" + slot);
    }

    /** The pre-v0.13 single binary file (only read for backwards compatibility). */
    private static Path legacyFile(String techniqueId, int slot) {
        return baseDir(techniqueId).resolve("slot" + slot + ".dat");
    }

    private static Path savManifest(String techniqueId, int slot) {
        return slotDir(techniqueId, slot).resolve(techniqueId + "-slot" + slot + ".sav");
    }

    // -------------------------------------------------------------------------
    // Save
    // -------------------------------------------------------------------------

    static void save(String techniqueId, int slot, MlpPolicy policy, double score) {
        save(techniqueId, slot, policy, score, SaveExtras.NONE);
    }

    /** Trained-weights save (Evolution / Imitation) in the folder format. */
    static void save(String techniqueId, int slot, MlpPolicy policy, double score, SaveExtras extras) {
        double[] w = policy.getWeights();
        StringBuilder model = new StringBuilder();
        for (double v : w) model.append(v).append('\n');
        writeSlot(techniqueId, slot, KIND_WEIGHTS, score, extras,
                infoLines(techniqueId, slot, KIND_WEIGHTS, score,
                        "arch = " + dev.suika.env.StateObservationEncoder.TOTAL + "x" + HIDDEN_SIZE + "x" + OUTPUT_BINS,
                        "params = " + w.length),
                "model.txt", model.toString(), Map.of());
    }

    static void saveConfig(String techniqueId, int slot, Map<String, Double> params, double score) {
        saveConfig(techniqueId, slot, params, score, SaveExtras.NONE);
    }

    /** Config-only save (planning / baselines / Python surrogates / learning ensembles). */
    static void saveConfig(String techniqueId, int slot, Map<String, Double> params, double score, SaveExtras extras) {
        StringBuilder model = new StringBuilder();
        for (var e : params.entrySet()) model.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        writeSlot(techniqueId, slot, KIND_CONFIG, score, extras,
                infoLines(techniqueId, slot, KIND_CONFIG, score, "params = " + params.size()),
                "model.txt", model.toString(), params);
    }

    private static List<String> infoLines(String techniqueId, int slot, String kind, double score, String... extra) {
        List<String> lines = new ArrayList<>();
        lines.add("# Suika AI save - model information");
        lines.add("technique = " + techniqueId);
        lines.add("slot = " + slot);
        lines.add("kind = " + kind);
        long now = System.currentTimeMillis();
        lines.add("saved = " + new java.util.Date(now) + " (" + now + ")");
        lines.add("score = " + score);
        for (String x : extra) lines.add(x);
        return lines;
    }

    /**
     * Writes all four files of a slot folder atomically-ish (each file individually, then
     * the {@code .sav} manifest last so a half-written folder is never mistaken for a
     * complete save — {@link #info} keys off the manifest's presence).
     */
    private static void writeSlot(String techniqueId, int slot, String kind, double score, SaveExtras extras,
                                  List<String> info, String modelFileName, String modelBody,
                                  Map<String, Double> configParams) {
        try {
            Path dir = slotDir(techniqueId, slot);
            Files.createDirectories(dir);

            writeText(dir.resolve("info.txt"), String.join("\n", info) + "\n");

            StringBuilder progress = new StringBuilder("# Suika AI save - game progress & graph history\n");
            progress.append("score = ").append(score).append('\n');
            progress.append("saved = ").append(System.currentTimeMillis()).append('\n');
            for (var g : extras.graphs().entrySet()) {
                progress.append("# graph: ").append(g.getKey()).append('\n');
                progress.append(g.getKey()).append(" = ").append(csv(g.getValue())).append('\n');
            }
            writeText(dir.resolve("progress.txt"), progress.toString());

            writeText(dir.resolve(modelFileName), modelBody);

            long now = System.currentTimeMillis();
            StringBuilder sav = new StringBuilder("# Suika AI quickstart save\n");
            sav.append("technique = ").append(techniqueId).append('\n');
            sav.append("slot = ").append(slot).append('\n');
            sav.append("kind = ").append(kind).append('\n');
            sav.append("score = ").append(score).append('\n');
            sav.append("saved = ").append(now).append('\n');
            sav.append("folder = ").append(dir.toAbsolutePath()).append('\n');
            for (var e : configParams.entrySet()) sav.append("param.").append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
            writeText(savManifest(techniqueId, slot), sav.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeText(Path p, String body) throws IOException {
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.writeString(tmp, body, StandardCharsets.UTF_8);
        Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static String csv(float[] v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) { if (i > 0) sb.append(','); sb.append(v[i]); }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Load
    // -------------------------------------------------------------------------

    /** Loads trained weights into {@code policy}. Reads the folder format first, then the
     *  legacy binary. {@code false} if absent, architecture-mismatched, or unreadable. */
    static boolean load(String techniqueId, int slot, MlpPolicy policy) {
        Path model = slotDir(techniqueId, slot).resolve("model.txt");
        if (Files.exists(savManifest(techniqueId, slot)) && Files.exists(model)) {
            try {
                List<Double> vals = new ArrayList<>();
                try (BufferedReader r = Files.newBufferedReader(model, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#") || line.contains("=")) continue;
                        vals.add(Double.parseDouble(line));
                    }
                }
                if (vals.size() != policy.paramCount()) return false;
                double[] w = new double[vals.size()];
                for (int i = 0; i < w.length; i++) w[i] = vals.get(i);
                policy.setWeights(w);
                return true;
            } catch (IOException | RuntimeException e) {
                return false;
            }
        }
        return loadLegacyWeights(techniqueId, slot, policy);
    }

    /** {@code null} unless the slot holds a config-only save (folder or legacy). */
    static ConfigSlot loadConfig(String techniqueId, int slot) {
        Path dir = slotDir(techniqueId, slot);
        if (Files.exists(savManifest(techniqueId, slot))) {
            if (!KIND_CONFIG.equals(readKind(dir))) return null;
            Map<String, Double> params = new LinkedHashMap<>();
            try (BufferedReader r = Files.newBufferedReader(dir.resolve("model.txt"), StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    int eq = line.indexOf('=');
                    if (line.startsWith("#") || eq < 0) continue;
                    try { params.put(line.substring(0, eq).trim(), Double.parseDouble(line.substring(eq + 1).trim())); }
                    catch (NumberFormatException ignored) { /* skip non-numeric */ }
                }
            } catch (IOException e) {
                return null;
            }
            SlotInfo si = info(techniqueId, slot);
            return new ConfigSlot(params, si.savedAtMillis(), si.score());
        }
        return loadLegacyConfig(techniqueId, slot);
    }

    /** Graph series persisted alongside a save (empty if none / legacy). */
    static Map<String, float[]> loadGraphs(String techniqueId, int slot) {
        Path progress = slotDir(techniqueId, slot).resolve("progress.txt");
        if (!Files.exists(progress)) return Map.of();
        Map<String, float[]> out = new LinkedHashMap<>();
        try (BufferedReader r = Files.newBufferedReader(progress, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("#") || line.startsWith("score") || line.startsWith("saved")) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String name = line.substring(0, eq).trim();
                String[] parts = line.substring(eq + 1).trim().split(",");
                if (parts.length == 0 || parts[0].isEmpty()) continue;
                float[] vals = new float[parts.length];
                boolean ok = true;
                for (int i = 0; i < parts.length; i++) {
                    try { vals[i] = Float.parseFloat(parts[i].trim()); } catch (NumberFormatException e) { ok = false; break; }
                }
                if (ok) out.put(name, vals);
            }
        } catch (IOException e) {
            return Map.of();
        }
        return out;
    }

    static SlotInfo info(String techniqueId, int slot) {
        Path sav = savManifest(techniqueId, slot);
        if (Files.exists(sav)) {
            long saved = 0L; double score = 0.0;
            try (BufferedReader r = Files.newBufferedReader(sav, StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("saved")) saved = parseLong(after(line), 0L);
                    else if (line.startsWith("score")) score = parseDouble(after(line), 0.0);
                }
            } catch (IOException e) {
                return SlotInfo.EMPTY;
            }
            return new SlotInfo(true, saved, score);
        }
        return infoLegacy(techniqueId, slot);
    }

    /** The kind of a slot's save ({@link #KIND_WEIGHTS}/{@link #KIND_CONFIG}), or
     *  {@code null} if the slot is empty. Cheap — reads only the manifest/info. */
    static String slotKind(String techniqueId, int slot) {
        if (Files.exists(savManifest(techniqueId, slot))) {
            String k = readKind(slotDir(techniqueId, slot));
            return k;
        }
        // Legacy binary: config saves start with MAGIC_CONFIG, weights with a positive count.
        Path f = legacyFile(techniqueId, slot);
        if (!Files.exists(f)) return null;
        try (DataInputStream in = new DataInputStream(Files.newInputStream(f))) {
            return in.readInt() == MAGIC_CONFIG ? KIND_CONFIG : KIND_WEIGHTS;
        } catch (IOException e) {
            return null;
        }
    }

    /** True when a slot holds loadable trained WEIGHTS (not just a config save). */
    static boolean hasWeights(String techniqueId, int slot) {
        return KIND_WEIGHTS.equals(slotKind(techniqueId, slot));
    }

    private static String readKind(Path dir) {
        try (BufferedReader r = Files.newBufferedReader(dir.resolve("info.txt"), StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) if (line.startsWith("kind")) return after(line);
        } catch (IOException ignored) { /* fall through */ }
        return KIND_WEIGHTS;
    }

    private static String after(String kvLine) {
        int eq = kvLine.indexOf('=');
        return eq < 0 ? "" : kvLine.substring(eq + 1).trim();
    }
    private static long parseLong(String s, long d)   { try { return Long.parseLong(s); }   catch (Exception e) { return d; } }
    private static double parseDouble(String s, double d) { try { return Double.parseDouble(s); } catch (Exception e) { return d; } }

    // -------------------------------------------------------------------------
    // Reveal the folder in the OS file manager
    // -------------------------------------------------------------------------

    /** Opens the slot's folder in the desktop file manager. Returns the folder path (for
     *  showing to the user) whether or not the reveal itself succeeded. */
    static String revealSlotFolder(String techniqueId, int slot) {
        Path dir = slotDir(techniqueId, slot);
        try {
            Files.createDirectories(dir);
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(dir.toFile());
            }
        } catch (Exception e) {
            // Best-effort — some headless/locked-down environments have no file manager.
        }
        return dir.toAbsolutePath().toString();
    }

    // -------------------------------------------------------------------------
    // Legacy (pre-v0.13) binary readers — never written any more.
    // -------------------------------------------------------------------------

    private static final int MAGIC_CONFIG = -2;

    private static boolean loadLegacyWeights(String techniqueId, int slot, MlpPolicy policy) {
        Path f = legacyFile(techniqueId, slot);
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

    private static ConfigSlot loadLegacyConfig(String techniqueId, int slot) {
        Path f = legacyFile(techniqueId, slot);
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

    private static SlotInfo infoLegacy(String techniqueId, int slot) {
        Path f = legacyFile(techniqueId, slot);
        if (!Files.exists(f)) return SlotInfo.EMPTY;
        try (DataInputStream in = new DataInputStream(Files.newInputStream(f))) {
            in.readInt(); // paramCount or MAGIC_CONFIG
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
