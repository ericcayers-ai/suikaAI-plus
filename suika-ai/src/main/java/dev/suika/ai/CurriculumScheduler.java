package dev.suika.ai;

import dev.suika.core.FruitTier;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Curriculum scheduler — gradually increases task difficulty (ROADMAP §IV.9).
 *
 * <p>Stages of the fruit-ladder curriculum:
 * <ol>
 *   <li>Tiers 1–2 only: trivially small board, easy merges.</li>
 *   <li>Tiers 1–3: introduces grape; cascades start appearing.</li>
 *   <li>Tiers 1–4: adds dekopon; density begins to matter.</li>
 *   <li>Tiers 1–5: full droppable set; the real game.</li>
 * </ol>
 *
 * <p>Stage promotion is triggered when a competence threshold (mean score over a
 * window of episodes) is reached. This follows the self-paced principle:
 * the curriculum adapts to the agent's current ability.
 */
public final class CurriculumScheduler {

    public record Stage(
            int             stageIndex,
            Set<FruitTier>  droppableTiers,
            double          promotionThreshold
    ) {}

    private static final List<Stage> STAGES = List.of(
            new Stage(0, EnumSet.of(FruitTier.CHERRY, FruitTier.STRAWBERRY),          5.0),
            new Stage(1, EnumSet.of(FruitTier.CHERRY, FruitTier.STRAWBERRY,
                                    FruitTier.GRAPE),                                  15.0),
            new Stage(2, EnumSet.of(FruitTier.CHERRY, FruitTier.STRAWBERRY,
                                    FruitTier.GRAPE,   FruitTier.DEKOPON),             40.0),
            new Stage(3, EnumSet.of(FruitTier.CHERRY, FruitTier.STRAWBERRY,
                                    FruitTier.GRAPE,   FruitTier.DEKOPON,
                                    FruitTier.PERSIMMON),                            Double.MAX_VALUE)
    );

    private int    currentStageIndex = 0;
    private double meanScoreWindow   = 0.0;
    private int    windowCount       = 0;
    private final int windowSize;

    public CurriculumScheduler(int windowSize) { this.windowSize = windowSize; }

    /** Report the score of a completed episode. Triggers stage promotion if ready. */
    public void recordEpisode(long score) {
        meanScoreWindow = (meanScoreWindow * windowCount + score) / (windowCount + 1);
        windowCount     = Math.min(windowCount + 1, windowSize);

        Stage s = currentStage();
        if (currentStageIndex < STAGES.size() - 1
                && meanScoreWindow >= s.promotionThreshold()) {
            currentStageIndex++;
            meanScoreWindow = 0;
            windowCount     = 0;
        }
    }

    public Stage  currentStage()       { return STAGES.get(currentStageIndex); }
    public int    currentStageIndex()  { return currentStageIndex; }
    public double meanScore()          { return meanScoreWindow; }

    /** Force-reset to a given stage (for evaluation or ablation). */
    public void setStage(int idx) {
        currentStageIndex = Math.max(0, Math.min(idx, STAGES.size() - 1));
        meanScoreWindow   = 0;
        windowCount       = 0;
    }

    public static int totalStages() { return STAGES.size(); }
}
