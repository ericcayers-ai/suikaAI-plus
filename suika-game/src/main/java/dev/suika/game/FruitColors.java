package dev.suika.game;

import com.badlogic.gdx.graphics.Color;
import dev.suika.core.FruitTier;

/** Canonical per-tier colour palette. Colourblind-friendly: shape + tier-number label are also used. */
public final class FruitColors {

    private FruitColors() {}

    private static final Color[] COLOURS = {
        new Color(0.93f, 0.12f, 0.18f, 1f), // 1  Cherry      – red
        new Color(0.95f, 0.38f, 0.55f, 1f), // 2  Strawberry  – pink
        new Color(0.50f, 0.16f, 0.72f, 1f), // 3  Grape       – purple
        new Color(0.97f, 0.54f, 0.06f, 1f), // 4  Dekopon     – orange
        new Color(0.82f, 0.28f, 0.04f, 1f), // 5  Persimmon   – dark orange
        new Color(0.16f, 0.70f, 0.20f, 1f), // 6  Apple       – green
        new Color(0.58f, 0.82f, 0.16f, 1f), // 7  Pear        – yellow-green
        new Color(0.95f, 0.62f, 0.51f, 1f), // 8  Peach       – salmon
        new Color(0.96f, 0.87f, 0.08f, 1f), // 9  Pineapple   – yellow
        new Color(0.18f, 0.79f, 0.34f, 1f), // 10 Melon       – bright green
        new Color(0.05f, 0.46f, 0.13f, 1f), // 11 Watermelon  – dark green
    };

    public static Color of(FruitTier tier) {
        return COLOURS[tier.ordinal()];
    }
}
