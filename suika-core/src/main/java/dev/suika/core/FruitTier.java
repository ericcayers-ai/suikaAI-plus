package dev.suika.core;

/**
 * The 11-tier fruit ladder from Cherry (1) to Watermelon (11).
 * Radii and scores are community-measured starting values; calibrate against reference.
 */
public enum FruitTier {

    // Radii are the community-measured baseline scaled up 10% ("a little bit bigger")
    // — still well within the jar's mouth/body clearance (see JarShape/Jar3DPhysics).
    CHERRY     (1,  0.55f,   0),
    STRAWBERRY (2,  0.77f,   1),
    GRAPE      (3,  0.99f,   3),
    DEKOPON    (4,  1.27f,   6),
    PERSIMMON  (5,  1.54f,  10),
    APPLE      (6,  1.82f,  15),
    PEAR       (7,  2.09f,  21),
    PEACH      (8,  2.37f,  28),
    PINEAPPLE  (9,  2.70f,  36),
    MELON      (10, 3.08f,  45),
    WATERMELON (11, 3.52f,  55);

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
