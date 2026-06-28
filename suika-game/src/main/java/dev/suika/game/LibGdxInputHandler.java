package dev.suika.game;

import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.math.Vector3;
import dev.suika.core.PhysicsConfig;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Translates LibGDX mouse events into game-space drop actions and hover x-position.
 *
 * <p>Implements both {@link InputHandler} (polled each game-loop tick) and
 * {@link InputAdapter} (registered with {@code Gdx.input}).
 *
 * <p>Virtual coordinate system: {@link #SCALE} virtual pixels per game unit,
 * with {@link #OFFSET_X}/{@link #OFFSET_Y} defining the game-field origin.
 */
public final class LibGdxInputHandler extends InputAdapter implements InputHandler {

    /** Virtual pixels per game unit (matches LibGdxGameRenderer). */
    static final float SCALE    = 50f;
    /** Virtual x of game coordinate x = 0 (left container wall). */
    static final float OFFSET_X = 50f;
    /** Virtual y of game coordinate y = 0 (floor). */
    static final float OFFSET_Y = 80f;

    private final OrthographicCamera           camera;
    private final AtomicReference<Double>      pendingDrop = new AtomicReference<>(null);
    private volatile double                    hoverX = PhysicsConfig.CONTAINER_WIDTH / 2.0;

    public LibGdxInputHandler(OrthographicCamera camera) {
        this.camera = camera;
    }

    /** Returns and clears the pending drop x in game units, or {@code null} if none queued. */
    @Override
    public Double pollDropAction() {
        return pendingDrop.getAndSet(null);
    }

    /** Latest mouse x in game coordinates, clamped to the valid drop range. */
    public double getHoverX() { return hoverX; }

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        double gx = toGameX(screenX, screenY);
        if (gx >= PhysicsConfig.DROP_X_MIN && gx <= PhysicsConfig.DROP_X_MAX) {
            pendingDrop.set(gx);
        }
        return true;
    }

    @Override
    public boolean mouseMoved(int screenX, int screenY) {
        double gx = toGameX(screenX, screenY);
        hoverX = Math.clamp(gx, PhysicsConfig.DROP_X_MIN, PhysicsConfig.DROP_X_MAX);
        return false;
    }

    private double toGameX(int screenX, int screenY) {
        Vector3 v = camera.unproject(new Vector3(screenX, screenY, 0));
        return (v.x - OFFSET_X) / SCALE;
    }
}
