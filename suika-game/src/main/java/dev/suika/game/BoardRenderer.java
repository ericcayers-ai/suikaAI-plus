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
 * Draws the Suika play-field in the virtual-pixel space of {@link Theme}.
 *
 * <p>Game units → virtual pixels: {@code px = OX + gx*SCALE}, {@code py = OY + gy*SCALE}.
 * Fruits are rendered as smooth, depth-shaded glossy circles (no sprite atlas needed).
 * Rendering is split into a {@link ShapeRenderer} pass and a {@link SpriteBatch} label
 * pass so the two never interleave their begin/end blocks.
 */
public final class BoardRenderer {

    // --- Default board transform (portrait, 720×1280 virtual canvas) ---
    public static final float SCALE = 56f;
    public static final float OX    = 80f;   // screen x of inner left wall (gx = 0)
    public static final float OY    = 120f;  // screen y of the floor (gy = 0)

    // --- Landscape preset (1280×720 virtual canvas, board on right half) ---
    // Sized so the whole well (and the fruit's drop-in position above it) stays on-screen
    // and the right wall clears the RESTART button at x≈1138.
    public static final float SCALE_L = 40f;
    public static final float OX_L    = 655f;
    public static final float OY_L    = 66f;

    private final GlyphLayout gl = new GlyphLayout();
    private final Color tmp  = new Color();
    private final Color tmp2 = new Color();

    /** Instance-level transform — defaults to portrait, call {@link #useLandscape()} to switch. */
    private float iOX = OX, iOY = OY, iScale = SCALE;
    private boolean landscape = false;

    private float   hoverX = Float.NaN;     // game-x of the hover guide (NaN = hidden)
    private FruitTier hoverTier;

    public void usePortrait()                           { iOX = OX;    iOY = OY;    iScale = SCALE;   landscape = false; }
    public void useLandscape()                          { iOX = OX_L;  iOY = OY_L;  iScale = SCALE_L; landscape = true;  }
    public void useCustom(float ox, float oy, float sc) { iOX = ox;     iOY = oy;    iScale = sc;      landscape = false; }
    public boolean isLandscape(){ return landscape; }

    // Static helpers — always use portrait constants (backward compat for SuikaScreen etc.)
    public static float vpx(double gx) { return OX + (float) gx * SCALE; }
    public static float vpy(double gy) { return OY + (float) gy * SCALE; }
    public static float vpr(double gr) { return (float) gr * SCALE; }

    // Instance helpers — respect the current landscape/portrait mode
    public float bvpx(double gx) { return iOX + (float) gx * iScale; }
    public float bvpy(double gy) { return iOY + (float) gy * iScale; }
    public float bvpr(double gr) { return (float) gr * iScale; }

    /** Screen/virtual x of discrete drop column {@code i} of {@code bins} (portrait statics). */
    public static float columnX(int i, int bins) {
        double gx = PhysicsConfig.DROP_X_MIN
                + i / (double) (bins - 1) * (PhysicsConfig.DROP_X_MAX - PhysicsConfig.DROP_X_MIN);
        return vpx(gx);
    }

    /** Instance version — accounts for landscape offset. */
    public float bColumnX(int i, int bins) {
        double gx = PhysicsConfig.DROP_X_MIN
                + i / (double) (bins - 1) * (PhysicsConfig.DROP_X_MAX - PhysicsConfig.DROP_X_MIN);
        return bvpx(gx);
    }

    public void setHover(float gameX, FruitTier tier) {
        this.hoverX = gameX;
        this.hoverTier = tier;
    }

    public void clearHover() { this.hoverX = Float.NaN; }

    // -------------------------------------------------------------------------
    // Background (own Filled pass)
    // -------------------------------------------------------------------------

    public void drawBackground(ShapeRenderer s) {
        // vertical gradient
        s.rect(0, 0, Theme.VW, Theme.VH,
                Theme.BG_BOTTOM, Theme.BG_BOTTOM, Theme.BG_TOP, Theme.BG_TOP);
    }

    // -------------------------------------------------------------------------
    // Board (own Filled pass) — well, walls, dead-line, guide, fruit, particles
    // -------------------------------------------------------------------------

    public void drawBoard(ShapeRenderer s, GameState gs, GameSettings cfg, Particles particles) {
        drawBoard(s, gs, cfg, particles, 1f);
    }

    /** Alpha-multiplied variant used for ghost overlay rendering. */
    public void drawBoard(ShapeRenderer s, GameState gs, GameSettings cfg, Particles particles, float alpha) {
        float wallT  = bvpr(PhysicsConfig.WALL_THICKNESS);
        float innerL = bvpx(0);
        float innerR = bvpx(PhysicsConfig.CONTAINER_WIDTH);
        float floorY = bvpy(0);
        float topY   = bvpy(PhysicsConfig.CONTAINER_HEIGHT);
        float wellW  = innerR - innerL;
        float wellH  = topY - floorY;

        // Well interior
        s.setColor(Theme.WELL.r, Theme.WELL.g, Theme.WELL.b, Theme.WELL.a * alpha);
        Ui.fillRoundRect(s, innerL, floorY, wellW, wellH, 10f);

        // Walls (left, right, floor) drawn as rounded bars
        s.setColor(Theme.WALL.r, Theme.WALL.g, Theme.WALL.b, Theme.WALL.a * alpha);
        Ui.fillRoundRect(s, innerL - wallT, floorY - wallT, wallT, wellH + wallT, 6f);
        Ui.fillRoundRect(s, innerR,         floorY - wallT, wallT, wellH + wallT, 6f);
        Ui.fillRoundRect(s, innerL - wallT, floorY - wallT, wellW + 2 * wallT, wallT, 6f);
        s.setColor(Theme.WALL_HI.r, Theme.WALL_HI.g, Theme.WALL_HI.b, Theme.WALL_HI.a * alpha);
        Ui.fillRoundRect(s, innerL - wallT, topY - 4f, wellW + 2 * wallT, 4f, 2f);

        // Dead-line
        float dlY = bvpy(PhysicsConfig.DEADLINE_Y);
        boolean warn = gs.timeAboveDeadline() > 0.05;
        Color dl = warn ? Theme.DEADLINE_WARN : Theme.DEADLINE;
        float pulse = warn ? (float) (0.55 + 0.45 * Math.sin(gs.timeAboveDeadline() * 12.0)) : 1f;
        s.setColor(dl.r, dl.g, dl.b, dl.a * pulse * alpha);
        s.rect(innerL, dlY - 2f, wellW, 4f);
        s.setColor(dl.r, dl.g, dl.b, dl.a * 0.5f * pulse * alpha);
        s.rect(innerL, dlY - 6f, wellW, 2f);

        // Drop guide + ghost fruit (only at full alpha — not on ghost boards)
        if (alpha >= 1f && cfg.showGuide && !gs.gameOver() && !Float.isNaN(hoverX) && hoverTier != null) {
            float gx = bvpx(hoverX);
            float ghostR = bvpr(hoverTier.radius);
            s.setColor(1f, 1f, 1f, 0.10f);
            float yy = bvpy(PhysicsConfig.DROP_Y);
            while (yy > floorY) {
                s.rect(gx - 1.5f, yy, 3f, 12f);
                yy -= 24f;
            }
            drawFruit(s, gx, bvpy(PhysicsConfig.DROP_Y), ghostR, hoverTier, cfg, 0.45f);
        }

        // Fruits (glossy, depth-shaded)
        for (Fruit f : gs.fruits()) {
            drawFruit(s, bvpx(f.x()), bvpy(f.y()), bvpr(f.radius()), f.tier(), cfg, alpha);
        }

        // Merge particles (only on main board)
        if (alpha >= 1f && cfg.particles && particles != null) particles.render(s);
    }

    private void drawFruit(ShapeRenderer s, float cx, float cy, float r,
                           FruitTier tier, GameSettings cfg, float alpha) {
        int seg = cfg.smoothShading ? Math.max(18, (int) (r * 0.9f)) : 14;

        // rim / outline
        FruitColors.rim(tier, tmp);
        s.setColor(tmp.r, tmp.g, tmp.b, alpha);
        s.circle(cx, cy, r, seg);

        // body
        Color base = FruitColors.of(tier);
        s.setColor(base.r, base.g, base.b, alpha);
        s.circle(cx, cy, r * 0.90f, seg);

        if (cfg.smoothShading) {
            // upper highlight band → gives a glossy, 3-D feel
            FruitColors.highlight(tier, tmp2);
            s.setColor(tmp2.r, tmp2.g, tmp2.b, alpha * 0.45f);
            s.circle(cx - r * 0.22f, cy + r * 0.26f, r * 0.46f, seg);
            // tiny specular dot
            s.setColor(1f, 1f, 1f, alpha * 0.55f);
            s.circle(cx - r * 0.30f, cy + r * 0.34f, r * 0.14f, 12);
        }
    }

    // -------------------------------------------------------------------------
    // Tier labels (own SpriteBatch pass)
    // -------------------------------------------------------------------------

    public void drawLabels(SpriteBatch b, BitmapFont font, GameState gs, GameSettings cfg) {
        if (!cfg.tierLabels) return;
        for (Fruit f : gs.fruits()) {
            float r = bvpr(f.radius());
            if (r < 16f) continue; // too small to label legibly
            String t = Integer.toString(f.tier().tier);
            gl.setText(font, t);
            Color base = FruitColors.of(f.tier());
            float lum = 0.299f * base.r + 0.587f * base.g + 0.114f * base.b;
            font.setColor(lum > 0.6f ? 0.12f : 0.96f, lum > 0.6f ? 0.12f : 0.96f,
                          lum > 0.6f ? 0.14f : 0.99f, 0.85f);
            font.draw(b, t, bvpx(f.x()) - gl.width / 2f, bvpy(f.y()) + gl.height / 2f);
        }
    }
}
