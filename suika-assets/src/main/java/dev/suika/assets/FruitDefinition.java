package dev.suika.assets;

/**
 * Data-driven definition of one fruit tier, loaded from {@code fruits.json}.
 * Matches the JSON schema in {@code src/main/resources/fruits.json}.
 */
public record FruitDefinition(
        int     tier,
        String  name,
        double  radius,
        int     mergeScore,
        boolean droppable,
        double  density
) {
    /** Returns the score awarded when *two* fruits of this tier merge into the next. */
    public int scoreOnMerge() { return mergeScore; }
}
