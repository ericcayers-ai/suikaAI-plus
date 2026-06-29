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
import dev.suika.core.Fruit;
import dev.suika.core.FruitTier;
import dev.suika.core.GameState;

/**
 * Game-over summary: final score plus honest end-of-run stats (highest fruit reached,
 * fruit on board, seed for reproducibility) with Play Again / Main Menu actions.
 */
public final class GameOverScreen extends ScreenAdapter {

    private final SuikaGame game;
    private final long finalScore;
    private final SuikaScreen.Mode mode;
    private final long seed;
    private final FruitTier highest;
    private final int fruitCount;

    private final OrthographicCamera camera = new OrthographicCamera();
    private final FitViewport viewport;
    private final Vector3 touch = new Vector3();
    private float mx, my;

    private final Rectangle againBtn = new Rectangle(Theme.VW/2f - 320, 380, 300, 78);
    private final Rectangle menuBtn  = new Rectangle(Theme.VW/2f +  20, 380, 300, 78);

    public GameOverScreen(SuikaGame game, long score, GameState finalState,
                          SuikaScreen.Mode mode, long seed) {
        this.game = game;
        this.finalScore = score;
        this.mode = mode;
        this.seed = seed;
        FruitTier hi = FruitTier.CHERRY;
        for (Fruit f : finalState.fruits()) if (f.tier().tier > hi.tier) hi = f.tier();
        this.highest = hi;
        this.fruitCount = finalState.fruits().size();
        viewport = new FitViewport(Theme.VW, Theme.VH, camera);
        camera.position.set(Theme.VW/2f, Theme.VH/2f, 0f);
        camera.update();
    }

    @Override
    public void show() {
        Gdx.input.setInputProcessor(new InputAdapter() {
            @Override public boolean touchDown(int sx, int sy, int p, int b) {
                camera.unproject(touch.set(sx, sy, 0));
                if (againBtn.contains(touch.x, touch.y)) game.setScreen(new SuikaScreen(game, mode));
                else if (menuBtn.contains(touch.x, touch.y)) game.setScreen(new MainMenuScreen(game));
                return true;
            }
            @Override public boolean mouseMoved(int sx, int sy) {
                camera.unproject(touch.set(sx, sy, 0)); mx = touch.x; my = touch.y; return false;
            }
        });
    }

    @Override
    public void render(float delta) {
        Gdx.gl.glClearColor(0.05f, 0.06f, 0.10f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        viewport.apply();
        game.shapes.setProjectionMatrix(camera.combined);
        game.batch.setProjectionMatrix(camera.combined);

        float cx = Theme.VW / 2f;
        ShapeRenderer s = game.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);
        s.rect(0, 0, Theme.VW, Theme.VH, Theme.BG_BOTTOM, Theme.BG_BOTTOM, Theme.BG_TOP, Theme.BG_TOP);

        // stat card
        Ui.panel(s, cx - 300, 560, 600, 300, 18, Theme.PANEL, Theme.PANEL_EDGE);

        // highest-fruit chip
        var c = FruitColors.of(highest);
        s.setColor(c.r, c.g, c.b, 1f);
        s.circle(cx, 640, 46, 40);

        Ui.button(s, againBtn, Theme.ACCENT_2, againBtn.contains(mx,my), true);
        Ui.button(s, menuBtn,  Theme.ACCENT,   menuBtn.contains(mx,my),  true);
        s.end();

        game.batch.begin();
        Ui.textCenter(game.batch, game.fontHuge, "GAME OVER", cx, 1010, Theme.ACCENT);
        Ui.textCenter(game.batch, game.fontSmall,
                mode == SuikaScreen.Mode.HUMAN ? "Your run" : "AI run · " + WatchAgents.get(game.settings.agentIndex).name(),
                cx, 930, Theme.TEXT_DIM);

        Ui.textCenter(game.batch, game.fontSmall, "FINAL SCORE", cx, 828, Theme.TEXT_DIM);
        Ui.textCenter(game.batch, game.fontBig, Long.toString(finalScore), cx, 792, Theme.GOLD);

        Ui.textCenter(game.batch, game.fontSmall, "highest fruit", cx, 588, Theme.TEXT_DIM);
        Ui.textCenter(game.batch, game.fontSmall, "tier " + highest.tier, cx, 622, Theme.TEXT);

        Ui.text(game.batch, game.fontSmall, "Fruit on board", cx - 270, 540, Theme.TEXT_DIM);
        Ui.textRight(game.batch, game.fontSmall, Integer.toString(fruitCount), cx - 30, 540, Theme.TEXT);
        Ui.text(game.batch, game.fontSmall, "Seed", cx + 20, 540, Theme.TEXT_DIM);
        Ui.textRight(game.batch, game.fontSmall, Long.toString(seed), cx + 270, 540, Theme.TEXT);

        Ui.textCenter(game.batch, game.fontMed, "PLAY AGAIN", againBtn.x + againBtn.width/2f, againBtn.y + 39, Theme.TEXT);
        Ui.textCenter(game.batch, game.fontMed, "MAIN MENU",  menuBtn.x + menuBtn.width/2f,   menuBtn.y + 39, Theme.TEXT);
        game.batch.end();
    }

    @Override public void resize(int width, int height) { viewport.update(width, height); }
    @Override public void hide() { Gdx.input.setInputProcessor(null); }
}
