package dev.suika.game;

import com.badlogic.gdx.graphics.Color;
import dev.suika.core.FruitTier;

/**
 * Procedural per-tier fruit colours (no external art — copyright-safe, ROADMAP §I.6).
 *
 * <p>Each tier has a base fill plus a derived darker rim and lighter top-highlight so
 * the renderer can draw glossy, depth-shaded fruit from a single source colour.
 * Colour-blind friendly: the renderer also stamps the tier number on every fruit.
 */
public final class FruitColors {

    private FruitColors() {}

    private static final Color[] COLOURS = {
        new Color(0.93f, 0.16f, 0.22f, 1f), // 1  Cherry      – red
        new Color(0.96f, 0.40f, 0.58f, 1f), // 2  Strawberry  – pink
        new Color(0.56f, 0.28f, 0.80f, 1f), // 3  Grape       – purple
        new Color(0.98f, 0.58f, 0.10f, 1f), // 4  Dekopon     – orange
        new Color(0.92f, 0.42f, 0.12f, 1f), // 5  Persimmon   – dark orange
        new Color(0.90f, 0.22f, 0.26f, 1f), // 6  Apple       – apple red
        new Color(0.74f, 0.84f, 0.30f, 1f), // 7  Pear        – yellow-green
        new Color(0.99f, 0.70f, 0.60f, 1f), // 8  Peach       – salmon
        new Color(0.98f, 0.86f, 0.22f, 1f), // 9  Pineapple   – yellow
        new Color(0.40f, 0.82f, 0.42f, 1f), // 10 Melon       – bright green
        new Color(0.16f, 0.62f, 0.30f, 1f), // 11 Watermelon  – deep green
    };

    /** Base fill colour for a tier. */
    public static Color of(FruitTier tier) {
        return COLOURS[tier.ordinal()];
    }

    /** Darker rim colour for the fruit outline / underside, written into {@code out}. */
    public static Color rim(FruitTier tier, Color out) {
        Color b = COLOURS[tier.ordinal()];
        out.set(b.r * 0.60f, b.g * 0.60f, b.b * 0.60f, 1f);
        return out;
    }

    /** Lighter top-highlight colour for the glossy sheen, written into {@code out}. */
    public static Color highlight(FruitTier tier, Color out) {
        Color b = COLOURS[tier.ordinal()];
        out.set(Math.min(1f, b.r * 1.30f + 0.14f),
                Math.min(1f, b.g * 1.30f + 0.14f),
                Math.min(1f, b.b * 1.30f + 0.14f), 1f);
        return out;
    }
}
