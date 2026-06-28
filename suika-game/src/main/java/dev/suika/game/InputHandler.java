package dev.suika.game;

/**
 * Input contract between the platform layer and the game loop.
 *
 * <p>Returns the next drop x-coordinate when the player commits a drop, or
 * {@code null} if no input is pending this frame. This non-blocking poll keeps
 * the game loop free of I/O coupling.
 */
@FunctionalInterface
public interface InputHandler {
    /**
     * @return drop x in game units, or {@code null} if no action this frame
     */
    Double pollDropAction();
}
