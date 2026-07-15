package dev.suika.game;

/**
 * Layout anchors and responsive profile helpers for portrait / landscape virtual
 * canvases. Screens position primary chrome relative to these rather than magic
 * numbers where practical.
 */
public final class UiLayout {

    public enum Profile { PORTRAIT, LANDSCAPE }

    private UiLayout() {}

    public static Profile profile(boolean landscape) {
        return landscape ? Profile.LANDSCAPE : Profile.PORTRAIT;
    }

    public static Profile profileForWindow(int w, int h) {
        return profile(Theme.isLandscapeWindow(w, h));
    }

    public static float width(Profile p)  { return p == Profile.LANDSCAPE ? Theme.VW_L : Theme.VW; }
    public static float height(Profile p) { return p == Profile.LANDSCAPE ? Theme.VH_L : Theme.VH; }

    /** Bottom safe band reserved for the primary action bar. */
    public static float bottomBarHeight(Profile p) {
        return p == Profile.LANDSCAPE ? 68f : 80f;
    }

    /** Horizontal content margin. */
    public static float marginX(Profile p) {
        return p == Profile.LANDSCAPE ? Theme.SPACE_LG : Theme.SPACE_XL;
    }

    /** Top content inset below a title block. */
    public static float contentTop(Profile p) {
        return height(p) - (p == Profile.LANDSCAPE ? 96f : 160f);
    }

    /** Centre of the virtual canvas. */
    public static float cx(Profile p) { return width(p) / 2f; }
    public static float cy(Profile p) { return height(p) / 2f; }

    /** Modal origin so a card of {@code mw}×{@code mh} is centred. */
    public static float modalX(Profile p, float mw) { return (width(p) - mw) / 2f; }
    public static float modalY(Profile p, float mh) { return (height(p) - mh) / 2f; }
}
