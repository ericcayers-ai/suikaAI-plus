package dev.suika.dash;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry that holds all active {@link RunMetrics} objects.
 * Trainers create a run on startup; the dashboard reads all active runs.
 */
public final class DashboardRegistry {

    private static final DashboardRegistry INSTANCE = new DashboardRegistry();
    private final Map<String, RunMetrics> runs = new ConcurrentHashMap<>();

    private DashboardRegistry() {}

    public static DashboardRegistry get() { return INSTANCE; }

    /** Create and register a new run, returning its metrics store. */
    public RunMetrics createRun(String algorithmName) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        RunMetrics m = new RunMetrics(id, algorithmName);
        runs.put(id, m);
        return m;
    }

    public RunMetrics       run(String id)  { return runs.get(id); }
    public Collection<RunMetrics> allRuns() { return runs.values(); }
    public void             remove(String id) { runs.remove(id); }
    public int              activeRunCount()  { return runs.size(); }
}
