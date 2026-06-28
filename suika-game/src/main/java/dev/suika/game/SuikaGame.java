package dev.suika.game;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * LibGDX {@link Game} (ApplicationListener) — owns shared rendering resources and
 * drives screen transitions: {@link MainMenuScreen} → {@link SuikaScreen} → {@link GameOverScreen}.
 *
 * <p>Created by {@code DesktopLauncher} in the {@code suika-app} module. Never constructed
 * on the headless path.
 */
public class SuikaGame extends Game {

    /** Shared across all screens; never disposed by a Screen. */
    public SpriteBatch   batch;
    public ShapeRenderer shapes;
    /** Body text (~30 px in virtual space). */
    public BitmapFont    font;
    /** Title / score text (~48 px in virtual space). */
    public BitmapFont    bigFont;

    @Override
    public void create() {
        batch  = new SpriteBatch();
        shapes = new ShapeRenderer();

        font = new BitmapFont();
        font.getData().setScale(2.0f);
        font.setUseIntegerPositions(false);

        bigFont = new BitmapFont();
        bigFont.getData().setScale(3.2f);
        bigFont.setUseIntegerPositions(false);

        setScreen(new MainMenuScreen(this));
    }

    @Override
    public void dispose() {
        super.dispose(); // disposes the current screen
        batch.dispose();
        shapes.dispose();
        font.dispose();
        bigFont.dispose();
    }
}
