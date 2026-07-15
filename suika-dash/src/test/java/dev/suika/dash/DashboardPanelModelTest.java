package dev.suika.dash;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DashboardPanelModelTest {

    @Test
    void rowsReflectRegistryRuns() {
        DashboardRegistry reg = DashboardRegistry.get();
        RunMetrics m = reg.createRun("unit-test-algo");
        m.record(1, Map.of("score", 10.0, "reward", 0.5));
        DashboardPanelModel model = new DashboardPanelModel(reg);
        assertTrue(model.activeCount() >= 1);
        assertTrue(model.rows().stream().anyMatch(r -> r.id().equals(m.id())));
        assertFalse(model.series(m.id(), "score").isEmpty());
        assertTrue(model.exportSummary().contains(m.id()));
        reg.remove(m.id());
    }
}
