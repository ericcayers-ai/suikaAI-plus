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
 * Main menu screen with three buttons: Play (human), AI Watch, and Quit.
 */
public final class MainMenuScreen extends ScreenAdapter {

    private static final float VW = SuikaScreen.VW;
    private static final float VH = SuikaScreen.VH;

    private final SuikaGame          game;
    private final OrthographicCamera camera;
    private final FitViewport        viewport;
    private final GlyphLayout        glCache = new GlyphLayout();

    // Button rectangles in virtual-pixel space (x, y, w, h; y-up)
    private final Rectangle playBtn    = new Rectangle(240f, 590f, 240f, 72f);
    private final Rectangle aiBtn     = new Rectangle(240f, 498f, 240f, 72f);
    private final Rectangle quitBtn   = new Rectangle(240f, 406f, 240f, 72f);

    public MainMenuScreen(SuikaGame game) {
        this.game = game;
        camera    = new OrthographicCamera();
        viewport  = new FitViewport(VW, VH, camera);
        camera.position.set(VW / 2f, VH / 2f, 0f);
        camera.update();
    }

    @Override
    public void show() {
        Gdx.input.setInputProcessor(new InputAdapter() {
            @Override
            public boolean touchDown(int sx, int sy, int pointer, int button) {
                Vector3 v = camera.unproject(new Vector3(sx, sy, 0));
                if (playBtn.contains(v.x, v.y)) {
                    game.setScreen(new SuikaScreen(game, SuikaScreen.Mode.HUMAN));
                } else if (aiBtn.contains(v.x, v.y)) {
                    game.setScreen(new SuikaScreen(game, SuikaScreen.Mode.AI_WATCH));
                } else if (quitBtn.contains(v.x, v.y)) {
                    Gdx.app.exit();
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

        drawTitle();
        drawButton(playBtn,  "PLAY",     0.20f, 0.68f, 0.28f);
        drawButton(aiBtn,    "AI WATCH", 0.18f, 0.42f, 0.76f);
        drawButton(quitBtn,  "QUIT",     0.58f, 0.18f, 0.18f);
        drawFooter();
    }

    private void drawTitle() {
        game.batch.begin();

        game.bigFont.setColor(0.97f, 0.72f, 0.10f, 1f);
        String line1 = "SUIKA AI";
        glCache.setText(game.bigFont, line1);
        game.bigFont.draw(game.batch, line1, VW / 2f - glCache.width / 2f, VH * 0.865f);

        game.bigFont.setColor(0.70f, 0.70f, 0.82f, 1f);
        String line2 = "SANDBOX";
        glCache.setText(game.bigFont, line2);
        game.bigFont.draw(game.batch, line2, VW / 2f - glCache.width / 2f, VH * 0.800f);

        game.font.setColor(0.45f, 0.48f, 0.58f, 1f);
        String sub = "Physics-faithful clone · AI laboratory";
        glCache.setText(game.font, sub);
        game.font.draw(game.batch, sub, VW / 2f - glCache.width / 2f, VH * 0.738f);

        game.batch.end();
    }

    private void drawButton(Rectangle rect, String label, float r, float g, float b) {
        // Shadow
        game.shapes.begin(ShapeRenderer.ShapeType.Filled);
        game.shapes.setColor(r * 0.35f, g * 0.35f, b * 0.35f, 1f);
        game.shapes.rect(rect.x + 3f, rect.y - 4f, rect.width, rect.height);
        // Button face
        game.shapes.setColor(r, g, b, 1f);
        game.shapes.rect(rect.x, rect.y, rect.width, rect.height);
        game.shapes.end();

        // Label
        game.batch.begin();
        game.font.setColor(1f, 1f, 1f, 1f);
        glCache.setText(game.font, label);
        game.font.draw(game.batch, label,
                rect.x + rect.width  / 2f - glCache.width  / 2f,
                rect.y + rect.height / 2f + glCache.height / 2f);
        game.batch.end();
    }

    private void drawFooter() {
        game.batch.begin();
        game.font.setColor(0.32f, 0.32f, 0.40f, 1f);
        game.font.draw(game.batch, "v0.2.0", 10f, 24f);
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
