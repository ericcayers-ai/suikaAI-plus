package dev.suika.game;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Version / matrix / prefs contract surfaces — see docs/contracts.md.
 */
class CompatibilityContractTest {

    private static final Path WORKSPACE = resolveWorkspace();

    private static Path resolveWorkspace() {
        Path here = Path.of("").toAbsolutePath();
        if (Files.isDirectory(here.resolve("suika-game"))) return here;
        return here.getParent();
    }

    @Test
    void themeVersionMatchesGradleAndPython() throws Exception {
        String gradle = readGradleVersion();
        String python = readPythonVersion();
        String setup = readSetupPyVersion();
        String fallback = readSuikaVersionFallback();
        assertEquals(gradle, Theme.VERSION,
                "Theme.VERSION must match build.gradle.kts (resource stamp / fallback)");
        assertEquals(gradle, python,
                "python/suika/__init__.py __version__ must match build.gradle.kts");
        assertEquals(gradle, setup,
                "python/setup.py version must match build.gradle.kts");
        assertEquals(gradle, fallback,
                "SuikaVersion.FALLBACK must match build.gradle.kts");
        assertFalse(Theme.VERSION.isBlank());
        assertFalse(Theme.VERSION.contains("@"));
    }

    @Test
    void playgroundMatrixIsThirteenPlusFive() {
        long techniques = Arrays.stream(AiTechnique.values()).filter(t -> !t.isEnsemble()).count();
        long ensembles = Arrays.stream(AiTechnique.values()).filter(AiTechnique::isEnsemble).count();
        assertEquals(13, techniques, "curated techniques");
        assertEquals(5, ensembles, "curated ensembles");
        assertEquals(18, AiTechnique.values().length);
    }

    @Test
    void prefsKeyStringsAreFrozen() {
        assertEquals("suika-display-settings", PrefsKeys.STORE);
        assertEquals("resHeightIndex", PrefsKeys.RES_HEIGHT_INDEX);
        assertEquals("fullscreen", PrefsKeys.FULLSCREEN);
        assertEquals("uiScaleIndex", PrefsKeys.UI_SCALE_INDEX);
        assertEquals("preferGpu", PrefsKeys.PREFER_GPU);
        assertEquals("autosaveIndex", PrefsKeys.AUTOSAVE_INDEX);
        assertEquals("jvmCpuOnly", PrefsKeys.JVM_CPU_ONLY);
        assertEquals("gpuMode", PrefsKeys.GPU_MODE);
        assertEquals("customValueEntry", PrefsKeys.CUSTOM_ENTRY);
        assertEquals("watchdogEnabled", PrefsKeys.WATCHDOG);
        assertEquals("presetsCalibrated", PrefsKeys.CALIBRATED);
        assertEquals("presetsSimsPerSec", PrefsKeys.CALIB_SIMS);
        assertEquals("reducedMotion", PrefsKeys.REDUCED_MOTION);
        assertEquals("fpsIndex", PrefsKeys.FPS_INDEX);
        assertEquals("firstRunHelpSeen", PrefsKeys.FIRST_RUN_HELP_SEEN);
    }

    @Test
    void modelSlotArchitectureMatchesEncoderContract() {
        assertEquals(32, ModelSlots.OUTPUT_BINS);
        assertEquals(
                dev.suika.ai.MlpPolicy.paramCount(
                        dev.suika.env.StateObservationEncoder.TOTAL,
                        ModelSlots.HIDDEN_SIZE,
                        ModelSlots.OUTPUT_BINS),
                ModelSlots.newCompatiblePolicy().paramCount());
    }

    private static String readGradleVersion() throws Exception {
        Path build = WORKSPACE.resolve("build.gradle.kts");
        String text = Files.readString(build, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("version\\s*=\\s*\"([^\"]+)\"").matcher(text);
        assertTrue(m.find(), "version= not found in build.gradle.kts");
        return m.group(1);
    }

    private static String readPythonVersion() throws Exception {
        Path init = WORKSPACE.resolve("python/suika/__init__.py");
        String text = Files.readString(init, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("__version__\\s*=\\s*\"([^\"]+)\"").matcher(text);
        assertTrue(m.find(), "__version__ not found");
        return m.group(1);
    }

    private static String readSetupPyVersion() throws Exception {
        Path setup = WORKSPACE.resolve("python/setup.py");
        String text = Files.readString(setup, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("version\\s*=\\s*\"([^\"]+)\"").matcher(text);
        assertTrue(m.find(), "version= not found in python/setup.py");
        return m.group(1);
    }

    private static String readSuikaVersionFallback() throws Exception {
        Path src = WORKSPACE.resolve(
                "suika-game/src/main/java/dev/suika/game/SuikaVersion.java");
        String text = Files.readString(src, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("FALLBACK\\s*=\\s*\"([^\"]+)\"").matcher(text);
        assertTrue(m.find(), "FALLBACK not found in SuikaVersion.java");
        return m.group(1);
    }
}
