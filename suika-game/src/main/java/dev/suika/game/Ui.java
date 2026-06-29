package dev.suika.game;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;

/**
 * Immediate-mode drawing helpers for the UI: rounded panels, buttons, toggles, and
 * centred text. All shape methods assume the caller has an active
 * {@link ShapeRenderer.ShapeType#Filled} batch; text methods assume an active
 * {@link SpriteBatch}.
 */
public final class Ui {

    private Ui() {}

    private static final Color TMP = new Color();
    private static final GlyphLayout GL = new GlyphLayout();

    // ---- Shapes (ShapeRenderer must be begun in Filled mode) ----

    public static void fillRoundRect(ShapeRenderer s, float x, float y, float w, float h, float r) {
        r = Math.min(r, Math.min(w, h) / 2f);
        if (r <= 0.5f) { s.rect(x, y, w, h); return; }
        s.rect(x + r, y, w - 2 * r, h);
        s.rect(x, y + r, r, h - 2 * r);
        s.rect(x + w - r, y + r, r, h - 2 * r);
        s.circle(x + r,     y + r,     r, 18);
        s.circle(x + w - r, y + r,     r, 18);
        s.circle(x + r,     y + h - r, r, 18);
        s.circle(x + w - r, y + h - r, r, 18);
    }

    /** Filled rounded panel with a 2px lighter border. */
    public static void panel(ShapeRenderer s, float x, float y, float w, float h, float r,
                             Color fill, Color edge) {
        s.setColor(edge);
        fillRoundRect(s, x - 2, y - 2, w + 4, h + 4, r + 2);
        s.setColor(fill);
        fillRoundRect(s, x, y, w, h, r);
    }

    /** A glossy button face. Draw the label separately in the text pass. */
    public static void button(ShapeRenderer s, Rectangle r, Color base, boolean hovered, boolean enabled) {
        Color face = base;
        if (!enabled) { TMP.set(base.r * 0.45f, base.g * 0.45f, base.b * 0.45f, 1f); face = TMP; }
        else if (hovered) { TMP.set(Math.min(1f, base.r * 1.18f), Math.min(1f, base.g * 1.18f),
                                    Math.min(1f, base.b * 1.18f), 1f); face = TMP; }
        // drop shadow
        s.setColor(0f, 0f, 0f, 0.35f);
        fillRoundRect(s, r.x + 3f, r.y - 5f, r.width, r.height, 16f);
        // face
        s.setColor(face);
        fillRoundRect(s, r.x, r.y, r.width, r.height, 16f);
        // top sheen (subtle — bright reflections are distracting, especially on dark overlays)
        s.setColor(1f, 1f, 1f, hovered ? 0.09f : 0.05f);
        fillRoundRect(s, r.x + 7f, r.y + r.height * 0.50f, r.width - 14f, r.height * 0.42f, 12f);
    }

    /** A pill toggle; draws an on/off track with a knob. */
    public static void toggle(ShapeRenderer s, float x, float y, float w, float h, boolean on) {
        s.setColor(on ? Theme.ACCENT_2 : new Color(0.28f, 0.30f, 0.40f, 1f));
        fillRoundRect(s, x, y, w, h, h / 2f);
        float knobR = h / 2f - 3f;
        float knobX = on ? (x + w - h / 2f) : (x + h / 2f);
        s.setColor(0.97f, 0.98f, 1f, 1f);
        s.circle(knobX, y + h / 2f, knobR, 20);
    }

    // ---- Text (SpriteBatch must be begun) ----

    public static void text(SpriteBatch b, BitmapFont f, String str, float x, float y, Color c) {
        f.setColor(c);
        f.draw(b, str, x, y);
    }

    /** Draws {@code str} horizontally centred on {@code cx}, baseline-centred on {@code cy}. */
    public static void textCenter(SpriteBatch b, BitmapFont f, String str, float cx, float cy, Color c) {
        GL.setText(f, str);
        f.setColor(c);
        f.draw(b, str, cx - GL.width / 2f, cy + GL.height / 2f);
    }

    /** Right-aligns {@code str} so its right edge sits at {@code rx}. */
    public static void textRight(SpriteBatch b, BitmapFont f, String str, float rx, float y, Color c) {
        GL.setText(f, str);
        f.setColor(c);
        f.draw(b, str, rx - GL.width, y);
    }

    public static float textWidth(BitmapFont f, String str) {
        GL.setText(f, str);
        return GL.width;
    }
}
