package dev.suika.game.rtlab;

import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static dev.suika.game.rtlab.VkCheck.ok;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK12.*;

/** Stage B smoke test: open a real window, build a swapchain, and present a
 *  shifting clear color for a couple of seconds via vkCmdClearColorImage (no
 *  render pass) — validates the whole acquire/submit/present loop before any
 *  ray-tracing-specific code is added on top of it. Not wired into the shipped app. */
public final class RtPresentLoopTest {
    public static void main(String[] args) throws InterruptedException {
        Configuration.STACK_SIZE.set(RtContext.STACK_SIZE_KB);
        if (!glfwInit()) throw new IllegalStateException("glfwInit failed");

        try (RtContext ctx = new RtContext(GLFWVulkan.glfwGetRequiredInstanceExtensions());
             RtWindow window = new RtWindow(ctx.instance, 960, 720, "Suika RT Lab (present-loop smoke test)")) {

            System.out.println("device = " + ctx.deviceName);
            RtSwapchain swap = new RtSwapchain(ctx.physicalDevice, ctx.device, window.surface, 960, 720);
            System.out.println("swapchain: " + swap.width + "x" + swap.height + ", " + swap.images.length + " images, format=" + swap.format);

            long commandPool = createCommandPool(ctx.device, ctx.graphicsQueueFamily);
            VkCommandBuffer cmd = allocateCommandBuffer(ctx.device, commandPool);
            long imageAvailable = createSemaphore(ctx.device);
            long renderFinished = createSemaphore(ctx.device);
            long inFlight = createFence(ctx.device);

            int frame = 0;
            long t0 = System.nanoTime();
            while (!window.shouldClose() && frame < 180) {
                window.pollEvents();
                renderFrame(ctx, swap, cmd, imageAvailable, renderFinished, inFlight, frame);
                frame++;
            }
            vkDeviceWaitIdle(ctx.device);
            double secs = (System.nanoTime() - t0) / 1e9;
            System.out.println("SUCCESS: presented " + frame + " frames in " + String.format("%.2f", secs) + "s");

            vkDestroySemaphore(ctx.device, imageAvailable, null);
            vkDestroySemaphore(ctx.device, renderFinished, null);
            vkDestroyFence(ctx.device, inFlight, null);
            vkDestroyCommandPool(ctx.device, commandPool, null);
            swap.close();
        }
        glfwTerminate();
    }

    private static long createCommandPool(VkDevice device, int queueFamily) {
        try (MemoryStack stack = stackPush()) {
            VkCommandPoolCreateInfo info = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(queueFamily);
            LongBuffer pPool = stack.mallocLong(1);
            ok(vkCreateCommandPool(device, info, null, pPool), "vkCreateCommandPool");
            return pPool.get(0);
        }
    }

    private static VkCommandBuffer allocateCommandBuffer(VkDevice device, long pool) {
        try (MemoryStack stack = stackPush()) {
            VkCommandBufferAllocateInfo info = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(pool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            org.lwjgl.PointerBuffer pBuffers = stack.mallocPointer(1);
            ok(vkAllocateCommandBuffers(device, info, pBuffers), "vkAllocateCommandBuffers");
            return new VkCommandBuffer(pBuffers.get(0), device);
        }
    }

    private static long createSemaphore(VkDevice device) {
        try (MemoryStack stack = stackPush()) {
            VkSemaphoreCreateInfo info = VkSemaphoreCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
            LongBuffer p = stack.mallocLong(1);
            ok(vkCreateSemaphore(device, info, null, p), "vkCreateSemaphore");
            return p.get(0);
        }
    }

    private static long createFence(VkDevice device) {
        try (MemoryStack stack = stackPush()) {
            VkFenceCreateInfo info = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                    .flags(VK_FENCE_CREATE_SIGNALED_BIT);
            LongBuffer p = stack.mallocLong(1);
            ok(vkCreateFence(device, info, null, p), "vkCreateFence");
            return p.get(0);
        }
    }

    private static void renderFrame(RtContext ctx, RtSwapchain swap, VkCommandBuffer cmd,
                                     long imageAvailable, long renderFinished, long inFlight, int frame) {
        try (MemoryStack stack = stackPush()) {
            ok(vkWaitForFences(ctx.device, inFlight, true, Long.MAX_VALUE), "vkWaitForFences");
            ok(vkResetFences(ctx.device, inFlight), "vkResetFences");

            java.nio.IntBuffer pIndex = stack.mallocInt(1);
            int acquire = vkAcquireNextImageKHR(ctx.device, swap.handle, Long.MAX_VALUE, imageAvailable, VK_NULL_HANDLE, pIndex);
            ok(acquire, "vkAcquireNextImageKHR");
            int imageIndex = pIndex.get(0);

            ok(vkResetCommandBuffer(cmd, 0), "vkResetCommandBuffer");
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            ok(vkBeginCommandBuffer(cmd, beginInfo), "vkBeginCommandBuffer");

            long image = swap.images[imageIndex];
            transition(stack, cmd, image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT);

            VkClearColorValue clear = VkClearColorValue.calloc(stack);
            float t = (frame % 180) / 180f;
            clear.float32(0, 0.05f + 0.1f * t).float32(1, 0.08f).float32(2, 0.15f + 0.2f * (1 - t)).float32(3, 1f);
            VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack)
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkImageSubresourceRange.Buffer ranges = VkImageSubresourceRange.calloc(1, stack);
            ranges.put(0, range);
            vkCmdClearColorImage(cmd, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, clear, ranges);

            transition(stack, cmd, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_ACCESS_TRANSFER_WRITE_BIT, 0);
            ok(vkEndCommandBuffer(cmd), "vkEndCommandBuffer");

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .waitSemaphoreCount(1)
                    .pWaitSemaphores(stack.longs(imageAvailable))
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_TRANSFER_BIT))
                    .pCommandBuffers(stack.pointers(cmd))
                    .pSignalSemaphores(stack.longs(renderFinished));
            ok(vkQueueSubmit(ctx.graphicsQueue, submitInfo, inFlight), "vkQueueSubmit");

            VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                    .pWaitSemaphores(stack.longs(renderFinished))
                    .swapchainCount(1)
                    .pSwapchains(stack.longs(swap.handle))
                    .pImageIndices(stack.ints(imageIndex));
            ok(vkQueuePresentKHR(ctx.graphicsQueue, presentInfo), "vkQueuePresentKHR");
        }
    }

    private static void transition(MemoryStack stack, VkCommandBuffer cmd, long image,
                                    int oldLayout, int newLayout, int srcAccess, int dstAccess) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image)
                .srcAccessMask(srcAccess)
                .dstAccessMask(dstAccess);
        barrier.get(0).subresourceRange()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                0, null, null, barrier);
    }
}
