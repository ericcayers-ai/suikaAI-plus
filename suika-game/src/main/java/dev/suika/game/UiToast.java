package dev.suika.game;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * Unified toast / transient-message queue. Replaces the ad-hoc
 * {@code note + noteTimer} pairs scattered across Playground and Control Center.
 */
public final class UiToast {

    public enum Tone { INFO, SUCCESS, WARNING, ERROR }

    private String message = "";
    private float remaining;
    private Tone tone = Tone.INFO;

    public void show(String msg) { show(msg, Tone.INFO, Theme.TOAST_SECONDS); }

    public void show(String msg, Tone tone) { show(msg, tone, Theme.TOAST_SECONDS); }

    public void show(String msg, Tone tone, float seconds) {
        if (msg == null || msg.isBlank()) return;
        this.message = msg;
        this.tone = tone == null ? Tone.INFO : tone;
        this.remaining = Math.max(0.5f, seconds);
    }

    public void clear() { message = ""; remaining = 0f; }

    public boolean visible() { return remaining > 0f && !message.isBlank(); }

    public String message() { return message; }

    public void tick(float dt) {
        if (remaining > 0f) remaining -= dt;
        if (remaining <= 0f) { remaining = 0f; message = ""; }
    }

    private Color accent() {
        return switch (tone) {
            case SUCCESS -> Theme.SUCCESS;
            case WARNING -> Theme.WARNING;
            case ERROR   -> Theme.ERROR;
            case INFO    -> Theme.INFO;
        };
    }

    /** Shape pass — call inside a Filled ShapeRenderer begin/end. */
    public void drawShapes(ShapeRenderer s, float vw, float y) {
        if (!visible()) return;
        float w = Math.min(vw - 48f, 560f);
        float h = 48f;
        Ui.toastShell(s, vw / 2f, y, w, h, accent());
    }

    /** Text pass — call inside an active SpriteBatch. */
    public void drawText(SpriteBatch b, BitmapFont font, float vw, float y) {
        if (!visible()) return;
        Ui.textCenter(b, font, message, vw / 2f, y + 24f, Theme.TEXT);
    }
}
