package dev.suika.env;

/** The three observation modes described in ROADMAP §III.1. */
public enum ObservationMode {
    /** Raw pixel render (84×84 by default, optional frame-stack). Unsupervised / vision. */
    PIXELS,
    /** Full symbolic state: per-fruit x,y,vx,vy,tier + globals. Supervised. */
    STATE,
    /** Rasterised multi-channel heatmap — one channel per fruit tier. Hybrid. */
    HYBRID
}
