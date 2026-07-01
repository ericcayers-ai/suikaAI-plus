package dev.suika.game.rtlab;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;

import static dev.suika.game.rtlab.VkCheck.ok;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK12.*;

/**
 * A sampled 2D texture for the RT pipeline: decodes a bundled JPG/PNG resource with
 * {@link ImageIO} (no extra native dependency — these load once at startup, not per
 * frame), uploads it through a staging buffer, and generates a full mip chain with
 * {@code vkCmdBlitImage}. Mips matter here: the wood table plane spans from the very
 * front of the view to behind the jar, so without minification mips its far half
 * shimmers with aliasing that no denoiser pass can fully hide.
 */
public final class RtTexture implements AutoCloseable {

    public final long image;
    public final long memory;
    public final long view;
    public final long sampler;

    private final VkDevice device;

    public RtTexture(VkPhysicalDevice pd, VkDevice device, long commandPool, VkQueue queue, String resourcePath) {
        this.device = device;

        BufferedImage img = decode(resourcePath);
        int width = img.getWidth(), height = img.getHeight();
        int mipLevels = 1 + (int) (Math.log(Math.max(width, height)) / Math.log(2));

        ByteBuffer rgba = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            img.getRGB(0, y, width, 1, row, 0, width);
            for (int x = 0; x < width; x++) {
                int argb = row[x];
                rgba.put((byte) ((argb >> 16) & 0xFF));
                rgba.put((byte) ((argb >> 8) & 0xFF));
                rgba.put((byte) (argb & 0xFF));
                rgba.put((byte) ((argb >> 24) & 0xFF));
            }
        }
        rgba.flip();

        try (MemoryStack stack = stackPush()) {
            // UNORM for every map (not SRGB for albedo): the shader linearises albedo
            // itself with pow(2.2) so all maps share one format/one code path, and the
            // data maps (normal/roughness) must NOT be gamma-decoded by the sampler.
            int format = VK_FORMAT_R8G8B8A8_UNORM;

            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(format)
                    .extent(e -> e.width(width).height(height).depth(1))
                    .mipLevels(mipLevels)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    // TRANSFER_SRC too: each mip level is blitted FROM the previous one.
                    .usage(VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            LongBuffer pImage = stack.mallocLong(1);
            ok(vkCreateImage(device, imageInfo, null, pImage), "vkCreateImage (texture " + resourcePath + ")");
            this.image = pImage.get(0);

            VkMemoryRequirements memReq = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(device, image, memReq);
            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memReq.size())
                    .memoryTypeIndex(RtBuffer.findMemoryType(pd, memReq.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            LongBuffer pMemory = stack.mallocLong(1);
            ok(vkAllocateMemory(device, allocInfo, null, pMemory), "vkAllocateMemory (texture)");
            this.memory = pMemory.get(0);
            ok(vkBindImageMemory(device, image, memory, 0), "vkBindImageMemory (texture)");

            try (RtBuffer staging = new RtBuffer(pd, device, (long) width * height * 4,
                    VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
                staging.uploadHostVisible(rgba);
                OneShotCommands.submit(device, commandPool, queue, cmd ->
                        uploadAndGenerateMips(cmd, staging.buffer, width, height, mipLevels));
            }

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(format)
                    .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0).levelCount(mipLevels).baseArrayLayer(0).layerCount(1));
            LongBuffer pView = stack.mallocLong(1);
            ok(vkCreateImageView(device, viewInfo, null, pView), "vkCreateImageView (texture)");
            this.view = pView.get(0);

            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(VK_FILTER_LINEAR)
                    .minFilter(VK_FILTER_LINEAR)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .minLod(0f)
                    .maxLod(mipLevels);
            LongBuffer pSampler = stack.mallocLong(1);
            ok(vkCreateSampler(device, samplerInfo, null, pSampler), "vkCreateSampler (texture)");
            this.sampler = pSampler.get(0);
        }
    }

    private static BufferedImage decode(String resourcePath) {
        try (InputStream in = RtTexture.class.getResourceAsStream(resourcePath)) {
            if (in == null) throw new IOException("texture resource not found: " + resourcePath);
            BufferedImage img = ImageIO.read(in);
            if (img == null) throw new IOException("ImageIO could not decode: " + resourcePath);
            return img;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load RT texture " + resourcePath, e);
        }
    }

    /** Copies the staging buffer into mip 0, then blits each mip from the previous
     *  one, ending with the whole chain in SHADER_READ_ONLY_OPTIMAL. */
    private void uploadAndGenerateMips(VkCommandBuffer cmd, long stagingBuffer, int width, int height, int mipLevels) {
        try (MemoryStack stack = stackPush()) {
            // all mips -> TRANSFER_DST for the initial copy
            barrier(stack, cmd, 0, mipLevels, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
            region.get(0).imageSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1));
            region.get(0).imageOffset(o -> o.set(0, 0, 0));
            region.get(0).imageExtent(e -> e.width(width).height(height).depth(1));
            vkCmdCopyBufferToImage(cmd, stagingBuffer, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);

            int mipW = width, mipH = height;
            for (int level = 1; level < mipLevels; level++) {
                // previous level: DST -> SRC so this level can blit from it
                barrier(stack, cmd, level - 1, 1, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

                final int lvl = level, sw = mipW, sh = mipH;
                final int nextW = Math.max(1, mipW / 2), nextH = Math.max(1, mipH / 2);
                VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
                blit.get(0).srcSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(lvl - 1).baseArrayLayer(0).layerCount(1));
                blit.get(0).srcOffsets(0, o -> o.set(0, 0, 0));
                blit.get(0).srcOffsets(1, o -> o.set(sw, sh, 1));
                blit.get(0).dstSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(lvl).baseArrayLayer(0).layerCount(1));
                blit.get(0).dstOffsets(0, o -> o.set(0, 0, 0));
                blit.get(0).dstOffsets(1, o -> o.set(nextW, nextH, 1));
                vkCmdBlitImage(cmd, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, VK_FILTER_LINEAR);

                // previous level is final now: SRC -> SHADER_READ
                barrier(stack, cmd, level - 1, 1, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_SHADER_READ_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
                mipW = nextW;
                mipH = nextH;
            }
            // last level was only ever a blit DST
            barrier(stack, cmd, mipLevels - 1, 1, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
        }
    }

    private void barrier(MemoryStack stack, VkCommandBuffer cmd, int baseMip, int mipCount,
                         int oldLayout, int newLayout, int srcAccess, int dstAccess, int srcStage, int dstStage) {
        VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .oldLayout(oldLayout).newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image).srcAccessMask(srcAccess).dstAccessMask(dstAccess);
        b.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(baseMip).levelCount(mipCount).baseArrayLayer(0).layerCount(1);
        vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, b);
    }

    @Override
    public void close() {
        vkDestroySampler(device, sampler, null);
        vkDestroyImageView(device, view, null);
        vkDestroyImage(device, image, null);
        vkFreeMemory(device, memory, null);
    }
}
