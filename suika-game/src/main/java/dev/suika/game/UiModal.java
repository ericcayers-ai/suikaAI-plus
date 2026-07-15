package dev.suika.game;

/**
 * Stack-style modal ownership for immediate-mode screens. Only the top modal
 * receives clicks; Esc dismisses it. Screens keep drawing — this just tracks
 * which layer is active so focus / input routing stays consistent.
 */
public final class UiModal {

    public enum Kind { NONE, INFO, HOTSWAP, SLOTS, CONFIRM, NUMERIC, CUSTOM }

    private Kind kind = Kind.NONE;
    private Object payload;

    public Kind kind() { return kind; }
    public Object payload() { return payload; }
    public boolean active() { return kind != Kind.NONE; }

    public void open(Kind k) { open(k, null); }

    public void open(Kind k, Object payload) {
        this.kind = k == null ? Kind.NONE : k;
        this.payload = payload;
    }

    public void close() {
        kind = Kind.NONE;
        payload = null;
    }

    /** Esc dismiss. Returns true when a modal was closed. */
    public boolean dismiss() {
        if (!active()) return false;
        close();
        return true;
    }

    /** True when input outside the modal card should be ignored. */
    public boolean ownsInput() { return active(); }
}
