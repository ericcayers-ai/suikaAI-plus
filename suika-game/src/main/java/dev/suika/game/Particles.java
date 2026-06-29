package dev.suika.game;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Tiny pooled particle system for merge "pop" sparkles (ROADMAP §I.5 merge feedback).
 * All coordinates are in the virtual-pixel space used by {@link BoardRenderer}.
 */
public final class Particles {

    private static final class P {
        float x, y, vx, vy, life, maxLife, size;
        final Color color = new Color();
        boolean alive;
    }

    private final List<P> pool = new ArrayList<>();
    private final Random rng = new Random(1234);

    /** Spawn a burst of {@code count} sparkles at a point in the given colour. */
    public void burst(float x, float y, Color color, int count) {
        for (int i = 0; i < count; i++) {
            P p = obtain();
            double ang = rng.nextDouble() * Math.PI * 2;
            float  spd = 120f + rng.nextFloat() * 260f;
            p.x = x; p.y = y;
            p.vx = (float) Math.cos(ang) * spd;
            p.vy = (float) Math.sin(ang) * spd + 60f;
            p.maxLife = 0.45f + rng.nextFloat() * 0.45f;
            p.life = p.maxLife;
            p.size = 3f + rng.nextFloat() * 5f;
            p.color.set(color);
            p.alive = true;
        }
    }

    public void update(float dt) {
        for (P p : pool) {
            if (!p.alive) continue;
            p.life -= dt;
            if (p.life <= 0f) { p.alive = false; continue; }
            p.vy -= 520f * dt;          // gravity
            p.vx *= (1f - 1.8f * dt);   // drag
            p.x += p.vx * dt;
            p.y += p.vy * dt;
        }
    }

    /** Draw all live particles. Caller manages the ShapeRenderer begin/end (Filled). */
    public void render(ShapeRenderer shapes) {
        for (P p : pool) {
            if (!p.alive) continue;
            float a = Math.max(0f, p.life / p.maxLife);
            shapes.setColor(p.color.r, p.color.g, p.color.b, a);
            shapes.circle(p.x, p.y, p.size * (0.4f + 0.6f * a), 10);
        }
    }

    public void clear() {
        for (P p : pool) p.alive = false;
    }

    private P obtain() {
        for (P p : pool) if (!p.alive) return p;
        P p = new P();
        pool.add(p);
        return p;
    }
}
