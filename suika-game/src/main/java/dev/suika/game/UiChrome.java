package dev.suika.game;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;

/**
 * Shared screen chrome for product UI: title block, bottom action bar, section chips,
 * and quiet utility controls. Keeps navigation vocabulary consistent so every tool
 * screen feels like the same app.
 */
public final class UiChrome {

    private UiChrome() {}

    /** Portrait bottom-bar geometry used by Playground / Settings / Lab. */
    public static final float BAR_Y = 16f;
    public static final float BAR_H = 64f;
    public static final float BAR_MARGIN = 36f;
    public static final float BAR_GAP = 20f;

    /** Places BACK (left) and optional primary CTA (right) in the bottom safe band. */
    public static void layoutBottomBar(Rectangle back, Rectangle primary, float vw) {
        float usable = vw - 2f * BAR_MARGIN;
        if (primary == null) {
            float w = Math.min(300f, usable);
            back.set((vw - w) / 2f, BAR_Y, w, BAR_H);
            return;
        }
        float w = (usable - BAR_GAP) / 2f;
        back.set(BAR_MARGIN, BAR_Y, w, BAR_H);
        primary.set(BAR_MARGIN + w + BAR_GAP, BAR_Y, w, BAR_H);
    }

    /** Quiet secondary / utility button (panel edge, no loud accent). */
    public static void secondaryButton(ShapeRenderer s, Rectangle r, boolean hovered, boolean enabled) {
        Ui.button(s, r, Theme.PANEL_EDGE, hovered, enabled);
    }

    /** Ghost / text-link affordance: soft fill on hover only. */
    public static void ghostButton(ShapeRenderer s, Rectangle r, boolean hovered, boolean enabled) {
        if (!enabled) {
            s.setColor(Theme.DISABLED);
            Ui.fillRoundRect(s, r.x, r.y, r.width, r.height, Theme.RADIUS_MD);
            return;
        }
        if (hovered) {
            s.setColor(Theme.ACCENT_SOFT);
            Ui.fillRoundRect(s, r.x, r.y, r.width, r.height, Theme.RADIUS_MD);
        }
        s.setColor(hovered ? Theme.PANEL_EDGE : Theme.TEXT_FAINT);
        float t = 1.5f;
        s.rect(r.x, r.y, r.width, t);
        s.rect(r.x, r.y + r.height - t, r.width, t);
        s.rect(r.x, r.y, t, r.height);
        s.rect(r.x + r.width - t, r.y, t, r.height);
    }

    /** Compact section/filter chip used for jump navigation. */
    public static void chip(ShapeRenderer s, Rectangle r, boolean selected, boolean hovered) {
        Color fill = selected ? Theme.GOLD : (hovered ? Theme.PANEL : Theme.PANEL_DEEP);
        Color edge = selected ? Theme.GOLD : Theme.PANEL_EDGE;
        Ui.panel(s, r.x, r.y, r.width, r.height, Theme.RADIUS_SM, fill, edge);
    }

    public static void chipLabel(SpriteBatch b, BitmapFont f, Rectangle r, String label, boolean selected) {
        Ui.textCenter(b, f, label, r.x + r.width / 2f, r.y + r.height / 2f,
                selected ? Theme.BG_BOTTOM : Theme.TEXT_DIM);
    }

    /**
     * Lays out equal-width chips across {@code [x, x+w]} and returns the count placed.
     * Chips that would overflow are omitted — callers should keep labels short.
     */
    public static int layoutChips(Rectangle[] dest, String[] labels, float x, float y, float w, float h) {
        int n = Math.min(dest.length, labels.length);
        if (n == 0) return 0;
        float gap = Theme.SPACE_SM;
        float chipW = (w - gap * (n - 1)) / n;
        if (chipW < 56f) {
            // Too tight — pack fewer by growing chip width to a readable floor.
            n = Math.max(1, (int) ((w + gap) / (56f + gap)));
            chipW = (w - gap * (n - 1)) / n;
        }
        for (int i = 0; i < n; i++) {
            if (dest[i] == null) dest[i] = new Rectangle();
            dest[i].set(x + i * (chipW + gap), y, chipW, h);
        }
        return n;
    }

    /** Title + optional one-line subtitle for tool screens. */
    public static void drawTitle(SpriteBatch b, BitmapFont titleFont, BitmapFont subFont,
                                 float vw, float vh, String title, String subtitle) {
        Ui.textCenter(b, titleFont, title, vw / 2f, vh - 48f, Theme.TEXT);
        if (subtitle != null && !subtitle.isBlank()) {
            Ui.textCenter(b, subFont, subtitle, vw / 2f, vh - 84f, Theme.TEXT_DIM);
        }
    }

    /** Hairline rule under the title band — separates chrome from content without a card. */
    public static void titleRule(ShapeRenderer s, float vw, float y) {
        s.setColor(Theme.RULE);
        s.rect(Theme.SPACE_XL, y, vw - 2f * Theme.SPACE_XL, 1.5f);
    }
}
