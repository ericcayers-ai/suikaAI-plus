package dev.suika.game;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;

/**
 * Persistent experiment-status rail shared by Playground and Control Center.
 * Compact strip: technique · preset · health · one actionable detail line.
 */
public final class ExperimentStatusRail {

    public final Rectangle bounds = new Rectangle();

    public void layout(float x, float y, float w, float h) {
        bounds.set(x, y, w, Math.max(h, Theme.MIN_TARGET));
    }

    public void drawShapes(ShapeRenderer s, ExperimentStatus st) {
        Color edge = switch (st.health) {
            case IDLE -> Theme.PANEL_EDGE;
            case READY -> Theme.ACCENT_2;
            case RUNNING -> Theme.ACCENT_BLUE;
            case WARNING -> Theme.GOLD;
            case ERROR -> Theme.ACCENT;
        };
        Ui.panel(s, bounds.x, bounds.y, bounds.width, bounds.height,
                Theme.RADIUS_MD, Theme.PANEL_DEEP, edge);
        s.setColor(edge);
        Ui.fillRoundRect(s, bounds.x, bounds.y, 5f, bounds.height, 2f);
    }

    public void drawText(SpriteBatch b, BitmapFont med, BitmapFont small, ExperimentStatus st) {
        float left = bounds.x + 16f;
        float top = bounds.y + bounds.height - 18f;
        Ui.text(b, med, st.techniqueLabel, left, top, Theme.TEXT);
        Ui.textRight(b, small, st.runLabel,
                bounds.x + bounds.width - 14f, top, healthColor(st.health));
        Ui.text(b, small,
                st.presetLabel + "  ·  " + st.hardwareLabel + "  ·  " + st.pythonLabel,
                left, top - 22f, Theme.TEXT_DIM);
        if (!st.detail.isBlank())
            Ui.text(b, small, st.detail, left, bounds.y + 14f, Theme.TEXT_FAINT);
    }

    private static Color healthColor(ExperimentStatus.Health h) {
        return switch (h) {
            case IDLE -> Theme.TEXT_DIM;
            case READY -> Theme.ACCENT_2;
            case RUNNING -> Theme.ACCENT_BLUE;
            case WARNING -> Theme.GOLD;
            case ERROR -> Theme.ACCENT;
        };
    }
}
