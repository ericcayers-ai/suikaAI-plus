package dev.suika.dash;

import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Simple console exporter — prints run metrics in a human-readable format.
 * Serves as the Phase 3 dashboard until ImGui is wired in Phase 6.
 */
public final class ConsoleExporter {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public void export(RunMetrics metrics) {
        System.out.printf("[%s] run=%-8s algo=%-12s steps=%d%n",
                FMT.format(metrics.startTime().atZone(java.time.ZoneId.systemDefault())),
                metrics.id(), metrics.algorithmName(), metrics.stepCount());

        if (!metrics.steps().isEmpty()) {
            RunMetrics.Step last = metrics.steps().getLast();
            System.out.print("  latest: ");
            for (Map.Entry<String, Double> e : last.scalars().entrySet()) {
                System.out.printf("%s=%.3f  ", e.getKey(), e.getValue());
            }
            System.out.println();
        }
    }

    /** Print a summary table of all active runs. */
    public void exportAll(DashboardRegistry registry) {
        System.out.println("=== Dashboard snapshot ===");
        registry.allRuns().forEach(this::export);
        System.out.println("==========================");
    }
}
