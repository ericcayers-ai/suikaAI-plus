package dev.suika.game;

import dev.suika.core.GameState;

/**
 * Rendering contract for the game view.
 *
 * <p>Implementations live in the LibGDX layer ({@code suika-game}) and are
 * wired up at application startup. The headless core never depends on this interface —
 * it flows the other way: the renderer pulls state from {@link GameState}.
 *
 * @param alpha  interpolation factor [0,1] between the previous and current physics step
 */
@FunctionalInterface
public interface GameRenderer {
    void render(GameState state, double alpha);
}
