package dev.suika.dash;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DashboardTest {

    @Test
    void runMetricsRecordsSteps() {
        RunMetrics m = new RunMetrics("test-1", "genetic");
        m.record(0, "fitness/best", 100.0);
        m.record(1, "fitness/best", 150.0);
        assertEquals(2, m.stepCount());
        assertEquals(150.0, m.latest("fitness/best"), 1e-9);
    }

    @Test
    void registryCreatesUniqueRuns() {
        DashboardRegistry reg = DashboardRegistry.get();
        int before = reg.activeRunCount();
        RunMetrics r1 = reg.createRun("genetic");
        RunMetrics r2 = reg.createRun("cma-es");
        assertNotEquals(r1.id(), r2.id());
        assertTrue(reg.activeRunCount() >= before + 2);
        reg.remove(r1.id());
        reg.remove(r2.id());
    }

    @Test
    void evolutionLoggerRecordsGenerations() {
        EvolutionMetricsLogger logger = new EvolutionMetricsLogger("test-ga");
        logger.logGeneration(0, 10.0, 5.0, 1.0);
        logger.logGeneration(1, 20.0, 8.0, 0.9);
        assertEquals(2, logger.metrics().stepCount());
        assertEquals(20.0, logger.metrics().latest("fitness/best"), 1e-9);
        DashboardRegistry.get().remove(logger.metrics().id());
    }

    @Test
    void consoleExporterDoesNotThrow() {
        RunMetrics m = new RunMetrics("x", "test");
        m.record(0, "score", 42.0);
        assertDoesNotThrow(() -> new ConsoleExporter().export(m));
    }
}
