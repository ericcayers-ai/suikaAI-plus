package dev.suika.game;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.utils.viewport.FitViewport;
import dev.suika.ai.ActionSpec;
import dev.suika.ai.AgentPlugin;
import dev.suika.ai.MctsAgent;
import dev.suika.core.GameCore;
import dev.suika.core.PhysicsConfig;

/**
 * The main playing screen.
 *
 * <p>Supports two modes:
 * <ul>
 *   <li>{@link Mode#HUMAN} — the player drops fruit by clicking; hover follows the mouse.</li>
 *   <li>{@link Mode#AI_WATCH} — an MCTS agent plays automatically at a readable pace.</li>
 * </ul>
 *
 * <p>Press {@code R} to restart or {@code ESC} to return to the main menu at any time.
 * After game-over, a short countdown auto-transitions to {@link GameOverScreen}.
 */
public final class SuikaScreen extends ScreenAdapter {

    public enum Mode { HUMAN, AI_WATCH }

    /** Virtual viewport dimensions (px). All rendering is in this coordinate space. */
    static final float VW = 720f, VH = 1060f;

    private static final float AI_DROP_DELAY = 0.85f; // seconds between AI moves
    private static final float GAME_OVER_DELAY = 4.0f; // auto-navigate after game over

    private final SuikaGame game;
    private final Mode      mode;

    private final OrthographicCamera camera;
    private final FitViewport        viewport;
    private final GameCore           core;
    private final LibGdxInputHandler inputHandler;
    private final LibGdxGameRenderer renderer;
    private final GameLoop           gameLoop;

    private final AgentPlugin aiAgent;   // null in HUMAN mode
    private float aiTimer       = 0f;
    private float gameOverTimer = -1f;

    public SuikaScreen(SuikaGame game, Mode mode) {
        this.game = game;
        this.mode = mode;

        camera   = new OrthographicCamera();
        viewport = new FitViewport(VW, VH, camera);
        camera.position.set(VW / 2f, VH / 2f, 0f);
        camera.update();

        core         = new GameCore(System.currentTimeMillis());
        inputHandler = new LibGdxInputHandler(camera);
        renderer     = new LibGdxGameRenderer(game.shapes, game.batch, game.font, game.bigFont);
        gameLoop     = new GameLoop(core, renderer, inputHandler);

        aiAgent = (mode == Mode.AI_WATCH) ? new MctsAgent(50, Math.sqrt(2), 5, 32) : null;
    }

    // -------------------------------------------------------------------------
    // Screen lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void show() {
        Gdx.input.setInputProcessor(new InputMultiplexer(
            inputHandler,
            new InputAdapter() {
                @Override
                public boolean keyDown(int keycode) {
                    if (keycode == Input.Keys.R) {
                        game.setScreen(new SuikaScreen(game, mode));
                        return true;
                    }
                    if (keycode == Input.Keys.ESCAPE) {
                        game.setScreen(new MainMenuScreen(game));
                        return true;
                    }
                    return false;
                }
            }
        ));
    }

    @Override
    public void render(float delta) {
        Gdx.gl.glClearColor(0.10f, 0.10f, 0.16f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        viewport.apply();
        game.shapes.setProjectionMatrix(camera.combined);
        game.batch.setProjectionMatrix(camera.combined);

        // Update hover indicator with current state before rendering
        renderer.setHover(inputHandler.getHoverX(), core.getState().currentFruitTier());

        if (mode == Mode.HUMAN) {
            // GameLoop handles: poll input → drop if needed → render
            gameLoop.tick(System.nanoTime());
        } else {
            // AI Watch: render every frame; drop when timer expires
            renderer.render(core.getState(), 0.0);
            if (!core.isGameOver()) {
                aiTimer -= delta;
                if (aiTimer <= 0f) {
                    performAiDrop();
                    aiTimer = AI_DROP_DELAY;
                }
            }
        }

        // Game-over countdown → auto-transition to GameOverScreen
        if (core.isGameOver()) {
            if (gameOverTimer < 0f) gameOverTimer = GAME_OVER_DELAY;
            gameOverTimer -= delta;
            if (gameOverTimer <= 0f) {
                game.setScreen(new GameOverScreen(game, core.getScore(), mode));
            }
        }
    }

    @Override
    public void resize(int width, int height) {
        viewport.update(width, height);
    }

    @Override
    public void hide() {
        Gdx.input.setInputProcessor(null);
    }

    @Override
    public void dispose() {
        // Shared rendering resources are owned by SuikaGame; nothing to dispose here.
    }

    // -------------------------------------------------------------------------

    private void performAiDrop() {
        if (core.isGameOver()) return;
        ActionSpec spec   = ActionSpec.discrete(32);
        Object     action = aiAgent.selectAction(core.getState(), spec);
        double     x      = spec.toDropX(action, PhysicsConfig.DROP_X_MIN, PhysicsConfig.DROP_X_MAX);
        core.dropAndSettle(x);
    }
}
