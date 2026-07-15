package dev.suika.game;

import dev.suika.core.PhysicsConfig;

/**
 * Board-grid geometry extracted from {@link ControlCenterScreen}: free region and
 * multi-board placements. Pure layout math — no LibGDX draw calls.
 */
public final class ControlCenterBoardGrid {

    public static final float GRID_CELL_PAD = 10f;
    public static final float GRID_TAG_H = 46f;
    public static final float BOARD_GW =
            (float) (PhysicsConfig.CONTAINER_WIDTH + 2 * PhysicsConfig.WALL_THICKNESS);
    public static final float BOARD_GH =
            (float) (PhysicsConfig.CONTAINER_HEIGHT + PhysicsConfig.WALL_THICKNESS + 1.0);

    private ControlCenterBoardGrid() {}

    /**
     * Free screen area {@code {x, y, w, h}} not covered by the side panel or control bar.
     *
     * @param panelBounds landscape/portrait panel as {@code {x,y,w,h}}
     */
    public static float[] boardRegion(boolean landscape, float[] panelBounds) {
        if (landscape) {
            float left = panelBounds[0] + panelBounds[2] + 24f;
            return new float[]{ left, 78f, Theme.VW_L - left - 16f, Theme.VH_L - 78f - 12f };
        }
        return new float[]{ 10f, 78f, Theme.VW - 20f, 980f - 78f - 8f };
    }

    /**
     * Per-board transform {@code {ox, oy, scale}} tiling {@code n} boards into the region.
     * Rows pack from the top (see Control Center history for the header-gap fix).
     */
    public static float[][] placements(float[] region, int n) {
        int cols = Math.max(1, (int) Math.ceil(Math.sqrt(n)));
        int rows = (n + cols - 1) / cols;
        float pad = GRID_CELL_PAD, tag = GRID_TAG_H, topMargin = 16f;
        float cw = region[2] / cols, chFull = region[3] / rows;
        float availW = cw - 2 * pad, availHFull = chFull - 2 * pad - tag;
        float sc = Math.min(availW / BOARD_GW, availHFull / BOARD_GH);
        float boardPxH = (float) PhysicsConfig.CONTAINER_HEIGHT * sc;
        float rowPxH = tag + 2 * pad + boardPxH;
        float regionTop = region[1] + region[3] - topMargin;
        float[][] out = new float[n][3];
        for (int i = 0; i < n; i++) {
            int cxIdx = i % cols, cyIdx = i / cols;
            float cellX = region[0] + cxIdx * cw;
            float rowTop = regionTop - cyIdx * rowPxH;
            float ox = cellX + cw / 2f - 5f * sc;
            float oy = rowTop - tag - pad - boardPxH;
            out[i] = new float[]{ ox, oy, sc };
        }
        return out;
    }
}
