package dev.suika.game;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;

import java.util.ArrayList;
import java.util.List;

/**
 * Keyboard focus order for immediate-mode controls. Screens register hit targets
 * each frame (or on layout), advance with Tab / Shift+Tab, and draw a focus ring
 * on the active index.
 */
public final class UiFocus {

    private final List<Rectangle> targets = new ArrayList<>();
    private int index = -1;

    public void clear() { targets.clear(); }

    public void add(Rectangle r) {
        if (r != null) targets.add(r);
    }

    public int size() { return targets.size(); }

    public int index() { return index; }

    public Rectangle current() {
        if (index < 0 || index >= targets.size()) return null;
        return targets.get(index);
    }

    public void setIndex(int i) {
        if (targets.isEmpty()) { index = -1; return; }
        index = Math.floorMod(i, targets.size());
    }

    public void next() { setIndex(index < 0 ? 0 : index + 1); }
    public void prev() { setIndex(index < 0 ? targets.size() - 1 : index - 1); }

    /** Tab / Shift+Tab. Returns true when handled. */
    public boolean key(int keycode, boolean shift) {
        if (keycode != com.badlogic.gdx.Input.Keys.TAB || targets.isEmpty()) return false;
        if (shift) prev(); else next();
        return true;
    }

    public boolean isFocused(Rectangle r) {
        Rectangle cur = current();
        return cur != null && cur == r;
    }

    public void drawRing(ShapeRenderer s) {
        Rectangle r = current();
        if (r != null) Ui.focusOutline(s, r.x, r.y, r.width, r.height);
    }

    /**
     * Tiny helper for screens that want a default "first focusable is the primary CTA".
     */
    public void ensureStarted() {
        if (index < 0 && !targets.isEmpty()) index = 0;
    }
}
