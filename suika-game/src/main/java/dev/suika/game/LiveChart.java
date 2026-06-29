package dev.suika.game;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * Fixed-capacity rolling line chart for live diagnostics (loss, fitness, score…).
 * Renders with {@link ShapeRenderer#rectLine} so it can share the caller's
 * {@link ShapeRenderer.ShapeType#Filled} pass — no separate Line batch needed.
 */
public final class LiveChart {

    private final float[] buf;
    private final int cap;
    private int count = 0;
    private int head = 0;

    public LiveChart(int capacity) {
        this.cap = capacity;
        this.buf = new float[capacity];
    }

    public void add(float v) {
        buf[head] = v;
        head = (head + 1) % cap;
        if (count < cap) count++;
    }

    public void clear() { count = 0; head = 0; }
    public int  size()  { return count; }
    public float latest() { return count > 0 ? at(count - 1) : 0f; }

    public float at(int i) {
        int start = (head - count + cap) % cap;
        return buf[(start + i) % cap];
    }

    /** Draw a connected line filling the rect (auto-scaled to data range). */
    public void render(ShapeRenderer s, float x, float y, float w, float h, Color c) {
        if (count < 2) return;
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (int i = 0; i < count; i++) { float v = at(i); lo = Math.min(lo, v); hi = Math.max(hi, v); }
        if (hi - lo < 1e-6f) { hi = lo + 1f; }
        s.setColor(c);
        for (int i = 0; i < count - 1; i++) {
            float x0 = x + w * (i / (float) (count - 1));
            float x1 = x + w * ((i + 1) / (float) (count - 1));
            float y0 = y + h * ((at(i)     - lo) / (hi - lo));
            float y1 = y + h * ((at(i + 1) - lo) / (hi - lo));
            s.rectLine(x0, y0, x1, y1, 2.2f);
        }
    }
}
