package dev.suika.game;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Import / export of a {@link PlaygroundConfig} experiment as a portable
 * {@code key=value} block (clipboard or {@code ~/.suikai/experiments/*.suika-exp}).
 */
public final class ExperimentIO {

    public static final String HEADER = "# suika-ai-plus experiment v1";

    private ExperimentIO() {}

    /** Serialises the live config into a portable text blob. */
    public static String exportText(PlaygroundConfig cfg) {
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER).append('\n');
        put(sb, "technique", cfg.technique.id);
        put(sb, "preset", cfg.preset.name());
        put(sb, "speedIndex", cfg.speedIndex);
        put(sb, "parallelism", cfg.parallelism);
        put(sb, "actionBins", cfg.actionBins);
        put(sb, "rollouts", cfg.rollouts);
        put(sb, "populationSize", cfg.populationSize);
        put(sb, "mutationSigma", cfg.mutationSigma);
        put(sb, "mutationSigmaIndex", cfg.mutationSigmaIndex);
        put(sb, "targetReturn", cfg.targetReturn);
        put(sb, "learningRate", cfg.learningRate);
        put(sb, "maxThinkMs", cfg.maxThinkMs);
        put(sb, "autoDrop", cfg.autoDrop);
        put(sb, "ensembleDonorIndex", cfg.ensembleDonorIndex);
        put(sb, "ensembleDonorSlot", cfg.ensembleDonorSlot);
        put(sb, "netWeightIndex", cfg.netWeightIndex);
        put(sb, "tieThresholdIndex", cfg.tieThresholdIndex);
        put(sb, "ucbCIndex", cfg.ucbCIndex);
        put(sb, "adaptLrIndex", cfg.adaptLrIndex);
        put(sb, "tensorboardDetailed", cfg.tensorboardDetailed);
        put(sb, "selectionIndex", cfg.selectionIndex);
        put(sb, "crossover", cfg.crossover);
        put(sb, "sigmaAnneal", cfg.sigmaAnneal);
        put(sb, "ghostView", cfg.ghostView);
        put(sb, "simsPerGenIndex", cfg.simsPerGenIndex);
        put(sb, "ghostCullIndex", cfg.ghostCullIndex);
        put(sb, "eliteViewIndex", cfg.eliteViewIndex);
        return sb.toString();
    }

    /**
     * Applies a previously exported blob onto {@code cfg}. Returns an error message,
     * or {@code null} on success.
     */
    public static String importText(PlaygroundConfig cfg, String text) {
        if (text == null || text.isBlank()) return "Clipboard empty";
        Map<String, String> map = parse(text);
        if (map.isEmpty()) return "No key=value pairs found";
        String techId = map.get("technique");
        if (techId == null) return "Missing technique=";
        AiTechnique tech = null;
        for (AiTechnique t : AiTechnique.values()) if (t.id.equals(techId)) { tech = t; break; }
        if (tech == null) return "Unknown technique: " + techId;

        cfg.selectDefaultsFor(tech);
        try {
            if (map.containsKey("preset")) {
                try {
                    HardwarePresets p = HardwarePresets.valueOf(map.get("preset").toUpperCase(Locale.ROOT));
                    if (PresetCalibration.calibrated()) p.applyTo(cfg);
                    else cfg.preset = p;
                } catch (IllegalArgumentException ignored) { /* keep defaults */ }
            }
            cfg.speedIndex = clampIdx(getInt(map, "speedIndex", cfg.speedIndex), PlaygroundConfig.SPEEDS.length);
            cfg.parallelism = getInt(map, "parallelism", cfg.parallelism);
            cfg.actionBins = getInt(map, "actionBins", cfg.actionBins);
            cfg.rollouts = getInt(map, "rollouts", cfg.rollouts);
            cfg.populationSize = getInt(map, "populationSize", cfg.populationSize);
            cfg.mutationSigma = getDouble(map, "mutationSigma", cfg.mutationSigma);
            cfg.mutationSigmaIndex = clampIdx(getInt(map, "mutationSigmaIndex", cfg.mutationSigmaIndex),
                    PlaygroundConfig.MUTATION_SIGMA_OPTIONS.length);
            cfg.targetReturn = getDouble(map, "targetReturn", cfg.targetReturn);
            cfg.learningRate = getDouble(map, "learningRate", cfg.learningRate);
            cfg.maxThinkMs = getLong(map, "maxThinkMs", cfg.maxThinkMs);
            cfg.autoDrop = getBool(map, "autoDrop", cfg.autoDrop);
            cfg.ensembleDonorIndex = clampIdx(getInt(map, "ensembleDonorIndex", cfg.ensembleDonorIndex),
                    PlaygroundConfig.ENSEMBLE_DONORS.length);
            cfg.ensembleDonorSlot = getInt(map, "ensembleDonorSlot", cfg.ensembleDonorSlot);
            cfg.netWeightIndex = clampIdx(getInt(map, "netWeightIndex", cfg.netWeightIndex),
                    PlaygroundConfig.NET_WEIGHT_OPTIONS.length);
            cfg.tieThresholdIndex = clampIdx(getInt(map, "tieThresholdIndex", cfg.tieThresholdIndex),
                    PlaygroundConfig.TIE_THRESHOLD_OPTIONS.length);
            cfg.ucbCIndex = clampIdx(getInt(map, "ucbCIndex", cfg.ucbCIndex),
                    PlaygroundConfig.UCB_C_OPTIONS.length);
            cfg.adaptLrIndex = clampIdx(getInt(map, "adaptLrIndex", cfg.adaptLrIndex),
                    PlaygroundConfig.ADAPT_LR_OPTIONS.length);
            cfg.tensorboardDetailed = getBool(map, "tensorboardDetailed", cfg.tensorboardDetailed);
            cfg.selectionIndex = getInt(map, "selectionIndex", cfg.selectionIndex);
            cfg.crossover = getBool(map, "crossover", cfg.crossover);
            cfg.sigmaAnneal = getBool(map, "sigmaAnneal", cfg.sigmaAnneal);
            cfg.ghostView = getBool(map, "ghostView", cfg.ghostView);
            cfg.simsPerGenIndex = clampIdx(getInt(map, "simsPerGenIndex", cfg.simsPerGenIndex),
                    PlaygroundConfig.SIMS_PER_GEN_OPTIONS.length);
            cfg.ghostCullIndex = clampIdx(getInt(map, "ghostCullIndex", cfg.ghostCullIndex),
                    PlaygroundConfig.GHOST_CULL_OPTIONS.length);
            cfg.eliteViewIndex = clampIdx(getInt(map, "eliteViewIndex", cfg.eliteViewIndex),
                    PlaygroundConfig.ELITE_VIEW_OPTIONS.length);
        } catch (RuntimeException e) {
            return "Parse error: " + e.getMessage();
        }
        return null;
    }

    /** Writes under {@code ~/.suikai/experiments/<stamp>.suika-exp}. Returns path or error. */
    public static String exportToFile(PlaygroundConfig cfg) {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(
                    System.getProperty("user.home"), ".suikai", "experiments");
            java.nio.file.Files.createDirectories(dir);
            String name = cfg.technique.id + "-" + System.currentTimeMillis() + ".suika-exp";
            java.nio.file.Path out = dir.resolve(name);
            java.nio.file.Files.writeString(out, exportText(cfg));
            return out.toAbsolutePath().toString();
        } catch (Exception e) {
            return "Export failed: " + e.getMessage();
        }
    }

    private static void put(StringBuilder sb, String k, Object v) {
        sb.append(k).append('=').append(v).append('\n');
    }

    private static Map<String, String> parse(String text) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            map.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return map;
    }

    private static int clampIdx(int i, int len) { return Math.max(0, Math.min(i, len - 1)); }

    private static int getInt(Map<String, String> m, String k, int def) {
        String v = m.get(k); if (v == null) return def;
        return Integer.parseInt(v.trim());
    }

    private static long getLong(Map<String, String> m, String k, long def) {
        String v = m.get(k); if (v == null) return def;
        return Long.parseLong(v.trim());
    }

    private static double getDouble(Map<String, String> m, String k, double def) {
        String v = m.get(k); if (v == null) return def;
        return Double.parseDouble(v.trim());
    }

    private static boolean getBool(Map<String, String> m, String k, boolean def) {
        String v = m.get(k); if (v == null) return def;
        return Boolean.parseBoolean(v.trim());
    }
}
