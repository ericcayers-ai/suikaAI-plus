package dev.suika.game;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.viewport.FitViewport;
import dev.suika.ai.ActionSpec;
import dev.suika.ai.AgentPlugin;
import dev.suika.ai.MctsAgent;
import dev.suika.core.FruitTier;
import dev.suika.core.GameCore;
import dev.suika.core.GameState;
import dev.suika.core.MergeEvent;
import dev.suika.core.PhysicsConfig;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Live-physics gameplay screen.
 *
 * <ul>
 *   <li>{@link Mode#HUMAN} — a fruit follows the mouse; click to drop and watch it
 *       fall and settle in real time.</li>
 *   <li>{@link Mode#AI_WATCH} — the configured {@link WatchAgents} agent thinks on a
 *       background thread, then drops; MCTS visit counts are drawn above the well.</li>
 * </ul>
 *
 * Physics advances on a fixed {@code 1/60 s} accumulator decoupled from the render
 * frame rate, so the chosen FPS cap never changes the simulation.
 */
public final class SuikaScreen extends ScreenAdapter {

    public enum Mode { HUMAN, AI_WATCH }

    private static final float DROP_COOLDOWN  = 0.24f; // min seconds between human drops (1.7× faster)
    private static final float GAME_OVER_WAIT = 2.2f;

    private final SuikaGame    game;
    private final GameSettings cfg;
    private final Mode         mode;

    private final OrthographicCamera camera = new OrthographicCamera();
    private final FitViewport viewport;
    private final BoardRenderer board = new BoardRenderer();
    private final Vector3 touch = new Vector3();

    private final GameCore core;
    private final long     seed;

    private double accumulator = 0;
    private float  dropCooldown = 0f;
    private float  hoverGameX   = (float) ((PhysicsConfig.DROP_X_MIN + PhysicsConfig.DROP_X_MAX) / 2.0);
    private float  shake = 0f;
    private float  gameOverTimer = -1f;
    private boolean paused = false;
    private long   displayScore = 0;  // smoothly lerps toward core.getScore()

    // --- AI watch ---
    private final AgentPlugin aiAgent;
    private final ActionSpec  aiSpec;
    private enum AiPhase { WAIT, THINK }
    private AiPhase aiPhase = AiPhase.WAIT;
    private float   aiTimer = 0.4f;
    private final AtomicReference<Double> aiResult = new AtomicReference<>(null);
    private volatile boolean aiThinking = false;
    private int   aiDrops = 0;
    private float aiLastX = Float.NaN;

    // --- pause overlay buttons ---
    private final Rectangle resumeBtn = new Rectangle(Theme.VW/2f-150, 660, 300, 70);
    private final Rectangle restartBtn= new Rectangle(Theme.VW/2f-150, 575, 300, 70);
    private final Rectangle menuBtn   = new Rectangle(Theme.VW/2f-150, 490, 300, 70);
    private float mx, my;

    public SuikaScreen(SuikaGame game, Mode mode) {
        this.game = game;
        this.cfg  = game.settings;
        this.mode = mode;
        viewport = new FitViewport(Theme.VW, Theme.VH, camera);
        camera.position.set(Theme.VW/2f, Theme.VH/2f, 0f);
        camera.update();

        this.seed = cfg.resolveSeed();
        this.core = new GameCore(seed);
        game.particles.clear();
        game.scorePops.clear();

        if (mode == Mode.AI_WATCH) {
            aiSpec  = ActionSpec.discrete(cfg.actionBins());
            aiAgent = WatchAgents.create(cfg.agentIndex, cfg.actionBins());
            aiTimer = cfg.aiMoveDelay;
        } else {
            aiSpec = null; aiAgent = null;
        }
    }

    @Override
    public void show() {
        Gdx.input.setInputProcessor(new InputAdapter() {
            @Override public boolean touchDown(int sx, int sy, int p, int b) {
                camera.unproject(touch.set(sx, sy, 0), viewport.getScreenX(), viewport.getScreenY(), viewport.getScreenWidth(), viewport.getScreenHeight());
                if (paused) { handlePauseClick(touch.x, touch.y); return true; }
                if (core.isGameOver()) { toGameOver(); return true; }
                if (mode == Mode.HUMAN && dropCooldown <= 0f && chuteClear()) {
                    core.spawnDrop(hoverGameX);
                    dropCooldown = DROP_COOLDOWN;
                }
                return true;
            }
            @Override public boolean mouseMoved(int sx, int sy) {
                camera.unproject(touch.set(sx, sy, 0), viewport.getScreenX(), viewport.getScreenY(), viewport.getScreenWidth(), viewport.getScreenHeight());
                mx = touch.x; my = touch.y;
                updateHoverFromScreen();
                return false;
            }
            @Override public boolean touchDragged(int sx, int sy, int p) {
                camera.unproject(touch.set(sx, sy, 0), viewport.getScreenX(), viewport.getScreenY(), viewport.getScreenWidth(), viewport.getScreenHeight());
                mx = touch.x; my = touch.y;
                updateHoverFromScreen();
                return false;
            }
            @Override public boolean keyDown(int k) {
                if (k == Input.Keys.ESCAPE || UiKeys.isPause(k)) { paused = !paused; return true; }
                if (k == Input.Keys.R)      { game.setScreen(new SuikaScreen(game, mode)); return true; }
                if (k == Input.Keys.SPACE && mode == Mode.HUMAN) {
                    // Keyboard-only drop, completing the arrow-key aim scheme below —
                    // mirrors touchDown's HUMAN branch exactly (same cooldown/chute/
                    // pause/game-over guards) so Space is a genuine alternative to
                    // clicking, not a shortcut that skips the click's safety checks.
                    if (!paused && !core.isGameOver() && dropCooldown <= 0f && chuteClear()) {
                        core.spawnDrop(hoverGameX);
                        dropCooldown = DROP_COOLDOWN;
                    }
                    return true;
                }
                return false;
            }
        });
    }

    /**
     * Converts the mouse position to a drop-x, clamped by the CURRENT fruit's own
     * radius (not a fixed margin) so the ghost preview lands exactly where
     * {@link GameCore#spawnDrop(double)} will actually place it — previously this used
     * a small fixed {@code DROP_X_MIN}/{@code DROP_X_MAX} margin, so large fruit near a
     * wall would preview closer to the wall than it actually settled.
     */
    private void updateHoverFromScreen() {
        float gx = (touch.x - BoardRenderer.OX) / BoardRenderer.SCALE;
        float radius = core.getState().currentFruitTier() != null
                ? core.getState().currentFruitTier().radius : 0.5f;
        hoverGameX = (float) GameCore.clampDropForRadius(gx, radius);
    }

    /** Test/QA hook: open the pause overlay (used by the capture harness). */
    void pauseForCapture() { paused = true; }

    private void handlePauseClick(float x, float y) {
        if (resumeBtn.contains(x, y)) paused = false;
        else if (restartBtn.contains(x, y)) game.setScreen(new SuikaScreen(game, mode));
        else if (menuBtn.contains(x, y)) game.setScreen(new MainMenuScreen(game));
    }

    private void toGameOver() {
        game.setScreen(new GameOverScreen(game, core.getScore(), core.getState(), mode, seed));
    }

    // -------------------------------------------------------------------------

    @Override
    public void render(float delta) {
        delta = Math.min(delta, 0.05f);
        BoardRenderer.tickFlash(delta);
        if (!paused) update(delta);

        // screen shake
        float ox = 0, oy = 0;
        if (cfg.screenShake && shake > 0f) {
            ox = MathUtils.random(-1f, 1f) * shake;
            oy = MathUtils.random(-1f, 1f) * shake;
            shake = Math.max(0f, shake - delta * 40f);
        }
        camera.position.set(Theme.VW/2f + ox, Theme.VH/2f + oy, 0f);
        camera.update();

        Gdx.gl.glClearColor(0.05f, 0.06f, 0.10f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_BLEND);

        viewport.apply();
        game.shapes.setProjectionMatrix(camera.combined);
        game.batch.setProjectionMatrix(camera.combined);

        GameState gs = core.getState();
        board.setHover(mode == Mode.HUMAN ? hoverGameX : Float.NaN, gs.currentFruitTier());

        ShapeRenderer s = game.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);
        board.drawBackground(s);
        board.drawBoard(s, gs, cfg, game.particles);
        if (mode == Mode.AI_WATCH) drawThinkingOverlay(s, gs);
        drawHudPanels(s, gs);
        s.end();

        game.batch.begin();
        board.drawLabels(game.batch, game.fontSmall, gs, cfg);
        drawHudText(gs);
        game.scorePops.draw(game.batch, game.fontMed);
        game.batch.end();

        if (core.isGameOver())  drawGameOverFade(delta);
        if (paused)             drawPauseOverlay();
    }

    /** Window-widths-per-second glide speed for arrow-key aiming — fast enough to cross
     *  the well in well under a second, slow enough for a precise final nudge. */
    private static final float ARROW_AIM_SPEED = (float)
            ((PhysicsConfig.DROP_X_MAX - PhysicsConfig.DROP_X_MIN) * 1.4);

    /** Left/Right arrows glide the hover position exactly like mouse movement would —
     *  a keyboard-only alternative for aiming (Space drops, see keyDown) so the human
     *  player never needs the mouse at all. Polled every frame (libGDX's isKeyPressed)
     *  rather than tracked via press/release events, since a human can hold the key
     *  down across many frames. */
    private void updateHoverFromArrowKeys(float delta) {
        boolean left  = Gdx.input.isKeyPressed(Input.Keys.LEFT);
        boolean right = Gdx.input.isKeyPressed(Input.Keys.RIGHT);
        if (!left && !right) return;
        float radius = core.getState().currentFruitTier() != null
                ? core.getState().currentFruitTier().radius : 0.5f;
        float gx = hoverGameX + (right ? 1f : 0f) * ARROW_AIM_SPEED * delta
                              - (left  ? 1f : 0f) * ARROW_AIM_SPEED * delta;
        hoverGameX = (float) GameCore.clampDropForRadius(gx, radius);
    }

    private void update(float delta) {
        if (dropCooldown > 0f) dropCooldown -= delta;
        if (mode == Mode.HUMAN && !paused) updateHoverFromArrowKeys(delta);

        // smooth score display — count up toward the real score
        long actual = core.getScore();
        long diff = actual - displayScore;
        if (diff > 0) {
            displayScore += Math.max(1L, (long) (diff * Math.min(1.0, delta * 10.0)));
            if (displayScore > actual) displayScore = actual;
        }

        game.scorePops.update(delta);

        // fixed-step physics
        accumulator += delta;
        int steps = 0;
        while (accumulator >= PhysicsConfig.FIXED_DT && steps < PhysicsConfig.MAX_SUB_STEPS) {
            List<MergeEvent> merges = core.tick();
            for (MergeEvent m : merges) onMerge(m);
            accumulator -= PhysicsConfig.FIXED_DT;
            steps++;
        }
        game.particles.update(delta);

        if (mode == Mode.AI_WATCH) updateAi(delta);

        if (core.isGameOver()) {
            if (cfg.immediateDeadline) { toGameOver(); return; }
            if (gameOverTimer < 0f) gameOverTimer = GAME_OVER_WAIT;
            gameOverTimer -= delta;
            if (gameOverTimer <= 0f) toGameOver();
        }
    }

    private void onMerge(MergeEvent m) {
        if (m.resultTier() != null) {
            float vpx = BoardRenderer.vpx(m.spawnX());
            float vpy = BoardRenderer.vpy(m.spawnY());
            if (cfg.particles) {
                game.particles.burst(vpx, vpy, FruitColors.of(m.resultTier()), 10 + m.resultTier().tier * 2);
            }
            game.scorePops.add(vpx, vpy, m.scoreAwarded());
            if (m.resultTier().tier >= 6) shake = 7f;
        }
    }

    private void updateAi(float delta) {
        if (core.isGameOver()) return;
        switch (aiPhase) {
            case WAIT -> {
                // Steady cadence: only require the drop chute to be clear, not a full
                // settle (lone fruit can take seconds to fully sleep). This keeps the
                // AI lively and matches how a strong player drops continuously.
                aiTimer -= delta;
                if (aiTimer <= 0f && chuteClear()) startThinking();
            }
            case THINK -> {
                Double x = aiResult.getAndSet(null);
                if (x != null && !aiThinking) {
                    aiLastX = x.floatValue();
                    core.spawnDrop(x);
                    aiDrops++;
                    aiPhase = AiPhase.WAIT;
                    aiTimer = cfg.aiMoveDelay;
                }
            }
        }
    }

    /** True when nothing is still falling through the drop chute above the rim — checks
     *  each fruit's TOP surface (y + radius), not just its center. */
    private boolean chuteClear() {
        double thresh = PhysicsConfig.CONTAINER_HEIGHT - 1.0;
        for (var f : core.getState().fruits()) {
            if (f.y() + f.radius() > thresh) return false;
        }
        return true;
    }

    private void startThinking() {
        aiPhase = AiPhase.THINK;
        aiThinking = true;
        final GameCore snap = core.snapshot();
        Thread t = new Thread(() -> {
            Object a = aiAgent.selectAction(snap, aiSpec);
            double x = aiSpec.toDropX(a, PhysicsConfig.DROP_X_MIN, PhysicsConfig.DROP_X_MAX);
            aiThinking = false;
            aiResult.set(x);
        }, "ai-think");
        t.setDaemon(true);
        t.start();
    }

    // -------------------------------------------------------------------------
    // HUD
    // -------------------------------------------------------------------------

    private void drawHudPanels(ShapeRenderer s, GameState gs) {
        // top score panel (left)
        Ui.panel(s, 40, Theme.VH - 168, 300, 120, 14, Theme.PANEL, Theme.PANEL_EDGE);
        // next-fruit panel (right)
        Ui.panel(s, Theme.VW - 200, Theme.VH - 168, 160, 120, 14, Theme.PANEL, Theme.PANEL_EDGE);
        // next fruit chip
        FruitTier nt = gs.nextFruitTier();
        float r = Math.min(34f, BoardRenderer.vpr(nt.radius));
        var c = FruitColors.of(nt);
        s.setColor(c.r, c.g, c.b, 1f);
        s.circle(Theme.VW - 120, Theme.VH - 132, r, 28);
    }

    private void drawHudText(GameState gs) {
        Ui.text(game.batch, game.fontSmall, "SCORE", 60, Theme.VH - 64, Theme.TEXT_DIM);
        Ui.text(game.batch, game.fontBig, Long.toString(displayScore), 58, Theme.VH - 86, Theme.TEXT);
        Ui.textRight(game.batch, game.fontSmall, "BEST  " + gs.bestScore(), 320, Theme.VH - 150, Theme.TEXT_DIM);

        Ui.textCenter(game.batch, game.fontSmall, "NEXT", Theme.VW - 120, Theme.VH - 64, Theme.TEXT_DIM);

        // mode badge + hints (bottom)
        String badge = mode == Mode.HUMAN ? "HUMAN" : "AI WATCH";
        String controls = mode == Mode.HUMAN ? "  ·  ←/→ + SPACE or mouse + click" : "";
        Ui.text(game.batch, game.fontSmall, badge + controls + "   ·   ESC pause   ·   R restart",
                48, 44, Theme.TEXT_FAINT);

        if (mode == Mode.AI_WATCH) {
            String name = WatchAgents.get(cfg.agentIndex).name();
            String status = aiThinking ? "thinking..." : (aiPhase == AiPhase.WAIT ? "watching" : "");
            Ui.textCenter(game.batch, game.font, name, Theme.VW/2f, Theme.VH - 70, Theme.ACCENT_BLUE);
            Ui.textCenter(game.batch, game.fontSmall, "drops " + aiDrops + "   ·   " + status,
                    Theme.VW/2f, Theme.VH - 98, Theme.TEXT_DIM);
        }
    }

    /** MCTS visit-count bars above the well + chosen-column marker ("see it think"). */
    private void drawThinkingOverlay(ShapeRenderer s, GameState gs) {
        if (!cfg.showThinking || !(aiAgent instanceof MctsAgent m)) return;
        int[] visits = m.lastVisits();
        if (visits.length == 0) return;
        int max = 1;
        for (int v : visits) max = Math.max(max, v);
        float baseY = BoardRenderer.vpy(PhysicsConfig.CONTAINER_HEIGHT) + 12f;
        float barW = (BoardRenderer.vpx(PhysicsConfig.CONTAINER_WIDTH) - BoardRenderer.vpx(0))
                / visits.length * 0.7f;
        for (int i = 0; i < visits.length; i++) {
            float x = BoardRenderer.columnX(i, visits.length);
            float h = 8f + 64f * (visits[i] / (float) max);
            s.setColor(Theme.ACCENT_BLUE.r, Theme.ACCENT_BLUE.g, Theme.ACCENT_BLUE.b, 0.75f);
            Ui.fillRoundRect(s, x - barW/2f, baseY, barW, h, 3f);
        }
        if (!Float.isNaN(aiLastX)) {
            float x = BoardRenderer.vpx(aiLastX);
            s.setColor(Theme.GOLD);
            s.rect(x - 2f, baseY, 4f, 84f);
        }
    }

    // -------------------------------------------------------------------------
    // Overlays
    // -------------------------------------------------------------------------

    private void drawGameOverFade(float delta) {
        float a = gameOverTimer >= 0f ? MathUtils.clamp((GAME_OVER_WAIT - gameOverTimer) / GAME_OVER_WAIT, 0f, 0.7f) : 0.7f;
        ShapeRenderer s = game.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);
        s.setColor(0f, 0f, 0f, a);
        s.rect(0, 0, Theme.VW, Theme.VH);
        s.end();
        game.batch.begin();
        Ui.textCenter(game.batch, game.fontHuge, "GAME OVER", Theme.VW/2f, Theme.VH/2f, Theme.ACCENT);
        game.batch.end();
    }

    private void drawPauseOverlay() {
        ShapeRenderer s = game.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);
        s.setColor(0f, 0f, 0f, 0.62f);
        s.rect(0, 0, Theme.VW, Theme.VH);
        Ui.button(s, resumeBtn,  Theme.ACCENT_2,    resumeBtn.contains(mx,my),  true);
        Ui.button(s, restartBtn, Theme.ACCENT_BLUE, restartBtn.contains(mx,my), true);
        Ui.button(s, menuBtn,    Theme.ACCENT,      menuBtn.contains(mx,my),    true);
        s.end();
        game.batch.begin();
        Ui.textCenter(game.batch, game.fontBig, "PAUSED", Theme.VW/2f, 840, Theme.TEXT);
        Ui.textCenter(game.batch, game.fontMed, "RESUME",    Theme.VW/2f, resumeBtn.y+35, Theme.TEXT);
        Ui.textCenter(game.batch, game.fontMed, "RESTART",   Theme.VW/2f, restartBtn.y+35, Theme.TEXT);
        Ui.textCenter(game.batch, game.fontMed, "MAIN MENU", Theme.VW/2f, menuBtn.y+35, Theme.TEXT);
        game.batch.end();
    }

    @Override public void resize(int width, int height) { viewport.update(width, height); }
    @Override public void hide() { Gdx.input.setInputProcessor(null); }
}
