package dev.suika.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the duplicated fruit tables across Java, assets JSON, and Python.
 * See docs/contracts.md — these four surfaces must stay identical.
 */
class FruitRadiusSyncTest {

    private static final Path REPO_ROOT = Path.of("").toAbsolutePath();
    // Test CWD is the module directory (suika-core) under Gradle.
    private static final Path WORKSPACE = Files.isDirectory(REPO_ROOT.resolve("suika-core"))
            ? REPO_ROOT
            : REPO_ROOT.getParent();

    @Test
    void standardLadderMatchesFruitTierRadiiAndScores() {
        FruitLadder ladder = FruitLadder.standard();
        FruitTier[] tiers = FruitTier.values();
        assertEquals(tiers.length, ladder.size());
        for (int i = 0; i < tiers.length; i++) {
            FruitLadder.Entry e = ladder.get(i);
            assertEquals(tiers[i].radius, e.radius(), 1e-5f,
                    "radius mismatch at " + tiers[i].name());
            assertEquals(tiers[i].mergeScore, e.mergeScore(),
                    "mergeScore mismatch at " + tiers[i].name());
            assertEquals(tiers[i].isDroppable(), e.droppable(),
                    "droppable mismatch at " + tiers[i].name());
        }
    }

    @Test
    void fruitsJsonMatchesFruitTier() throws IOException {
        Path json = WORKSPACE.resolve("suika-assets/src/main/resources/fruits.json");
        assertTrue(Files.isRegularFile(json), "missing " + json);
        String text = Files.readString(json, StandardCharsets.UTF_8);

        for (FruitTier t : FruitTier.values()) {
            Matcher m = Pattern.compile(
                    "\\{\\s*\"tier\"\\s*:\\s*" + t.tier + "\\s*,[^}]*\"radius\"\\s*:\\s*([0-9.]+)[^}]*\"mergeScore\"\\s*:\\s*(-?\\d+)[^}]*\"droppable\"\\s*:\\s*(true|false)",
                    Pattern.DOTALL).matcher(text);
            assertTrue(m.find(), "tier " + t.tier + " missing from fruits.json");
            assertEquals(t.radius, Float.parseFloat(m.group(1)), 1e-5f, "json radius tier " + t.tier);
            assertEquals(t.mergeScore, Integer.parseInt(m.group(2)), "json score tier " + t.tier);
            assertEquals(t.isDroppable(), Boolean.parseBoolean(m.group(3)), "json droppable tier " + t.tier);
        }
    }

    @Test
    void pythonFruitTiersMatchFruitTier() throws IOException {
        Path py = WORKSPACE.resolve("python/suika/env.py");
        assertTrue(Files.isRegularFile(py), "missing " + py);
        String text = Files.readString(py, StandardCharsets.UTF_8);

        // Capture tuples inside FRUIT_TIERS = [ ... ]
        int start = text.indexOf("FRUIT_TIERS");
        assertTrue(start >= 0, "FRUIT_TIERS not found");
        int bracket = text.indexOf('[', start);
        int end = text.indexOf(']', bracket);
        assertTrue(bracket > 0 && end > bracket);
        String block = text.substring(bracket, end + 1);

        Pattern tuple = Pattern.compile("\\(\\s*(\\d+)\\s*,\\s*([0-9.]+)\\s*,\\s*(-?\\d+)\\s*\\)");
        Matcher m = tuple.matcher(block);
        List<double[]> rows = new ArrayList<>();
        while (m.find()) {
            rows.add(new double[]{
                    Double.parseDouble(m.group(1)),
                    Double.parseDouble(m.group(2)),
                    Double.parseDouble(m.group(3))
            });
        }
        FruitTier[] tiers = FruitTier.values();
        assertEquals(tiers.length, rows.size(), "Python FRUIT_TIERS count");
        for (int i = 0; i < tiers.length; i++) {
            assertEquals(tiers[i].tier, (int) rows.get(i)[0], "python tier index");
            assertEquals(tiers[i].radius, (float) rows.get(i)[1], 1e-5f, "python radius tier " + tiers[i].tier);
            assertEquals(tiers[i].mergeScore, (int) rows.get(i)[2], "python score tier " + tiers[i].tier);
        }
    }
}
