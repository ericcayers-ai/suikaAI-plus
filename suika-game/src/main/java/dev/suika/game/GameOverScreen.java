package dev.suika.game;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.viewport.FitViewport;

/**
 * Game-over screen — shows the final score with Play Again / Main Menu buttons.
 */
public final class GameOverScreen extends ScreenAdapter {

    private static final float VW = SuikaScreen.VW;
    private static final float VH = SuikaScreen.VH;

    private final SuikaGame         game;
    private final long              finalScore;
    private final SuikaScreen.Mode  prevMode;
    private final OrthographicCamera camera;
    private final FitViewport        viewport;
    private final GlyphLayout        glCache = new GlyphLayout();

    private final Rectangle playAgainBtn = new Rectangle(160f, 440f, 170f, 64f);
    private final Rectangle menuBtn      = new Rectangle(390f, 440f, 170f, 64f);

    public GameOverScreen(SuikaGame game, long score, SuikaScreen.Mode prevMode) {
        this.game       = game;
        this.finalScore = score;
        this.prevMode   = prevMode;
        camera   = new OrthographicCamera();
        viewport = new FitViewport(VW, VH, camera);
        camera.position.set(VW / 2f, VH / 2f, 0f);
        camera.update();
    }

    @Override
    public void show() {
        Gdx.input.setInputProcessor(new InputAdapter() {
            @Override
            public boolean touchDown(int sx, int sy, int pointer, int button) {
                Vector3 v = camera.unproject(new Vector3(sx, sy, 0));
                if (playAgainBtn.contains(v.x, v.y)) {
                    game.setScreen(new SuikaScreen(game, prevMode));
                } else if (menuBtn.contains(v.x, v.y)) {
                    game.setScreen(new MainMenuScreen(game));
                }
                return true;
            }
        });
    }

    @Override
    public void render(float delta) {
        Gdx.gl.glClearColor(0.08f, 0.08f, 0.14f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        viewport.apply();
        game.shapes.setProjectionMatrix(camera.combined);
        game.batch.setProjectionMatrix(camera.combined);

        float cx = VW / 2f;

        game.batch.begin();

        // "GAME OVER"
        game.bigFont.setColor(1f, 0.20f, 0.10f, 1f);
        glCache.setText(game.bigFont, "GAME OVER");
        game.bigFont.draw(game.batch, "GAME OVER",
                cx - glCache.width / 2f, VH * 0.730f);

        // Final score
        String scoreStr = "Final Score:  " + finalScore;
        game.bigFont.setColor(1f, 1f, 1f, 1f);
        glCache.setText(game.bigFont, scoreStr);
        game.bigFont.draw(game.batch, scoreStr,
                cx - glCache.width / 2f, VH * 0.620f);

        game.batch.end();

        drawButton(playAgainBtn, "PLAY AGAIN", 0.18f, 0.64f, 0.24f);
        drawButton(menuBtn,      "MAIN MENU",  0.24f, 0.34f, 0.70f);
    }

    private void drawButton(Rectangle rect, String label, float r, float g, float b) {
        game.shapes.begin(ShapeRenderer.ShapeType.Filled);
        game.shapes.setColor(r * 0.38f, g * 0.38f, b * 0.38f, 1f);
        game.shapes.rect(rect.x + 3f, rect.y - 4f, rect.width, rect.height);
        game.shapes.setColor(r, g, b, 1f);
        game.shapes.rect(rect.x, rect.y, rect.width, rect.height);
        game.shapes.end();

        game.batch.begin();
        game.font.setColor(1f, 1f, 1f, 1f);
        glCache.setText(game.font, label);
        game.font.draw(game.batch, label,
                rect.x + rect.width  / 2f - glCache.width  / 2f,
                rect.y + rect.height / 2f + glCache.height / 2f);
        game.batch.end();
    }

    @Override
    public void resize(int width, int height) {
        viewport.update(width, height);
    }

    @Override
    public void hide() {
        Gdx.input.setInputProcessor(null);
    }
}
