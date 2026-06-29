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

    private final Rectangle playBtn     = new Rectangle(CX - 170, 660, 340, 78);
    private final Rectangle watchBtn    = new Rectangle(CX - 170, 566, 340, 78);
    private final Rectangle settingsBtn = new Rectangle(CX - 170, 472, 340, 78);
    private final Rectangle quitBtn     = new Rectangle(CX - 170, 378, 340, 78);

    private float time = 0f;
    private float mx, my;

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
                camera.unproject(touch.set(sx, sy, 0));
                if (playBtn.contains(touch.x, touch.y))
                    game.setScreen(new SuikaScreen(game, SuikaScreen.Mode.HUMAN));
                else if (watchBtn.contains(touch.x, touch.y))
                    game.setScreen(new SuikaScreen(game, SuikaScreen.Mode.AI_WATCH));
                else if (settingsBtn.contains(touch.x, touch.y))
                    game.setScreen(new SettingsScreen(game, MainMenuScreen::new));
                else if (quitBtn.contains(touch.x, touch.y))
                    Gdx.app.exit();
                return true;
            }
            @Override public boolean mouseMoved(int sx, int sy) {
                camera.unproject(touch.set(sx, sy, 0));
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

        // Footer
        Ui.textCenter(game.batch, game.fontSmall,
                "Click / drag to aim · ESC pauses · R restarts", CX, 250, Theme.TEXT_FAINT);
        Ui.text(game.batch, game.fontSmall, "v0.3.0", 14, 30, Theme.TEXT_FAINT);
        Ui.textRight(game.batch, game.fontSmall,
                WatchAgents.get(game.settings.agentIndex).name() + " · " + game.settings.fpsLabel(),
                Theme.VW - 14, 30, Theme.TEXT_FAINT);
        game.batch.end();
    }

    @Override public void resize(int width, int height) { viewport.update(width, height); }
    @Override public void hide() { Gdx.input.setInputProcessor(null); }
}
