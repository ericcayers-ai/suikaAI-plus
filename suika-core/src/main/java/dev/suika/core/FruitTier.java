package dev.suika.core;

/**
 * The 11-tier fruit ladder from Cherry (1) to Watermelon (11).
 * Radii and scores are community-measured starting values; calibrate against reference.
 */
public enum FruitTier {

    CHERRY     (1,  0.50f,   0),
    STRAWBERRY (2,  0.70f,   1),
    GRAPE      (3,  0.90f,   3),
    DEKOPON    (4,  1.15f,   6),
    PERSIMMON  (5,  1.40f,  10),
    APPLE      (6,  1.65f,  15),
    PEAR       (7,  1.90f,  21),
    PEACH      (8,  2.15f,  28),
    PINEAPPLE  (9,  2.45f,  36),
    MELON      (10, 2.80f,  45),
    WATERMELON (11, 3.20f,  55);

    /** 1-based tier index. */
    public final int tier;
    /** Collision radius in game units (to calibrate against reference). */
    public final float radius;
    /** Score awarded when two of the *previous* tier merge to produce this tier. */
    public final int mergeScore;

    FruitTier(int tier, float radius, int mergeScore) {
        this.tier = tier;
        this.radius = radius;
        this.mergeScore = mergeScore;
    }

    /** Only tiers 1–5 can be handed to the player at the top. */
    public boolean isDroppable() {
        return tier <= 5;
    }

    /**
     * Returns the tier this fruit becomes when two copies merge,
     * or {@code null} if this is WATERMELON (two watermelons vanish for bonus).
     */
    public FruitTier next() {
        FruitTier[] vals = values();
        return (ordinal() < vals.length - 1) ? vals[ordinal() + 1] : null;
    }

    /** Look up a tier by its 1-based index. */
    public static FruitTier fromTier(int t) {
        for (FruitTier ft : values()) {
            if (ft.tier == t) return ft;
        }
        throw new IllegalArgumentException("Unknown tier: " + t);
    }
}
