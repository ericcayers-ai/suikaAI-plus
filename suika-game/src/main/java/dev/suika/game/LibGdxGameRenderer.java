package dev.suika.game;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import dev.suika.core.Fruit;
import dev.suika.core.FruitTier;
import dev.suika.core.GameState;
import dev.suika.core.PhysicsConfig;

/**
 * Full LibGDX renderer for Suika Game using procedurally generated fruit graphics.
 *
 * <p>Fruit are drawn as filled coloured circles (one per tier colour, colourblind-safe)
 * labelled with their tier number. No external art assets are required (ROADMAP §I.6).
 *
 * <p>All coordinates are in <em>virtual-pixel space</em>. A {@code FitViewport} in the
 * enclosing {@link SuikaScreen} scales this to any actual window resolution. The conversion
 * from game units is: {@code virtualPx = OFFSET + gameUnit * SCALE}.
 */
public final class LibGdxGameRenderer implements GameRenderer {

    /** Virtual pixels per game unit — must match {@link LibGdxInputHandler#SCALE}. */
    static final float SCALE    = LibGdxInputHandler.SCALE;
    /** Virtual x origin of the game field (left inner wall). */
    static final float OFFSET_X = LibGdxInputHandler.OFFSET_X;
    /** Virtual y origin of the game field (floor level). */
    static final float OFFSET_Y = LibGdxInputHandler.OFFSET_Y;

    private static final int   CIRCLE_SEGS = 48;
    private static final int   HIGHLIGHT_SEGS = 24;

    private final ShapeRenderer shapes;
    private final SpriteBatch   batch;
    private final BitmapFont    font;
    private final BitmapFont    bigFont;
    private final GlyphLayout   glCache = new GlyphLayout();

    // Updated by SuikaScreen before each render() call
    private double    hoverX    = PhysicsConfig.CONTAINER_WIDTH / 2.0;
    private FruitTier hoverTier = FruitTier.CHERRY;

    public LibGdxGameRenderer(ShapeRenderer shapes, SpriteBatch batch,
                              BitmapFont font, BitmapFont bigFont) {
        this.shapes  = shapes;
        this.batch   = batch;
        this.font    = font;
        this.bigFont = bigFont;
    }

    /** Call before {@link #render} to update the hover-indicator state. */
    public void setHover(double x, FruitTier tier) {
        this.hoverX    = x;
        this.hoverTier = tier;
    }

    // -------------------------------------------------------------------------
    // GameRenderer contract
    // -------------------------------------------------------------------------

    @Override
    public void render(GameState state, double alpha) {
        drawBackground();
        drawContainer();
        drawDeadLine(state);
        drawFruits(state);
        drawHoverIndicator(state);
        drawHud(state);
        if (state.gameOver()) {
            drawGameOverOverlay(state);
        }
    }

    // -------------------------------------------------------------------------
    // Drawing passes
    // -------------------------------------------------------------------------

    private void drawBackground() {
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.10f, 0.10f, 0.16f, 1f);
        shapes.rect(0, 0, 720, 1060);
        shapes.end();
    }

    private void drawContainer() {
        float wt = (float) PhysicsConfig.WALL_THICKNESS * SCALE;
        float cw = (float) PhysicsConfig.CONTAINER_WIDTH  * SCALE;
        float ch = (float) PhysicsConfig.CONTAINER_HEIGHT * SCALE;

        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.52f, 0.53f, 0.58f, 1f);
        // Floor
        shapes.rect(OFFSET_X - wt, OFFSET_Y - wt, cw + 2 * wt, wt);
        // Left wall
        shapes.rect(OFFSET_X - wt, OFFSET_Y, wt, ch);
        // Right wall
        shapes.rect(OFFSET_X + cw, OFFSET_Y, wt, ch);
        shapes.end();
    }

    private void drawDeadLine(GameState state) {
        float dy = OFFSET_Y + (float) PhysicsConfig.DEADLINE_Y * SCALE;
        float cw = (float) PhysicsConfig.CONTAINER_WIDTH * SCALE;
        boolean warning = state.anyFruitAboveDeadline();

        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(
            warning ? 1.00f : 0.78f,
            warning ? 0.18f : 0.18f,
            warning ? 0.08f : 0.28f,
            warning ? 0.95f : 0.55f
        );
        shapes.rect(OFFSET_X, dy - 2f, cw, 4f);
        shapes.end();
    }

    private void drawFruits(GameState state) {
        if (state.fruits().isEmpty()) return;

        // Pass 1: outlines (darker ring around each circle)
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (Fruit f : state.fruits()) {
            Color c = FruitColors.of(f.tier());
            shapes.setColor(c.r * 0.50f, c.g * 0.50f, c.b * 0.50f, 1f);
            shapes.circle(vpx(f.x()), vpy(f.y()), vpr(f.radius()) + 2f, CIRCLE_SEGS);
        }
        // Pass 2: main fill
        for (Fruit f : state.fruits()) {
            shapes.setColor(FruitColors.of(f.tier()));
            shapes.circle(vpx(f.x()), vpy(f.y()), vpr(f.radius()), CIRCLE_SEGS);
        }
        // Pass 3: specular highlight (top-left lobe)
        shapes.setColor(1f, 1f, 1f, 0.22f);
        for (Fruit f : state.fruits()) {
            float r = vpr(f.radius());
            shapes.circle(vpx(f.x()) - r * 0.26f, vpy(f.y()) + r * 0.28f,
                          r * 0.42f, HIGHLIGHT_SEGS);
        }
        shapes.end();

        // Pass 4: tier number labels
        batch.begin();
        font.setColor(1f, 1f, 1f, 0.94f);
        for (Fruit f : state.fruits()) {
            String label = fruitLabel(f.tier());
            glCache.setText(font, label);
            font.draw(batch, label,
                      vpx(f.x()) - glCache.width / 2f,
                      vpy(f.y()) + glCache.height / 2f);
        }
        batch.end();
    }

    private void drawHoverIndicator(GameState state) {
        if (state.gameOver()) return;

        float cx   = vpx(hoverX);
        float r    = vpr(hoverTier.radius);
        float cen  = OFFSET_Y + (float)(PhysicsConfig.CONTAINER_HEIGHT + 1.0) * SCALE;
        Color col  = FruitColors.of(hoverTier);

        // Dashed guide line from drop centre to floor
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(col.r, col.g, col.b, 0.28f);
        float y = cen - r;
        while (y > OFFSET_Y) {
            float segTop    = y;
            float segBottom = Math.max(OFFSET_Y, y - 18f);
            shapes.rect(cx - 1.5f, segBottom, 3f, segTop - segBottom);
            y -= 28f;
        }
        shapes.end();

        // Ghost fruit at drop position (semi-transparent)
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(col.r, col.g, col.b, 0.52f);
        shapes.circle(cx, cen, r, CIRCLE_SEGS);
        shapes.end();

        // Ghost outline
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(col.r * 0.60f, col.g * 0.60f, col.b * 0.60f, 0.80f);
        shapes.circle(cx, cen, r, CIRCLE_SEGS);
        shapes.end();
    }

    private void drawHud(GameState state) {
        float hx   = OFFSET_X + (float) PhysicsConfig.CONTAINER_WIDTH * SCALE + 22f;
        float htop = OFFSET_Y + (float) PhysicsConfig.CONTAINER_HEIGHT * SCALE;

        // Panel background
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.14f, 0.14f, 0.22f, 1f);
        shapes.rect(hx - 8f, htop - 300f, 158f, 305f);
        shapes.end();

        // Score text
        batch.begin();
        font.setColor(0.70f, 0.72f, 0.80f, 1f);
        font.draw(batch, "SCORE", hx, htop - 6f);
        bigFont.setColor(1f, 1f, 1f, 1f);
        bigFont.draw(batch, Long.toString(state.score()), hx, htop - 34f);

        font.setColor(0.60f, 0.62f, 0.70f, 1f);
        font.draw(batch, "BEST",  hx, htop - 102f);
        bigFont.setColor(0.82f, 0.84f, 0.90f, 1f);
        bigFont.draw(batch, Long.toString(state.bestScore()), hx, htop - 130f);

        font.setColor(0.70f, 0.72f, 0.80f, 1f);
        font.draw(batch, "NEXT FRUIT", hx, htop - 198f);
        batch.end();

        // Next-fruit preview
        if (state.nextFruitTier() != null) {
            float nx = hx + 52f;
            float ny = htop - 265f;
            float nr = Math.min(40f, vpr(state.nextFruitTier().radius) * 0.7f);
            Color nc = FruitColors.of(state.nextFruitTier());
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(nc.r * 0.52f, nc.g * 0.52f, nc.b * 0.52f, 1f);
            shapes.circle(nx, ny, nr + 2f, CIRCLE_SEGS);
            shapes.setColor(nc);
            shapes.circle(nx, ny, nr, CIRCLE_SEGS);
            shapes.end();
            String label = fruitLabel(state.nextFruitTier());
            batch.begin();
            font.setColor(1f, 1f, 1f, 0.90f);
            glCache.setText(font, label);
            font.draw(batch, label, nx - glCache.width / 2f, ny + glCache.height / 2f);
            batch.end();
        }

        // Controls hint (bottom)
        batch.begin();
        font.setColor(0.42f, 0.42f, 0.50f, 1f);
        font.draw(batch, "Click to drop", hx, OFFSET_Y + 44f);
        font.draw(batch, "[R] Restart",   hx, OFFSET_Y + 22f);
        font.draw(batch, "[ESC] Menu",    hx, OFFSET_Y + 2f);
        batch.end();
    }

    private void drawGameOverOverlay(GameState state) {
        float cw = (float) PhysicsConfig.CONTAINER_WIDTH  * SCALE;
        float ch = (float) PhysicsConfig.CONTAINER_HEIGHT * SCALE;
        float ox = OFFSET_X;
        float oy = OFFSET_Y;

        // Dark overlay on game field
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0f, 0f, 0f, 0.72f);
        shapes.rect(ox, oy, cw, ch);
        shapes.end();

        float cx = ox + cw / 2f;

        batch.begin();
        bigFont.setColor(1f, 0.22f, 0.12f, 1f);
        glCache.setText(bigFont, "GAME OVER");
        bigFont.draw(batch, "GAME OVER",
                cx - glCache.width / 2f, oy + ch * 0.67f);

        String sc = "Score: " + state.score();
        bigFont.setColor(1f, 1f, 1f, 1f);
        glCache.setText(bigFont, sc);
        bigFont.draw(batch, sc, cx - glCache.width / 2f, oy + ch * 0.53f);

        font.setColor(0.78f, 0.78f, 0.84f, 1f);
        String hint = "[R] Play Again   [ESC] Menu";
        glCache.setText(font, hint);
        font.draw(batch, hint, cx - glCache.width / 2f, oy + ch * 0.40f);
        batch.end();
    }

    // -------------------------------------------------------------------------
    // Coordinate helpers
    // -------------------------------------------------------------------------

    /** Game x → virtual pixel x. */
    private float vpx(double gx) { return OFFSET_X + (float) gx * SCALE; }
    /** Game y → virtual pixel y. */
    private float vpy(double gy) { return OFFSET_Y + (float) gy * SCALE; }
    /** Game radius → virtual pixel radius. */
    private float vpr(double gr) { return (float) gr * SCALE; }

    private static String fruitLabel(FruitTier tier) {
        return tier.tier == 11 ? "W" : Integer.toString(tier.tier);
    }
}
