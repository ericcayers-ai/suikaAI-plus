package dev.suika.game;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;

/**
 * Immediate-mode drawing helpers for the UI: rounded panels, buttons, toggles,
 * modals, cyclers, setting rows, toasts, focus rings, and status states.
 * All shape methods assume an active {@link ShapeRenderer.ShapeType#Filled} batch;
 * text methods assume an active {@link SpriteBatch}.
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

    /** Full-screen plum gradient backdrop for a virtual canvas. */
    public static void background(ShapeRenderer s, float vw, float vh) {
        s.setColor(Theme.BG_BOTTOM);
        s.rect(0, 0, vw, vh, Theme.BG_BOTTOM, Theme.BG_BOTTOM, Theme.BG_TOP, Theme.BG_TOP);
    }

    /** Dim overlay used behind modal cards. */
    public static void modalScrim(ShapeRenderer s, float vw, float vh) {
        s.setColor(Theme.OVERLAY);
        s.rect(0, 0, vw, vh);
    }

    /** Modal card shell (edge + fill). Draw title/body in the text pass. */
    public static void modalCard(ShapeRenderer s, float x, float y, float w, float h) {
        panel(s, x, y, w, h, Theme.RADIUS_LG, Theme.PANEL, Theme.PANEL_EDGE);
    }

    /** A glossy button face. Draw the label separately in the text pass. */
    public static void button(ShapeRenderer s, Rectangle r, Color base, boolean hovered, boolean enabled) {
        button(s, r, base, hovered, enabled, false);
    }

    /**
     * Button face with optional pressed (active) state — darkens slightly so click
     * feedback is visible without a separate animation system.
     */
    public static void button(ShapeRenderer s, Rectangle r, Color base, boolean hovered,
                              boolean enabled, boolean pressed) {
        Color face = base;
        if (!enabled) { TMP.set(base.r * 0.45f, base.g * 0.45f, base.b * 0.45f, 1f); face = TMP; }
        else if (pressed) { TMP.set(base.r * 0.82f, base.g * 0.82f, base.b * 0.82f, 1f); face = TMP; }
        else if (hovered) { TMP.set(Math.min(1f, base.r * 1.18f), Math.min(1f, base.g * 1.18f),
                                    Math.min(1f, base.b * 1.18f), 1f); face = TMP; }
        float shadowLift = pressed ? 1f : 5f;
        float shadowOff  = pressed ? 1f : 3f;
        s.setColor(Theme.SHADOW);
        fillRoundRect(s, r.x + shadowOff, r.y - shadowLift, r.width, r.height, Theme.RADIUS_LG);
        s.setColor(face);
        fillRoundRect(s, r.x, pressed ? r.y - 2f : r.y, r.width, r.height, Theme.RADIUS_LG);
        s.setColor(1f, 1f, 1f, hovered && !pressed ? 0.16f : 0.10f);
        float top = pressed ? r.y - 2f : r.y;
        fillRoundRect(s, r.x + 7f, top + r.height * 0.82f, r.width - 14f, r.height * 0.14f, Theme.RADIUS_SM);
    }

    /** A pill toggle; draws an on/off track with a knob. */
    public static void toggle(ShapeRenderer s, float x, float y, float w, float h, boolean on) {
        s.setColor(on ? Theme.ACCENT_2 : Theme.DISABLED);
        fillRoundRect(s, x, y, w, h, h / 2f);
        float knobR = h / 2f - 3f;
        float knobX = on ? (x + w - h / 2f) : (x + h / 2f);
        s.setColor(0.97f, 0.98f, 1f, 1f);
        s.circle(knobX, y + h / 2f, knobR, 20);
    }

    /**
     * Draws a value cycler track (± click halves). Returns the hit rectangle for
     * reuse; callers typically pass a pre-laid-out {@link Rectangle}.
     */
    public static void cycler(ShapeRenderer s, Rectangle r, boolean hovered, boolean enabled) {
        Color fill = enabled
                ? (hovered ? Theme.PANEL_EDGE : Theme.PANEL)
                : Theme.DISABLED;
        panel(s, r.x, r.y, r.width, r.height, Theme.RADIUS_SM, fill, Theme.PANEL_EDGE);
        // Subtle centre divider hinting at left/right halves
        s.setColor(1f, 1f, 1f, enabled ? 0.08f : 0.03f);
        s.rect(r.x + r.width / 2f - 0.5f, r.y + 4f, 1f, r.height - 8f);
    }

    /**
     * Setting-row chrome: full-width track for label + control, with optional hover.
     * Height is expanded to at least {@link Theme#MIN_TARGET}.
     */
    public static void settingRow(ShapeRenderer s, float x, float y, float w, float h,
                                  boolean hovered, boolean focused) {
        float hh = Math.max(h, Theme.MIN_TARGET);
        s.setColor(hovered ? Theme.ACCENT_SOFT : Theme.PANEL_DEEP);
        fillRoundRect(s, x, y, w, hh, Theme.RADIUS_MD);
        if (focused) focusRing(s, x, y, w, hh, Theme.RADIUS_MD);
    }

    /**
     * Visible keyboard focus ring drawn <em>after</em> the control: a thin gold
     * frame around the hit target so focus never washes out the label.
     */
    public static void focusRing(ShapeRenderer s, float x, float y, float w, float h, float radius) {
        focusOutline(s, x, y, w, h);
    }

    /** Outline-only focus accent drawn AFTER the control. */
    public static void focusOutline(ShapeRenderer s, float x, float y, float w, float h) {
        float t = 2.5f;
        s.setColor(Theme.FOCUS);
        s.rect(x - t, y, t, h);
        s.rect(x + w, y, t, h);
        s.rect(x, y - t, w, t);
        s.rect(x, y + h, w, t);
        s.circle(x, y, t, 10);
        s.circle(x + w, y, t, 10);
        s.circle(x, y + h, t, 10);
        s.circle(x + w, y + h, t, 10);
    }

    /** Toast / banner shell near the bottom of the canvas. */
    public static void toastShell(ShapeRenderer s, float cx, float y, float w, float h, Color accent) {
        float x = cx - w / 2f;
        panel(s, x, y, w, h, Theme.RADIUS_MD, Theme.TOAST_BG, accent);
    }

    /** Empty-state placeholder panel (no results / no slots / no models). */
    public static void emptyState(ShapeRenderer s, float x, float y, float w, float h) {
        panel(s, x, y, w, h, Theme.RADIUS_MD, Theme.PANEL_DEEP, Theme.PANEL_EDGE);
    }

    /** Loading bar fill (0..1) inside a track. */
    public static void loadingBar(ShapeRenderer s, float x, float y, float w, float h, float frac) {
        s.setColor(Theme.DISABLED);
        fillRoundRect(s, x, y, w, h, h / 2f);
        float f = Math.max(0f, Math.min(1f, frac));
        if (f > 0.01f) {
            s.setColor(Theme.ACCENT_2);
            fillRoundRect(s, x, y, Math.max(h, w * f), h, h / 2f);
        }
    }

    /** Ensure a hit target meets {@link Theme#MIN_TARGET} without shifting its centre. */
    public static void ensureMinTarget(Rectangle r) {
        if (r.width < Theme.MIN_TARGET) {
            float d = Theme.MIN_TARGET - r.width;
            r.x -= d / 2f;
            r.width = Theme.MIN_TARGET;
        }
        if (r.height < Theme.MIN_TARGET) {
            float d = Theme.MIN_TARGET - r.height;
            r.y -= d / 2f;
            r.height = Theme.MIN_TARGET;
        }
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

    /** Cycler value centred in the control, with faint − / + hints when enabled. */
    public static void cyclerLabel(SpriteBatch b, BitmapFont f, Rectangle r, String value,
                                   boolean enabled) {
        textCenter(b, f, value, r.x + r.width / 2f, r.y + r.height / 2f,
                enabled ? Theme.TEXT : Theme.TEXT_FAINT);
        if (enabled) {
            text(b, f, "−", r.x + 10f, r.y + r.height / 2f + 6f, Theme.TEXT_DIM);
            textRight(b, f, "+", r.x + r.width - 10f, r.y + r.height / 2f + 6f, Theme.TEXT_DIM);
        }
    }

    /** Empty / loading / retry copy block centred in a region. */
    public static void statusCopy(SpriteBatch b, BitmapFont title, BitmapFont body,
                                  float cx, float cy, String headline, String detail) {
        textCenter(b, title, headline, cx, cy + 14f, Theme.TEXT_DIM);
        if (detail != null && !detail.isBlank())
            textCenter(b, body, detail, cx, cy - 14f, Theme.TEXT_FAINT);
    }
}
