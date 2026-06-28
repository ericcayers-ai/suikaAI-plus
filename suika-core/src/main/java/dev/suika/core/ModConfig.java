package dev.suika.core;

/**
 * Full mod descriptor: a named variant that pairs a custom fruit ladder with
 * a custom container configuration (ROADMAP §XII).
 *
 * <p>Any mod can serve as a distinct curriculum stage or community benchmark:
 * {@code ModConfig.compact()} gives a faster-training 6-tier environment;
 * {@code ModConfig.standard()} reproduces the canonical game exactly.
 *
 * @param name          unique identifier for the variant
 * @param description   short human-readable description
 * @param fruitLadder   the fruit tier progression
 * @param container     the container geometry
 * @param doubleTopBonus bonus points for merging two top-tier fruits (≥ 0)
 */
public record ModConfig(
        String          name,
        String          description,
        FruitLadder     fruitLadder,
        ContainerConfig container,
        int             doubleTopBonus
) {
    public ModConfig {
        if (doubleTopBonus < 0) throw new IllegalArgumentException("doubleTopBonus must be >= 0");
    }

    /** Standard 11-tier Suika with canonical container and 100-point watermelon bonus. */
    public static ModConfig standard() {
        return new ModConfig(
                "standard",
                "The canonical Suika Game — 11 tiers, standard container.",
                FruitLadder.standard(),
                ContainerConfig.standard(),
                PhysicsConfig.DOUBLE_WATERMELON_BONUS
        );
    }

    /** 6-tier compact variant with standard container — good for rapid iteration. */
    public static ModConfig compact() {
        return new ModConfig(
                "compact",
                "6-tier compact variant for faster training experiments.",
                FruitLadder.compact(),
                ContainerConfig.standard(),
                50
        );
    }

    /** Standard ladder in a narrower container — higher precision required. */
    public static ModConfig narrow() {
        return new ModConfig(
                "narrow",
                "Standard fruit ladder in a narrow container.",
                FruitLadder.standard(),
                ContainerConfig.narrow(),
                PhysicsConfig.DOUBLE_WATERMELON_BONUS
        );
    }
}
