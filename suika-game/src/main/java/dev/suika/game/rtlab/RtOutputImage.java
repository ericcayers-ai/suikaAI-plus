package dev.suika.game.rtlab;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static dev.suika.game.rtlab.VkCheck.ok;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
import static org.lwjgl.vulkan.VK12.*;

/** The image the ray generation shader writes into via imageStore — traced once per
 *  frame, then blitted onto whichever swapchain image was acquired for presentation.
 *  RGBA32F so shading math (and later, temporal-accumulation blending) has real
 *  precision instead of clamping to 8-bit per channel before the denoiser ever runs. */
public final class RtOutputImage implements AutoCloseable {

    public static final int FORMAT = VK_FORMAT_R32G32B32A32_SFLOAT;

    public final long image;
    public final long memory;
    public final long view;
    public final int width, height;

    private final VkDevice device;

    public RtOutputImage(VkPhysicalDevice pd, VkDevice device, long commandPool, VkQueue queue, int width, int height) {
        this.device = device;
        this.width = width;
        this.height = height;
        try (MemoryStack stack = stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(FORMAT)
                    .extent(e -> e.width(width).height(height).depth(1))
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);

            LongBuffer pImage = stack.mallocLong(1);
            ok(vkCreateImage(device, imageInfo, null, pImage), "vkCreateImage (RT output)");
            this.image = pImage.get(0);

            VkMemoryRequirements memReq = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(device, image, memReq);
            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memReq.size())
                    .memoryTypeIndex(RtBuffer.findMemoryType(pd, memReq.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            LongBuffer pMemory = stack.mallocLong(1);
            ok(vkAllocateMemory(device, allocInfo, null, pMemory), "vkAllocateMemory (RT output)");
            this.memory = pMemory.get(0);
            ok(vkBindImageMemory(device, image, memory, 0), "vkBindImageMemory (RT output)");

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(FORMAT)
                    .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
            LongBuffer pView = stack.mallocLong(1);
            ok(vkCreateImageView(device, viewInfo, null, pView), "vkCreateImageView (RT output)");
            this.view = pView.get(0);

            // GENERAL is the one layout imageStore (in the raygen shader) is guaranteed
            // to work from — transition once up front rather than every frame, since we
            // never need it in another layout except transiently during the blit-out.
            OneShotCommands.submit(device, commandPool, queue, cmd -> {
                VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                        .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                        .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                        .newLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(image)
                        .dstAccessMask(VK_ACCESS_SHADER_WRITE_BIT);
                barrier.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                        0, null, null, barrier);
            });
        }
    }

    @Override
    public void close() {
        vkDestroyImageView(device, view, null);
        vkDestroyImage(device, image, null);
        vkFreeMemory(device, memory, null);
    }
}
