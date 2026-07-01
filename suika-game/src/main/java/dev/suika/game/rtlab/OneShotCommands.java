package dev.suika.game.rtlab;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.function.Consumer;

import static dev.suika.game.rtlab.VkCheck.ok;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK12.*;

/** Runs a single command buffer to completion and waits — used for the one-time
 *  setup work (acceleration structure builds, buffer uploads) that doesn't need to
 *  be part of the steady-state per-frame render loop. Simplicity over throughput:
 *  every call blocks on a fresh fence rather than trying to batch or pipeline. */
final class OneShotCommands {
    private OneShotCommands() {}

    static void submit(VkDevice device, long commandPool, VkQueue queue, Consumer<VkCommandBuffer> recorder) {
        try (MemoryStack stack = stackPush()) {
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            PointerBuffer pCmd = stack.mallocPointer(1);
            ok(vkAllocateCommandBuffers(device, allocInfo, pCmd), "vkAllocateCommandBuffers (one-shot)");
            VkCommandBuffer cmd = new VkCommandBuffer(pCmd.get(0), device);

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            ok(vkBeginCommandBuffer(cmd, beginInfo), "vkBeginCommandBuffer (one-shot)");

            recorder.accept(cmd);

            ok(vkEndCommandBuffer(cmd), "vkEndCommandBuffer (one-shot)");

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(cmd));
            ok(vkQueueSubmit(queue, submitInfo, VK_NULL_HANDLE), "vkQueueSubmit (one-shot)");
            ok(vkQueueWaitIdle(queue), "vkQueueWaitIdle (one-shot)");

            vkFreeCommandBuffers(device, commandPool, cmd);
        }
    }
}
