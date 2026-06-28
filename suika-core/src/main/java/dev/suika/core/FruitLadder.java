package dev.suika.core;

import java.util.List;

/**
 * Data-driven fruit progression for modding and custom benchmarks (ROADMAP §XII).
 *
 * <p>The default ladder mirrors the {@link FruitTier} enum.
 * Custom ladders can have more or fewer tiers, different radii, or alternate
 * merge scores — each becomes a distinct curriculum/benchmark automatically.
 *
 * @param name        human-readable name for this variant
 * @param description brief description shown in the UI
 * @param entries     tiers in ascending order; index 0 is the smallest fruit
 */
public record FruitLadder(
        String            name,
        String            description,
        List<Entry>       entries
) {

    /**
     * One rung in the fruit ladder.
     *
     * @param displayName  shown in UI and replay logs
     * @param radius       collision radius in game units
     * @param mergeScore   points awarded when two of the previous tier produce this one
     * @param droppable    true if the player can receive this tier at the top
     */
    public record Entry(
            String  displayName,
            float   radius,
            int     mergeScore,
            boolean droppable
    ) {}

    /** Number of tiers in this ladder. */
    public int size() { return entries.size(); }

    /** Retrieve an entry by 0-based index. */
    public Entry get(int index) { return entries.get(index); }

    /** True if the entry at {@code index} can be handed to the player. */
    public boolean isDroppable(int index) { return entries.get(index).droppable(); }

    /**
     * The default 11-tier Suika ladder derived from {@link FruitTier}.
     * Radii and scores match the calibrated values in FruitTier.
     */
    public static FruitLadder standard() {
        List<Entry> entries = List.of(
                new Entry("Cherry",     0.50f,  0,  true),
                new Entry("Strawberry", 0.70f,  1,  true),
                new Entry("Grape",      0.90f,  3,  true),
                new Entry("Dekopon",    1.15f,  6,  true),
                new Entry("Persimmon",  1.40f, 10,  true),
                new Entry("Apple",      1.65f, 15, false),
                new Entry("Pear",       1.90f, 21, false),
                new Entry("Peach",      2.15f, 28, false),
                new Entry("Pineapple",  2.45f, 36, false),
                new Entry("Melon",      2.80f, 45, false),
                new Entry("Watermelon", 3.20f, 55, false)
        );
        return new FruitLadder("Standard", "The canonical 11-tier Suika Game ladder.", entries);
    }

    /**
     * A compact 6-tier ladder useful for faster training experiments.
     */
    public static FruitLadder compact() {
        List<Entry> entries = List.of(
                new Entry("Tiny",   0.50f,  0, true),
                new Entry("Small",  0.80f,  2, true),
                new Entry("Medium", 1.20f,  6, true),
                new Entry("Large",  1.70f, 12, false),
                new Entry("Giant",  2.30f, 20, false),
                new Entry("Huge",   3.00f, 30, false)
        );
        return new FruitLadder("Compact", "6-tier ladder for rapid training iteration.", entries);
    }
}
