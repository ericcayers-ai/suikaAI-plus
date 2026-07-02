package dev.suika.game.rtlab;

import dev.suika.core.FruitTier;
import dev.suika.game.FruitColors;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;

import static dev.suika.game.rtlab.VkCheck.ok;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK12.*;

/**
 * The RT Lab's on-screen GUI: score, mode, next-fruit chip, control hints, and the
 * game-over banner — a real overlay in the window, replacing the old title-bar-text
 * HUD and the floating "next fruit" sphere that used to hover beside the jar.
 *
 * <p>Rendering crisp text through a raw Vulkan RT pipeline directly would mean
 * building a glyph atlas + quad rasteriser from scratch; instead the HUD is drawn
 * with Java2D (antialiased, any font) into a CPU image, uploaded to an RGBA8
 * storage image only when its content actually changes, and alpha-blended over the
 * present image by {@link RtHudCompositor}'s compute pass each frame. Redraws are
 * driven by state changes (score/next/game-over), so steady play costs one ~3 MB
 * staging copy per merge — nothing per frame.
 */
final class RtHud implements AutoCloseable {

    final long image;
    final long view;
    private final long memory;
    private final RtBuffer staging;
    private final BufferedImage canvas;
    private final ByteBuffer pixels;   // RGBA8, reused every redraw
    private final VkDevice device;
    private final int width, height;
    private boolean dirty = false;

    private static final Color PANEL   = new Color(10, 12, 20, 185);
    private static final Color TEXT    = new Color(235, 238, 245, 255);
    private static final Color DIM     = new Color(160, 166, 180, 255);
    private static final Color GOLD    = new Color(245, 195, 80, 255);
    private static final Color DANGER  = new Color(235, 90, 80, 255);
    private static final Color VIOLET  = new Color(150, 100, 220, 255);

    private final Font fontCaption = new Font(Font.SANS_SERIF, Font.BOLD, 20);
    private final Font fontBig     = new Font(Font.SANS_SERIF, Font.BOLD, 46);
    private final Font fontMed     = new Font(Font.SANS_SERIF, Font.BOLD, 30);
    private final Font fontSmall   = new Font(Font.SANS_SERIF, Font.PLAIN, 18);

    RtHud(VkPhysicalDevice pd, VkDevice device, long commandPool, VkQueue queue, int width, int height) {
        this.device = device;
        this.width = width;
        this.height = height;
        this.canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        this.pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());
        this.staging = new RtBuffer(pd, device, (long) width * height * 4,
                VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

        try (MemoryStack stack = stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(VK_FORMAT_R8G8B8A8_UNORM)
                    .extent(e -> e.width(width).height(height).depth(1))
                    .mipLevels(1).arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            LongBuffer pImage = stack.mallocLong(1);
            ok(vkCreateImage(device, imageInfo, null, pImage), "vkCreateImage (HUD)");
            this.image = pImage.get(0);

            VkMemoryRequirements memReq = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(device, image, memReq);
            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memReq.size())
                    .memoryTypeIndex(RtBuffer.findMemoryType(pd, memReq.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            LongBuffer pMemory = stack.mallocLong(1);
            ok(vkAllocateMemory(device, allocInfo, null, pMemory), "vkAllocateMemory (HUD)");
            this.memory = pMemory.get(0);
            ok(vkBindImageMemory(device, image, memory, 0), "vkBindImageMemory (HUD)");

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(VK_FORMAT_R8G8B8A8_UNORM)
                    .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
            LongBuffer pView = stack.mallocLong(1);
            ok(vkCreateImageView(device, viewInfo, null, pView), "vkCreateImageView (HUD)");
            this.view = pView.get(0);

            // Storage images must start in GENERAL — same pattern as RtOutputImage.
            // The upload path transitions to TRANSFER_DST and back per copy.
            OneShotCommands.submit(device, commandPool, queue, cmd -> {
                VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                        .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                        .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                        .newLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(image)
                        .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
                barrier.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        0, null, null, barrier);
            });
        }
    }

    /**
     * Redraws the overlay and stages it for upload. Call only when the shown state
     * actually changed, and only at a point where the previous frame's GPU work has
     * completed (in RtLabLauncher's loop: right after {@code scene.updateFrame},
     * whose one-shot TLAS build waits the queue idle) so the staging buffer is never
     * rewritten while an in-flight copy still reads it.
     */
    void draw(long score, FruitTier next, String modeName, String aiName, boolean gameOver, boolean use3d) {
        Graphics2D g = canvas.createGraphics();
        g.setComposite(java.awt.AlphaComposite.Clear);
        g.fillRect(0, 0, width, height);
        g.setComposite(java.awt.AlphaComposite.SrcOver);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // ---- top-left: score + mode ----
        g.setColor(PANEL);
        g.fillRoundRect(16, 16, 320, 128, 22, 22);
        g.setColor(DIM);
        g.setFont(fontCaption);
        g.drawString("SCORE", 38, 52);
        g.setColor(GOLD);
        g.setFont(fontBig);
        g.drawString(Long.toString(score), 38, 104);
        g.setColor(aiName != null ? VIOLET : DIM);
        g.setFont(fontSmall);
        g.drawString(aiName != null ? modeName + "  ·  AI: " + aiName : modeName, 38, 132);

        // ---- top-right: next-fruit chip ----
        int panelW = 168, px = width - 16 - panelW;
        g.setColor(PANEL);
        g.fillRoundRect(px, 16, panelW, 128, 22, 22);
        g.setColor(DIM);
        g.setFont(fontCaption);
        g.drawString("NEXT", px + 22, 52);
        var c = FruitColors.of(next);
        int cx = px + panelW / 2, cy = 96, r = 30;
        g.setColor(new Color(c.r, c.g, c.b, 1f));
        g.fillOval(cx - r, cy - r, 2 * r, 2 * r);
        g.setColor(new Color(0f, 0f, 0f, 0.45f));
        g.setStroke(new BasicStroke(3f));
        g.drawOval(cx - r, cy - r, 2 * r, 2 * r);
        g.setColor(TEXT);
        g.setFont(fontCaption);
        String tierNum = Integer.toString(next.tier);
        var fm = g.getFontMetrics();
        g.drawString(tierNum, cx - fm.stringWidth(tierNum) / 2, cy + fm.getAscent() / 2 - 2);

        // ---- bottom: control hints ----
        g.setFont(fontSmall);
        String hints = aiName != null
                ? (use3d ? "right-drag orbit · scroll zoom · R restart · D denoise · ESC close"
                         : "scroll zoom · R restart · D denoise · ESC close")
                : (use3d ? "click drop · right-drag orbit · scroll zoom · R restart · D denoise"
                         : "click drop · scroll zoom · R restart · D denoise");
        var fmS = g.getFontMetrics();
        int hw = fmS.stringWidth(hints);
        g.setColor(PANEL);
        g.fillRoundRect(width / 2 - hw / 2 - 16, height - 52, hw + 32, 36, 16, 16);
        g.setColor(DIM);
        g.drawString(hints, width / 2 - hw / 2, height - 28);

        // ---- game-over banner ----
        if (gameOver) {
            int bw = 460, bh = 170, bx = width / 2 - bw / 2, by = height / 2 - bh / 2;
            g.setColor(new Color(8, 9, 15, 225));
            g.fillRoundRect(bx, by, bw, bh, 26, 26);
            g.setColor(DANGER);
            g.setStroke(new BasicStroke(3f));
            g.drawRoundRect(bx, by, bw, bh, 26, 26);
            g.setFont(fontMed);
            var fmM = g.getFontMetrics();
            String over = "GAME OVER";
            g.drawString(over, width / 2 - fmM.stringWidth(over) / 2, by + 58);
            g.setColor(TEXT);
            g.setFont(fontCaption);
            var fmC = g.getFontMetrics();
            String fin = "final score  " + score;
            g.drawString(fin, width / 2 - fmC.stringWidth(fin) / 2, by + 98);
            g.setColor(DIM);
            g.setFont(fontSmall);
            String sub = aiName != null ? "next round starting…" : "press R to restart";
            var fmS2 = g.getFontMetrics();
            g.drawString(sub, width / 2 - fmS2.stringWidth(sub) / 2, by + 134);
        }
        g.dispose();

        // ARGB ints -> RGBA bytes for VK_FORMAT_R8G8B8A8_UNORM.
        int[] argb = ((DataBufferInt) canvas.getRaster().getDataBuffer()).getData();
        pixels.clear();
        for (int p : argb) {
            pixels.put((byte) (p >> 16)).put((byte) (p >> 8)).put((byte) p).put((byte) (p >>> 24));
        }
        pixels.flip();
        staging.uploadHostVisible(pixels);
        dirty = true;
    }

    /** Records the staged HUD upload into this frame's command buffer (no-op unless
     *  {@link #draw} ran since the last upload). */
    void recordUpload(VkCommandBuffer cmd) {
        if (!dirty) return;
        dirty = false;
        try (MemoryStack stack = stackPush()) {
            transition(stack, cmd, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
            region.get(0).imageSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1));
            region.get(0).imageOffset(o -> o.set(0, 0, 0));
            region.get(0).imageExtent(e -> e.width(width).height(height).depth(1));
            vkCmdCopyBufferToImage(cmd, staging.buffer, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
            transition(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
                    VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        }
    }

    private void transition(MemoryStack stack, VkCommandBuffer cmd, int oldLayout, int newLayout,
                            int srcAccess, int dstAccess, int srcStage, int dstStage) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .oldLayout(oldLayout).newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image).srcAccessMask(srcAccess).dstAccessMask(dstAccess);
        barrier.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, barrier);
    }

    @Override
    public void close() {
        vkDestroyImageView(device, view, null);
        vkDestroyImage(device, image, null);
        vkFreeMemory(device, memory, null);
        staging.close();
    }
}
