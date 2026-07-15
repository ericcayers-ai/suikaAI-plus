package dev.suika.game;

import java.time.Instant;
import java.util.Locale;

/**
 * Portable end-of-run summary text for Game Over and Control Center export.
 */
public final class RunSummaryExport {

    private RunSummaryExport() {}

    public static String format(String modeLabel, String techniqueOrAgent,
                                long score, long seed, String highestFruit,
                                int fruitCount, String extra) {
        StringBuilder sb = new StringBuilder();
        sb.append("# suika-ai-plus run summary\n");
        sb.append("time=").append(Instant.now()).append('\n');
        sb.append("mode=").append(modeLabel).append('\n');
        sb.append("agent=").append(techniqueOrAgent).append('\n');
        sb.append("score=").append(score).append('\n');
        sb.append("seed=").append(seed).append('\n');
        if (highestFruit != null) sb.append("highestFruit=").append(highestFruit).append('\n');
        sb.append("fruitOnBoard=").append(fruitCount).append('\n');
        if (extra != null && !extra.isBlank()) sb.append("notes=").append(extra).append('\n');
        return sb.toString();
    }

    /** Human one-liner for toasts. */
    public static String oneLiner(long score, long seed) {
        return String.format(Locale.ROOT, "Score %d · seed %d copied", score, seed);
    }

    /** Writes under {@code ~/.suikai/runs/}. Returns absolute path or error message. */
    public static String writeFile(String body, String tip) {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(
                    System.getProperty("user.home"), ".suikai", "runs");
            java.nio.file.Files.createDirectories(dir);
            String name = (tip == null || tip.isBlank() ? "run" : tip.replaceAll("[^a-zA-Z0-9_-]", "_"))
                    + "-" + System.currentTimeMillis() + ".txt";
            java.nio.file.Path out = dir.resolve(name);
            java.nio.file.Files.writeString(out, body);
            return out.toAbsolutePath().toString();
        } catch (Exception e) {
            return "Export failed: " + e.getMessage();
        }
    }
}
