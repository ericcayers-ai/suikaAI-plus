package dev.suika.game.rtlab;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static dev.suika.game.rtlab.VkCheck.ok;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Swapchain used purely as a present target: the RT pipeline traces into its own
 * offscreen storage image (see {@link RtOutputImage}), which gets blitted into
 * whichever swapchain image is acquired each frame. Keeping the swapchain itself
 * "dumb" (TRANSFER_DST only, no render pass) sidesteps needing a format that also
 * supports STORAGE usage, which not every present-capable surface format does.
 */
public final class RtSwapchain implements AutoCloseable {

    public final long handle;
    public final int format;
    public final int width, height;
    public final long[] images;
    public final long[] imageViews;

    private final VkDevice device;

    public RtSwapchain(VkPhysicalDevice physicalDevice, VkDevice device, long surface, int desiredW, int desiredH) {
        this.device = device;
        try (MemoryStack stack = stackPush()) {
            VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.malloc(stack);
            ok(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surface, caps), "surface capabilities");

            VkSurfaceFormatKHR.Buffer formats = querySurfaceFormats(stack, physicalDevice, surface);
            VkSurfaceFormatKHR chosenFormat = pickFormat(formats);
            int presentMode = pickPresentMode(stack, physicalDevice, surface);

            int w = clamp(desiredW, caps.minImageExtent().width(), caps.maxImageExtent().width());
            int h = clamp(desiredH, caps.minImageExtent().height(), caps.maxImageExtent().height());
            this.width = w;
            this.height = h;
            this.format = chosenFormat.format();

            int imageCount = caps.minImageCount() + 1;
            if (caps.maxImageCount() > 0) imageCount = Math.min(imageCount, caps.maxImageCount());

            VkSwapchainCreateInfoKHR createInfo = VkSwapchainCreateInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                    .surface(surface)
                    .minImageCount(imageCount)
                    .imageFormat(chosenFormat.format())
                    .imageColorSpace(chosenFormat.colorSpace())
                    .imageExtent(e -> e.width(w).height(h))
                    .imageArrayLayers(1)
                    .imageUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .preTransform(caps.currentTransform())
                    .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                    .presentMode(presentMode)
                    .clipped(true)
                    .oldSwapchain(VK_NULL_HANDLE);

            LongBuffer pSwapchain = stack.mallocLong(1);
            ok(vkCreateSwapchainKHR(device, createInfo, null, pSwapchain), "vkCreateSwapchainKHR");
            this.handle = pSwapchain.get(0);

            IntBuffer count = stack.mallocInt(1);
            ok(vkGetSwapchainImagesKHR(device, handle, count, null), "get swapchain images (count)");
            LongBuffer pImages = stack.mallocLong(count.get(0));
            ok(vkGetSwapchainImagesKHR(device, handle, count, pImages), "get swapchain images");
            this.images = new long[count.get(0)];
            pImages.get(this.images);

            this.imageViews = new long[images.length];
            for (int i = 0; i < images.length; i++) imageViews[i] = createView(stack, images[i], chosenFormat.format());
        }
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static VkSurfaceFormatKHR.Buffer querySurfaceFormats(MemoryStack stack, VkPhysicalDevice pd, long surface) {
        IntBuffer count = stack.mallocInt(1);
        ok(vkGetPhysicalDeviceSurfaceFormatsKHR(pd, surface, count, null), "surface formats (count)");
        VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.malloc(count.get(0), stack);
        ok(vkGetPhysicalDeviceSurfaceFormatsKHR(pd, surface, count, formats), "surface formats");
        return formats;
    }

    private static VkSurfaceFormatKHR pickFormat(VkSurfaceFormatKHR.Buffer formats) {
        for (VkSurfaceFormatKHR f : formats) {
            if (f.format() == VK_FORMAT_B8G8R8A8_UNORM && f.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) return f;
        }
        return formats.get(0);
    }

    private static int pickPresentMode(MemoryStack stack, VkPhysicalDevice pd, long surface) {
        IntBuffer count = stack.mallocInt(1);
        ok(vkGetPhysicalDeviceSurfacePresentModesKHR(pd, surface, count, null), "present modes (count)");
        IntBuffer modes = stack.mallocInt(count.get(0));
        ok(vkGetPhysicalDeviceSurfacePresentModesKHR(pd, surface, count, modes), "present modes");
        for (int i = 0; i < modes.remaining(); i++) if (modes.get(i) == VK_PRESENT_MODE_MAILBOX_KHR) return VK_PRESENT_MODE_MAILBOX_KHR;
        return VK_PRESENT_MODE_FIFO_KHR; // always guaranteed available
    }

    private long createView(MemoryStack stack, long image, int format) {
        VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .image(image)
                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                .format(format)
                .subresourceRange(r -> r
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1)
                        .baseArrayLayer(0).layerCount(1));
        LongBuffer pView = stack.mallocLong(1);
        ok(vkCreateImageView(device, viewInfo, null, pView), "vkCreateImageView");
        return pView.get(0);
    }

    @Override
    public void close() {
        for (long v : imageViews) vkDestroyImageView(device, v, null);
        vkDestroySwapchainKHR(device, handle, null);
    }
}
