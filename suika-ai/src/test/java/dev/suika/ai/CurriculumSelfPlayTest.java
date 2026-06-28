package dev.suika.ai;

import dev.suika.core.FruitTier;
import dev.suika.core.GameCore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CurriculumSelfPlayTest {

    @Test
    void curriculumStartsAtStageZero() {
        CurriculumScheduler cs = new CurriculumScheduler(10);
        assertEquals(0, cs.currentStageIndex());
        assertTrue(cs.currentStage().droppableTiers().contains(FruitTier.CHERRY));
        assertFalse(cs.currentStage().droppableTiers().contains(FruitTier.PERSIMMON));
    }

    @Test
    void curriculumPromotesOnHighScore() {
        CurriculumScheduler cs = new CurriculumScheduler(3);
        // Stage 0 threshold is 5.0 — flood with high scores to promote
        for (int i = 0; i < 5; i++) cs.recordEpisode(100L);
        assertTrue(cs.currentStageIndex() >= 1, "Must promote from stage 0 with high scores");
    }

    @Test
    void curriculumDoesNotExceedMaxStage() {
        CurriculumScheduler cs = new CurriculumScheduler(1);
        for (int i = 0; i < 100; i++) cs.recordEpisode(10_000L);
        assertTrue(cs.currentStageIndex() < CurriculumScheduler.totalStages(),
                "Stage index must stay within bounds");
    }

    @Test
    void racingSelfPlayProducesResult() {
        RacingSelfPlay race = new RacingSelfPlay(20, 8);
        HeuristicAgent a = new HeuristicAgent();
        RandomAgent    b = new RandomAgent();
        var result = race.race(a, b, 42L);
        assertNotNull(result.winnerId());
        assertTrue(result.scoreA() >= 0 && result.scoreB() >= 0);
    }

    @Test
    void adversarialSetterChoosesDroppableTier() {
        AdversarialSequenceSetter adv = new AdversarialSequenceSetter(
                AdversarialSequenceSetter.Strategy.GREEDY_WORST, 1L);
        GameCore core = new GameCore(2L);
        for (int i = 0; i < 5; i++) core.dropAndSettle(5.0);
        FruitTier chosen = adv.chooseNextFruit(core.getState());
        assertTrue(chosen.isDroppable(), "Adversary must always pick a droppable tier");
    }

    @Test
    void pbtRunsOneStep() {
        PopulationBasedTraining pbt = new PopulationBasedTraining(4, 7L);
        pbt.step(1000L);
        assertEquals(1, pbt.generation());
        assertNotNull(pbt.bestMember());
    }
}
