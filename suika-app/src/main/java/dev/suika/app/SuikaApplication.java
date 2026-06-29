package dev.suika.app;

import dev.suika.ai.*;
import dev.suika.core.GameCore;
import dev.suika.core.StepResult;
import dev.suika.dash.ConsoleExporter;
import dev.suika.dash.DashboardRegistry;
import dev.suika.dash.EvolutionMetricsLogger;
import dev.suika.env.*;

import java.util.Map;

/**
 * Application entry point.
 *
 * <ul>
 *   <li>Default (no args or no {@code --headless}): launches the full LibGDX windowed game.</li>
 *   <li>{@code --headless}: runs the CLI training demo (Explorer mode, GA, no display required).</li>
 * </ul>
 *
 * Run the GUI:   {@code ./gradlew :suika-app:run}
 * Run headless:  {@code ./gradlew :suika-app:run --args="--headless"}
 */
public class SuikaApplication {

    public static void main(String[] args) throws Exception {
        minimizeConsole();
        boolean headless = args.length > 0 && "--headless".equals(args[0]);
        if (!headless) {
            DesktopLauncher.launch();
            return;
        }

        System.out.println("=== Suika AI Sandbox — v" + dev.suika.game.Theme.VERSION + " ===");
        System.out.println("Running headless demo (Explorer → Quick Learner preset)\n");

        // --- Explorer mode: use a friendly preset ---
        AgentConfig cfg = AgentPreset.QUICK_LEARNER.config;
        System.out.println("Preset  : " + AgentPreset.QUICK_LEARNER.displayName);
        System.out.println("Desc    : " + AgentPreset.QUICK_LEARNER.description);
        System.out.println("Config  : " + cfg.agentId() + " " + cfg.hyperparameters() + "\n");

        // --- Wire environment ---
        SuikaEnv env = new SuikaEnv(
                ObservationMode.STATE,
                new ActionSpace.Discrete(32),
                RewardConfig.defaultConfig()
        );

        // --- Start a training run with dashboard ---
        EvolutionMetricsLogger logger = new EvolutionMetricsLogger("genetic");

        try (GeneticTrainer trainer = new GeneticTrainer(
                cfg.get("population_size", 20),
                cfg.get("elite_count",     4),
                cfg.get("mutation_sigma",  0.1),
                cfg.get("episodes_per_eval", 2),
                42L
        )) {
            ConsoleExporter exporter = new ConsoleExporter();

            for (int gen = 0; gen < 3; gen++) {
                trainer.update();
                logger.logGeneration(gen, trainer.bestFitness(), trainer.meanFitness(), 1.0);
                exporter.exportAll(DashboardRegistry.get());
            }

            // Evaluate best agent
            NeuralAgent best = trainer.bestAgent();
            System.out.println("\nBest agent after 3 generations:");
            GameCore eval = new GameCore(999L);
            int steps = 0;
            while (!eval.isGameOver() && steps < 100) {
                Object action = best.selectAction(eval.getState(), ActionSpec.discrete(32));
                double x = ActionSpec.discrete(32).toDropX(action,
                        dev.suika.core.PhysicsConfig.DROP_X_MIN,
                        dev.suika.core.PhysicsConfig.DROP_X_MAX);
                eval.dropAndSettle(x);
                steps++;
            }
            System.out.println("Score: " + eval.getScore() + " in " + steps + " drops");
        }

        // --- Show schema-driven hyperparams (Researcher mode) ---
        System.out.println("\nResearcher mode — Genetic Algorithm hyperparameter schema:");
        for (HyperparamSchema s : HyperparamSchema.forGenetic()) {
            System.out.printf("  %-20s = %-8s  [%s–%s]  %s%n",
                    s.key(), s.defaultValue(), s.min(), s.max(), s.helpText());
        }

        System.out.println("\nDone. Run `./gradlew test` to execute the full test suite.");
        System.out.println("Run `./gradlew :suika-app:run` to launch the GUI game.");
    }

    /**
     * Minimizes the console window that Windows attaches when the app is packaged
     * with {@code --win-console}. The game window is unaffected.
     */
    private static void minimizeConsole() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) return;
        try {
            new ProcessBuilder(
                "powershell.exe", "-NonInteractive", "-WindowStyle", "Hidden", "-Command",
                "Add-Type -Name WC -Namespace SC -MemberDefinition " +
                "'[DllImport(\"user32.dll\")] public static extern bool ShowWindow(IntPtr h,int n);" +
                "[DllImport(\"kernel32.dll\")] public static extern IntPtr GetConsoleWindow();';" +
                "[SC.WC]::ShowWindow([SC.WC]::GetConsoleWindow(),6)"
            ).start();
            Thread.sleep(200);
        } catch (Exception ignored) {}
    }
}
