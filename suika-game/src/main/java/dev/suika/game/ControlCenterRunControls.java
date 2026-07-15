package dev.suika.game;

import com.badlogic.gdx.math.Rectangle;

/**
 * Control-bar button layout for {@link ControlCenterScreen} (portrait / landscape).
 */
public final class ControlCenterRunControls {

    private ControlCenterRunControls() {}

    public static void layoutPortrait(Rectangle back, Rectangle pause, Rectangle slow,
                                      Rectangle fast, Rectangle swap, Rectangle slots,
                                      Rectangle restart) {
        back.set(20, 16, 100, 46);
        pause.set(128, 16, 100, 46);
        slow.set(240, 16, Theme.MIN_TARGET, 46);
        fast.set(344, 16, Theme.MIN_TARGET, 46);
        swap.set(400, 16, 86, 46);
        slots.set(492, 16, 74, 46);
        restart.set(574, 16, 122, 46);
        for (Rectangle r : new Rectangle[]{back, pause, slow, fast, swap, slots, restart})
            Ui.ensureMinTarget(r);
    }

    public static void layoutLandscape(Rectangle back, Rectangle pause, Rectangle slow,
                                       Rectangle fast, Rectangle swap, Rectangle slots,
                                       Rectangle restart) {
        back.set(24, 16, 100, 44);
        pause.set(132, 16, 100, 44);
        slow.set(244, 16, Theme.MIN_TARGET, 44);
        fast.set(348, 16, Theme.MIN_TARGET, 44);
        swap.set(404, 16, 86, 44);
        slots.set(496, 16, 92, 44);
        restart.set(1138, 16, 118, 44);
        for (Rectangle r : new Rectangle[]{back, pause, slow, fast, swap, slots, restart})
            Ui.ensureMinTarget(r);
    }
}
