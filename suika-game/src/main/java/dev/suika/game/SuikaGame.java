package dev.suika.game;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * LibGDX {@link Game} — owns shared rendering resources and drives screen transitions:
 * {@link MainMenuScreen} → {@link SettingsScreen} / {@link SuikaScreen} → {@link GameOverScreen}.
 *
 * <p>Fonts are rasterised on the fly from a bundled Apache-2.0 TrueType face via
 * FreeType, giving crisp anti-aliased text at every size (no more pixelated bitmaps).
 * Created by {@code DesktopLauncher} in {@code suika-app}; never constructed headless.
 */
public class SuikaGame extends Game {

    /** Shared across all screens; never disposed by a Screen. */
    public SpriteBatch   batch;
    public ShapeRenderer shapes;

    // Crisp FreeType fonts at a range of sizes (virtual-pixel space).
    public BitmapFont fontSmall; // ~18  hints, captions
    public BitmapFont font;      // ~23  body, buttons
    public BitmapFont fontMed;   // ~32  section headers (bold)
    public BitmapFont fontBig;   // ~52  HUD score (bold)
    public BitmapFont fontHuge;  // ~84  titles, GAME OVER (bold)

    /** Session-wide, mutable configuration edited in the settings screen. */
    public final GameSettings settings = new GameSettings();

    /** Shared merge-sparkle particle system. */
    public final Particles particles = new Particles();

    @Override
    public void create() {
        batch  = new SpriteBatch();
        shapes = new ShapeRenderer();

        FreeTypeFontGenerator regular =
                new FreeTypeFontGenerator(Gdx.files.internal("fonts/DroidSans.ttf"));
        FreeTypeFontGenerator bold =
                new FreeTypeFontGenerator(Gdx.files.internal("fonts/DroidSans-Bold.ttf"));

        fontSmall = gen(regular, 18);
        font      = gen(regular, 23);
        fontMed   = gen(bold,    32);
        fontBig   = gen(bold,    52);
        fontHuge  = gen(bold,    84);

        regular.dispose();
        bold.dispose();

        settings.applyDisplay();
        setScreen(new MainMenuScreen(this));
    }

    private BitmapFont gen(FreeTypeFontGenerator g, int size) {
        FreeTypeFontParameter p = new FreeTypeFontParameter();
        p.size       = size;
        p.color      = Color.WHITE;
        // Default ASCII plus the typographic glyphs the UI uses (em-dash, ellipsis, dot, arrows).
        p.characters = FreeTypeFontGenerator.DEFAULT_CHARS + "—…·•≈→←×÷";
        p.genMipMaps = true;
        p.minFilter  = Texture.TextureFilter.MipMapLinearLinear;
        p.magFilter  = Texture.TextureFilter.Linear;
        BitmapFont f = g.generateFont(p);
        f.setUseIntegerPositions(false);
        return f;
    }

    @Override
    public void dispose() {
        super.dispose(); // disposes the current screen
        batch.dispose();
        shapes.dispose();
        fontSmall.dispose();
        font.dispose();
        fontMed.dispose();
        fontBig.dispose();
        fontHuge.dispose();
    }
}
