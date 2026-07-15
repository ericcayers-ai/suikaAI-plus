package dev.suika.game;

/**
 * Shared experiment-status snapshot for the persistent rail on Playground and
 * Control Center — the signature workflow UI element (not decorative chrome).
 */
public final class ExperimentStatus {

    public enum Health { IDLE, READY, RUNNING, WARNING, ERROR }

    public final String techniqueLabel;
    public final String presetLabel;
    public final String hardwareLabel;
    public final String pythonLabel;
    public final String runLabel;
    public final Health health;
    public final String detail;

    public ExperimentStatus(String techniqueLabel, String presetLabel, String hardwareLabel,
                            String pythonLabel, String runLabel, Health health, String detail) {
        this.techniqueLabel = techniqueLabel;
        this.presetLabel = presetLabel;
        this.hardwareLabel = hardwareLabel;
        this.pythonLabel = pythonLabel;
        this.runLabel = runLabel;
        this.health = health;
        this.detail = detail == null ? "" : detail;
    }

    /** Playground / pre-launch snapshot. */
    public static ExperimentStatus forPlayground(PlaygroundConfig cfg, GameSettings settings) {
        String py = PythonSetup.isReady() ? "Python ready" : "Python not set up";
        Health h = PythonSetup.isReady() || !cfg.technique.python ? Health.READY : Health.WARNING;
        String detail = PresetCalibration.calibrated()
                ? HardwarePresets.hardwareLabel()
                : "Calibrate presets in Settings for hardware-aware budgets";
        if (cfg.technique.python && !PythonSetup.isReady())
            detail = "SETUP Python in Settings → AI before GPU training";
        return new ExperimentStatus(
                cfg.technique.display,
                cfg.preset.label,
                HardwarePresets.hardwareLabel(),
                py,
                "Ready to launch",
                h,
                detail);
    }

    /** Live Control Center snapshot. */
    public static ExperimentStatus forLiveRun(PlaygroundConfig cfg, TechniqueRunner runner,
                                              boolean paused, String watchdogNote) {
        String run = paused ? "Paused" : "Running · " + cfg.speedLabel();
        Health h = Health.RUNNING;
        String detail = runner != null ? shortStats(runner) : "";
        if (watchdogNote != null && !watchdogNote.isBlank()) {
            h = Health.WARNING;
            detail = watchdogNote;
        }
        return new ExperimentStatus(
                cfg.technique.display,
                cfg.preset.label,
                HardwarePresets.hardwareLabel(),
                PythonSetup.isReady() ? "Python ready" : "Python n/a",
                run,
                h,
                detail);
    }

    private static String shortStats(TechniqueRunner runner) {
        try {
            long score = runner.board().score();
            return "score " + score + (runner.board().gameOver() ? " · game over" : "");
        } catch (RuntimeException e) {
            return "";
        }
    }
}
