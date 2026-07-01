package dev.suika.game.rtlab;

import static org.lwjgl.vulkan.VK10.VK_SUCCESS;

/** Throws with a readable message on any non-VK_SUCCESS result — every raw Vulkan call
 *  in this package returns an int error code instead of throwing, so this is the one
 *  place that turns "silently wrong" into "loud and immediate". */
final class VkCheck {
    private VkCheck() {}

    static void ok(int result, String what) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException("Vulkan call failed (" + what + "): VkResult=" + result);
        }
    }
}
