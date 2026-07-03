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

    /** Floating "+N" score labels spawned on each merge. */
    public final ScorePopManager scorePops = new ScorePopManager();

    @Override
    public void create() {
        batch  = new SpriteBatch();
        shapes = new ShapeRenderer();

        SettingsPersistence.load(settings);
        buildFonts();
        // Skip under the capture harness — DesktopLauncher deliberately sizes that
        // window to match the virtual canvas exactly (no letterbox bars in
        // screenshots; -Dsuika.capture.land picks portrait vs landscape), and a
        // persisted resolution/fullscreen preference resizing over that broke
        // captures silently (confirmed via the harness itself: every screenshot
        // came back portrait even under -Dsuika.capture.land=true).
        if (System.getProperty("suika.capture.dir") == null) settings.applyWindowMode();

        settings.applyDisplay();
        settings.applyPhysics();
        GpuProbe.ensureStarted(); // background CUDA probe; resolves well before AI Playground is opened
        setScreen(new MainMenuScreen(this));
    }

    /** (Re)generates every font at {@link GameSettings#uiScale()} — called once at
     *  startup and again whenever the player changes the UI Scale setting live. */
    private void buildFonts() {
        FreeTypeFontGenerator regular =
                new FreeTypeFontGenerator(Gdx.files.internal("fonts/DroidSans.ttf"));
        FreeTypeFontGenerator bold =
                new FreeTypeFontGenerator(Gdx.files.internal("fonts/DroidSans-Bold.ttf"));

        float sc = settings.uiScale();
        fontSmall = gen(regular, Math.round(18 * sc));
        font      = gen(regular, Math.round(23 * sc));
        fontMed   = gen(bold,    Math.round(32 * sc));
        fontBig   = gen(bold,    Math.round(52 * sc));
        fontHuge  = gen(bold,    Math.round(84 * sc));

        regular.dispose();
        bold.dispose();
    }

    /** Test/QA + Settings-screen hook: throws away the current fonts and rebuilds
     *  them at the (just-changed) UI scale. Screens hold onto {@code game.font} etc.
     *  directly rather than caching a reference, so nothing needs to be re-wired. */
    public void regenerateFonts() {
        fontSmall.dispose(); font.dispose(); fontMed.dispose(); fontBig.dispose(); fontHuge.dispose();
        buildFonts();
    }

    private BitmapFont gen(FreeTypeFontGenerator g, int size) {
        FreeTypeFontParameter p = new FreeTypeFontParameter();
        p.size       = size;
        p.color      = Color.WHITE;
        // Default ASCII plus the typographic glyphs the UI uses (em-dash, minus sign,
        // ellipsis, dot, a few Greek letters ML stat labels favour — σ/λ/μ/α/β/θ).
        // The minus sign (U+2212) is distinct from the ASCII hyphen and is used by the
        // +/− cyclers. NOTE: deliberately no arrow glyphs (→←) here — DroidSans.ttf
        // has no glyph for them despite FreeType silently accepting the request, which
        // rendered as a tofu box wherever they were used (see MainMenuScreen's old
        // "AI PLAYS →" label, now plain ASCII "->").
        p.characters = FreeTypeFontGenerator.DEFAULT_CHARS + "—−…·•≈×÷σ±λμαβθ";
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
