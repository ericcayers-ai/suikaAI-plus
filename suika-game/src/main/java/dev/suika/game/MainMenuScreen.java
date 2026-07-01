package dev.suika.game;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.viewport.FitViewport;
import dev.suika.core.FruitTier;

/**
 * Title screen: Play, Watch AI, Settings, Quit — with a gentle ambient backdrop of
 * rising translucent fruit so the menu feels alive.
 */
public final class MainMenuScreen extends ScreenAdapter {

    private final SuikaGame          game;
    private final OrthographicCamera camera = new OrthographicCamera();
    private final FitViewport        viewport;
    private final Vector3            touch = new Vector3();

    private static final float CX = Theme.VW / 2f;
    private static final com.badlogic.gdx.graphics.Color RT_LAB_VIOLET =
            new com.badlogic.gdx.graphics.Color(0.55f, 0.35f, 0.85f, 1f);

    private final Rectangle playBtn     = new Rectangle(CX - 170, 660, 340, 78);
    private final Rectangle watchBtn    = new Rectangle(CX - 170, 566, 340, 78);
    private final Rectangle settingsBtn = new Rectangle(CX - 170, 472, 340, 78);
    private final Rectangle quitBtn     = new Rectangle(CX - 170, 378, 340, 78);
    // Smaller and visually distinct (violet) — this launches a genuinely experimental
    // feature (raw Vulkan hardware ray tracing in a separate window/GPU context, not
    // the game's own OpenGL rendering) that may not work on every GPU/driver.
    private final Rectangle rtLabBtn    = new Rectangle(CX - 130, 296, 260, 56);

    private float time = 0f;
    private float mx, my;
    /** Seconds left to show the "enable Experimental mode" hint under the RT button. */
    private float rtHintTimer = 0f;

    // ambient floating fruit (x, baseY, speed, tier)
    private final float[][] motes = new float[9][];

    public MainMenuScreen(SuikaGame game) {
        this.game = game;
        viewport = new FitViewport(Theme.VW, Theme.VH, camera);
        camera.position.set(CX, Theme.VH / 2f, 0f);
        camera.update();
        java.util.Random r = new java.util.Random(7);
        for (int i = 0; i < motes.length; i++) {
            motes[i] = new float[]{
                40 + r.nextFloat() * (Theme.VW - 80),
                r.nextFloat() * Theme.VH,
                14 + r.nextFloat() * 26,
                r.nextInt(FruitTier.values().length)
            };
        }
    }

    @Override
    public void show() {
        Gdx.input.setInputProcessor(new InputAdapter() {
            @Override public boolean touchDown(int sx, int sy, int pointer, int button) {
                camera.unproject(touch.set(sx, sy, 0), viewport.getScreenX(), viewport.getScreenY(), viewport.getScreenWidth(), viewport.getScreenHeight());
                if (playBtn.contains(touch.x, touch.y))
                    game.setScreen(new SuikaScreen(game, SuikaScreen.Mode.HUMAN));
                else if (watchBtn.contains(touch.x, touch.y))
                    game.setScreen(new AiPlaygroundScreen(game));
                else if (settingsBtn.contains(touch.x, touch.y))
                    game.setScreen(new SettingsScreen(game, MainMenuScreen::new));
                else if (quitBtn.contains(touch.x, touch.y))
                    Gdx.app.exit();
                else if (rtLabBtn.contains(touch.x, touch.y)) {
                    // Gated behind the Experimental settings toggle — the ray-traced
                    // game (and its 3D-physics option) only exists in experimental mode.
                    if (game.settings.experimentalMode)
                        dev.suika.game.rtlab.RtLabLauncher.launch(game.settings.rt3dPhysics);
                    else
                        rtHintTimer = 3.5f;
                }
                return true;
            }
            @Override public boolean mouseMoved(int sx, int sy) {
                camera.unproject(touch.set(sx, sy, 0), viewport.getScreenX(), viewport.getScreenY(), viewport.getScreenWidth(), viewport.getScreenHeight());
                mx = touch.x; my = touch.y;
                return false;
            }
        });
    }

    @Override
    public void render(float delta) {
        time += delta;
        Gdx.gl.glClearColor(0.05f, 0.06f, 0.10f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_BLEND);

        viewport.apply();
        game.shapes.setProjectionMatrix(camera.combined);
        game.batch.setProjectionMatrix(camera.combined);

        ShapeRenderer s = game.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);

        // backdrop gradient
        s.rect(0, 0, Theme.VW, Theme.VH,
                Theme.BG_BOTTOM, Theme.BG_BOTTOM, Theme.BG_TOP, Theme.BG_TOP);

        // ambient rising fruit
        for (float[] m : motes) {
            float y = (m[1] + time * m[2]) % (Theme.VH + 120) - 60;
            FruitTier t = FruitTier.values()[(int) m[3]];
            var c = FruitColors.of(t);
            float rr = 16 + m[3] * 6;
            s.setColor(c.r, c.g, c.b, 0.07f);
            s.circle(m[0], y, rr, 24);
        }

        // buttons
        Ui.button(s, playBtn,     Theme.ACCENT_2,    playBtn.contains(mx, my),     true);
        Ui.button(s, watchBtn,    Theme.ACCENT_BLUE, watchBtn.contains(mx, my),    true);
        Ui.button(s, settingsBtn, Theme.PANEL_EDGE,  settingsBtn.contains(mx, my), true);
        Ui.button(s, quitBtn,     Theme.ACCENT,      quitBtn.contains(mx, my),     true);
        // Dimmed until Experimental mode is enabled in Settings.
        if (game.settings.experimentalMode) {
            Ui.button(s, rtLabBtn, RT_LAB_VIOLET, rtLabBtn.contains(mx, my), true);
        } else {
            s.setColor(RT_LAB_VIOLET.r * 0.45f, RT_LAB_VIOLET.g * 0.45f, RT_LAB_VIOLET.b * 0.45f, 0.6f);
            Ui.fillRoundRect(s, rtLabBtn.x, rtLabBtn.y, rtLabBtn.width, rtLabBtn.height, 12f);
        }

        s.end();

        game.batch.begin();
        // Title
        Ui.textCenter(game.batch, game.fontHuge, "SUIKA", CX, 1010, Theme.GOLD);
        Ui.textCenter(game.batch, game.fontHuge, "AI SANDBOX", CX, 920, Theme.TEXT);
        Ui.textCenter(game.batch, game.font,
                "A faithful merge-puzzle clone fused with an AI laboratory",
                CX, 838, Theme.TEXT_DIM);

        // Button labels
        Ui.textCenter(game.batch, game.fontMed, "PLAY",     CX, playBtn.y + 39,     Theme.TEXT);
        Ui.textCenter(game.batch, game.fontMed, "WATCH AI", CX, watchBtn.y + 39,    Theme.TEXT);
        Ui.textCenter(game.batch, game.fontMed, "SETTINGS", CX, settingsBtn.y + 39, Theme.TEXT);
        Ui.textCenter(game.batch, game.fontMed, "QUIT",     CX, quitBtn.y + 39,     Theme.TEXT);
        if (game.settings.experimentalMode) {
            String mode = game.settings.rt3dPhysics ? "3D" : "2D";
            Ui.textCenter(game.batch, game.fontSmall, "RT LAB · " + mode + " (experimental)",
                    CX, rtLabBtn.y + 30, Theme.TEXT);
        } else {
            Ui.textCenter(game.batch, game.fontSmall, "RT LAB (experimental)", CX, rtLabBtn.y + 30, Theme.TEXT_DIM);
        }
        if (rtHintTimer > 0f) {
            rtHintTimer -= delta;
            Ui.textCenter(game.batch, game.fontSmall, "Enable Experimental mode in Settings first",
                    CX, rtLabBtn.y - 12, Theme.GOLD);
        }

        // Footer
        Ui.textCenter(game.batch, game.fontSmall,
                "Click / drag to aim · ESC pauses · R restarts", CX, 250, Theme.TEXT_FAINT);
        Ui.text(game.batch, game.fontSmall, "v" + Theme.VERSION, 14, 30, Theme.TEXT_FAINT);
        Ui.textRight(game.batch, game.fontSmall,
                AiTechnique.values().length + " AI techniques · " + game.settings.fpsLabel(),
                Theme.VW - 14, 30, Theme.TEXT_FAINT);
        game.batch.end();
    }

    @Override public void resize(int width, int height) { viewport.update(width, height); }
    @Override public void hide() { Gdx.input.setInputProcessor(null); }
}
