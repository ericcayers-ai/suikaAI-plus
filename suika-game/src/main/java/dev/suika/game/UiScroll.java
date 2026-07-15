package dev.suika.game;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Rectangle;

/**
 * Scroll-region state for immediate-mode lists (Playground technique matrix,
 * Settings rows, Control Center diagnostics panel). Wheel and keyboard share one
 * clamp path so both feel consistent.
 */
public final class UiScroll {

    public float offset;
    public float contentHeight;
    public float viewHeight;

    public UiScroll() {}

    public UiScroll(float contentHeight, float viewHeight) {
        this.contentHeight = contentHeight;
        this.viewHeight = viewHeight;
    }

    public float maxOffset() {
        return Math.max(0f, contentHeight - viewHeight);
    }

    public void clamp() {
        offset = MathUtils.clamp(offset, 0f, maxOffset());
    }

    /** Mouse-wheel delta (LibGDX: positive = scroll down / reveal lower content). */
    public void wheel(float amountY) {
        offset += amountY * Theme.SCROLL_STEP;
        clamp();
    }

    public void line(int dir) {
        offset += dir * Theme.SCROLL_STEP;
        clamp();
    }

    public void page(int dir) {
        offset += dir * Theme.SCROLL_PAGE;
        clamp();
    }

    public void home() { offset = 0f; }
    public void end()  { offset = maxOffset(); }

    /**
     * Translates a LibGDX key code into a scroll action. Returns true when handled.
     * Keys: Up/Down, PageUp/PageDown, Home/End.
     */
    public boolean key(int keycode) {
        return switch (keycode) {
            case com.badlogic.gdx.Input.Keys.UP        -> { line(-1); yield true; }
            case com.badlogic.gdx.Input.Keys.DOWN      -> { line(+1); yield true; }
            case com.badlogic.gdx.Input.Keys.PAGE_UP   -> { page(-1); yield true; }
            case com.badlogic.gdx.Input.Keys.PAGE_DOWN -> { page(+1); yield true; }
            case com.badlogic.gdx.Input.Keys.HOME      -> { home(); yield true; }
            case com.badlogic.gdx.Input.Keys.END       -> { end(); yield true; }
            default -> false;
        };
    }

    /** True when a world-y at the top of an item is inside the visible band. */
    public boolean visible(float itemTop, float itemHeight, float listTop, float listBot) {
        float top = listTop + offset - (listTop - itemTop); // unused helper kept simple:
        // Prefer the caller-specific cardTop math; this variant checks a hit rect:
        return itemTop > listBot && itemTop - itemHeight <= listTop;
    }

    /** Scissor-friendly: whether {@code r} intersects the [listBot, listTop] band. */
    public static boolean intersectsBand(Rectangle r, float listTop, float listBot) {
        return r.y + r.height > listBot && r.y < listTop;
    }
}
