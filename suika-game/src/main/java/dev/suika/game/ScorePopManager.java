package dev.suika.game;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

import java.util.ArrayList;
import java.util.List;

/**
 * Pooled floating "+N" score labels that rise and fade after a merge event.
 * Colour-coded by magnitude: gold for big merges, green for medium, white otherwise.
 */
public final class ScorePopManager {

    private static final class Pop {
        float x, y, vy, life, maxLife;
        String text;
        final Color color = new Color();
        boolean alive;
    }

    private final List<Pop> pool = new ArrayList<>();
    private final Color tmp = new Color();

    /** Spawn a pop at board virtual-pixel coordinates. Does nothing for 0 points. */
    public void add(float vpx, float vpy, long points) {
        if (points <= 0) return;
        Pop p = obtain();
        p.x = vpx;
        p.y = vpy + 16f;
        p.vy = 90f + Math.min(points * 0.06f, 80f);
        p.text = "+" + points;
        p.maxLife = 0.85f;
        p.life    = p.maxLife;
        if      (points >= 500) p.color.set(Theme.GOLD);
        else if (points >= 100) p.color.set(Theme.ACCENT_2);
        else                    p.color.set(Theme.TEXT);
        p.alive = true;
    }

    public void update(float dt) {
        for (Pop p : pool) {
            if (!p.alive) continue;
            p.life -= dt;
            if (p.life <= 0f) { p.alive = false; continue; }
            p.y  += p.vy * dt;
            p.vy *= (1f - 2.2f * dt);
        }
    }

    /**
     * Draw all live pops. Must be called inside an active {@code batch.begin()} block.
     * Temporarily overrides the font colour; resets it to white after drawing.
     */
    public void draw(SpriteBatch batch, BitmapFont font) {
        for (Pop p : pool) {
            if (!p.alive) continue;
            float alpha = Math.max(0f, p.life / p.maxLife);
            tmp.set(p.color.r, p.color.g, p.color.b, alpha);
            Ui.textCenter(batch, font, p.text, p.x, p.y, tmp);
        }
        font.setColor(Color.WHITE);
    }

    public void clear() {
        for (Pop p : pool) p.alive = false;
    }

    private Pop obtain() {
        for (Pop p : pool) if (!p.alive) return p;
        Pop p = new Pop();
        pool.add(p);
        return p;
    }
}
