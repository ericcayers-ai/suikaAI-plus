package dev.suika.dash;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Pure read-model for the in-app lab dashboard over {@link DashboardRegistry}.
 * Keeps headless {@link ConsoleExporter} paths unchanged.
 */
public final class DashboardPanelModel {

    public record RunRow(String id, String algorithm, int steps, double latestScore,
                         double latestReward, String start) {}

    public record SeriesPoint(int step, double value) {}

    private final DashboardRegistry registry;

    public DashboardPanelModel(DashboardRegistry registry) {
        this.registry = registry;
    }

    public List<RunRow> rows() {
        List<RunRow> out = new ArrayList<>();
        for (RunMetrics m : registry.allRuns()) {
            out.add(new RunRow(
                    m.id(),
                    m.algorithmName(),
                    m.stepCount(),
                    m.latest("score"),
                    m.latest("reward"),
                    m.startTime().toString()));
        }
        out.sort(Comparator.comparing(RunRow::start).reversed());
        return out;
    }

    public List<SeriesPoint> series(String runId, String key) {
        RunMetrics m = registry.run(runId);
        if (m == null) return List.of();
        List<SeriesPoint> pts = new ArrayList<>();
        for (RunMetrics.Step s : m.steps()) {
            Double v = s.scalars().get(key);
            if (v != null) pts.add(new SeriesPoint(s.step(), v));
        }
        return pts;
    }

    public String exportSummary() {
        StringBuilder sb = new StringBuilder("# suika dashboard export\n");
        for (RunRow r : rows()) {
            sb.append(String.format(Locale.ROOT,
                    "id=%s algo=%s steps=%d score=%.2f reward=%.4f start=%s%n",
                    r.id(), r.algorithm(), r.steps(),
                    Double.isNaN(r.latestScore()) ? 0 : r.latestScore(),
                    Double.isNaN(r.latestReward()) ? 0 : r.latestReward(),
                    r.start()));
        }
        return sb.toString();
    }

    public int activeCount() { return registry.activeRunCount(); }
}
