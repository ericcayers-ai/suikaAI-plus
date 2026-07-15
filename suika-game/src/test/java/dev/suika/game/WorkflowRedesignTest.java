package dev.suika.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Layout / catalog / experiment IO for workflow-redesign. */
class WorkflowRedesignTest {

    @Test
    void techniqueCatalogFiltersAndSearches() {
        TechniqueCatalog cat = new TechniqueCatalog();
        cat.filter = TechniqueCatalog.Filter.ENSEMBLES;
        cat.ensemblesExpanded = true;
        assertTrue(cat.buildRows().stream().anyMatch(r -> r instanceof TechniqueCatalog.TechRow t && t.technique().isEnsemble()));
        cat.filter = TechniqueCatalog.Filter.ALL;
        cat.query = "mcts";
        assertTrue(cat.buildRows().stream().anyMatch(r -> r instanceof TechniqueCatalog.TechRow t
                && t.technique().id.contains("mcts")));
        cat.cycleMode(+1);
        assertEquals(TechniqueCatalog.Mode.RESEARCHER, cat.mode);
    }

    @Test
    void experimentIoRoundTrip() {
        PlaygroundConfig cfg = new PlaygroundConfig();
        cfg.selectDefaultsFor(AiTechnique.MCTS);
        cfg.rollouts = 150;
        cfg.speedIndex = 3;
        String text = ExperimentIO.exportText(cfg);
        PlaygroundConfig other = new PlaygroundConfig();
        assertNull(ExperimentIO.importText(other, text));
        assertEquals(AiTechnique.MCTS, other.technique);
        assertEquals(150, other.rollouts);
        assertEquals(3, other.speedIndex);
    }

    @Test
    void boardGridPlacementsCoverRequestedCount() {
        float[] region = {0, 0, 800, 600};
        float[][] p = ControlCenterBoardGrid.placements(region, 4);
        assertEquals(4, p.length);
        assertTrue(p[0][2] > 0);
    }

    @Test
    void modelSlotStatusPrefersOnnxLabelWhenPresentIsFalse() {
        // Pure status formatting: empty SlotInfo → empty status without touching disk.
        var st = ControlCenterModelSlots.status("no-such-tech-zzzz", 1, ModelSlots.SlotInfo.EMPTY);
        assertFalse(st.present());
        assertEquals("empty", st.detailLine());
    }

    @Test
    void muzeroIsMarkedDemonstration() {
        assertTrue(AiTechnique.MUZERO.isDemonstrationSurrogate());
        assertTrue(AiTechnique.MUZERO.display.toLowerCase().contains("demo")
                || AiTechnique.MUZERO.blurb.toUpperCase().contains("DEMONSTRATION"));
        assertFalse(AiTechnique.MCTS.isDemonstrationSurrogate());
    }
}
