package dev.suika.env;

import dev.suika.core.GameCore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RewardStudioModelTest {

    @Test
    void exportImportRoundTrip() {
        RewardStudioModel m = new RewardStudioModel();
        m.setWeight("score_delta", 2.5);
        m.setWeight("survival", 0.02);
        assertNull(m.validate());
        String blob = m.exportText();
        RewardStudioModel n = new RewardStudioModel();
        assertNull(n.importText(blob));
        assertEquals(2.5, n.weight("score_delta"), 1e-6);
        assertEquals(0.02, n.weight("survival"), 1e-6);
    }

    @Test
    void rescoringUsesComposableReward() {
        RewardStudioModel m = new RewardStudioModel();
        GameCore core = new GameCore(1L);
        var step = core.dropAndSettle(5.0);
        var breakdown = m.score(step);
        assertFalse(Double.isNaN(breakdown.total()));
        assertFalse(breakdown.terms().isEmpty());
    }
}
