package dev.suika.game.rtlab;

import dev.suika.core.FruitTier;
import dev.suika.game.FruitColors;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.InputStream;
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

    // A restrained, high-contrast palette — one accent (gold) reserved for the score
    // and emphasis, a cooler violet only when an AI is driving, everything else a
    // graded scale of near-black/grey so the accents actually read as accents.
    private static final Color PANEL_TOP    = new Color(17, 19, 28, 205);
    private static final Color PANEL_BOTTOM = new Color(9, 10, 16, 205);
    private static final Color PANEL_EDGE   = new Color(255, 255, 255, 24);
    private static final Color TEXT    = new Color(240, 242, 248, 255);
    private static final Color DIM     = new Color(158, 164, 180, 255);
    private static final Color FAINT   = new Color(110, 116, 132, 255);
    private static final Color GOLD    = new Color(247, 197, 90, 255);
    private static final Color GOLD_DEEP = new Color(196, 146, 44, 255);
    private static final Color DANGER  = new Color(232, 96, 88, 255);
    private static final Color VIOLET  = new Color(168, 122, 235, 255);

    // The game's own bundled typeface (see SuikaGame.java's FreeType setup for the
    // libGDX side) loaded straight from the same TTF via AWT, instead of a generic
    // system sans-serif — the RT window's HUD now reads as the SAME app as the rest
    // of the game, not a bolted-on placeholder overlay. Falls back to SANS_SERIF if
    // the resource is ever missing so a font problem never takes the whole HUD down.
    private final Font fontCaption, fontBig, fontMed, fontSmall, fontTiny;

    private static Font loadBaseFont(String resourcePath, Font fallback) {
        try (InputStream in = RtHud.class.getResourceAsStream(resourcePath)) {
            if (in == null) return fallback;
            return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (Exception e) {
            return fallback;
        }
    }

    RtHud(VkPhysicalDevice pd, VkDevice device, long commandPool, VkQueue queue, int width, int height) {
        this.device = device;
        this.width = width;
        this.height = height;
        this.canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        this.pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());

        Font base     = loadBaseFont("/fonts/DroidSans.ttf", new Font(Font.SANS_SERIF, Font.PLAIN, 1));
        Font baseBold = loadBaseFont("/fonts/DroidSans-Bold.ttf", new Font(Font.SANS_SERIF, Font.BOLD, 1));
        this.fontCaption = baseBold.deriveFont(19f);
        this.fontBig     = baseBold.deriveFont(48f);
        this.fontMed     = baseBold.deriveFont(29f);
        this.fontSmall   = base.deriveFont(17f);
        this.fontTiny    = base.deriveFont(13f);
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
    void draw(long score, FruitTier next, String modeName, String aiName, boolean gameOver, boolean use3d,
              boolean paused, boolean bloomOn, boolean trayExpanded) {
        Graphics2D g = canvas.createGraphics();
        g.setComposite(java.awt.AlphaComposite.Clear);
        g.fillRect(0, 0, width, height);
        g.setComposite(java.awt.AlphaComposite.SrcOver);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        // ---- top-left: score + mode ----
        int scoreW = 320, scoreH = 128;
        panel(g, 16, 16, scoreW, scoreH, 22, GOLD);
        drawTracked(g, fontCaption, "SCORE", 38, 50, DIM, 2.2f);
        g.setColor(GOLD_DEEP);
        g.fillRect(38, 60, 30, 3);
        g.setColor(GOLD);
        g.setFont(fontBig);
        g.drawString(Long.toString(score), 38, 106);
        g.setColor(aiName != null ? VIOLET : FAINT);
        g.setFont(fontSmall);
        g.drawString(aiName != null ? modeName + "  ·  AI: " + aiName : modeName, 38, 132);

        // ---- top-right: next-fruit chip ----
        int panelW = 168, px = width - 16 - panelW;
        panel(g, px, 16, panelW, scoreH, 22, null);
        drawTracked(g, fontCaption, "NEXT", px + 22, 50, DIM, 2.2f);
        g.setColor(FAINT);
        g.fillRect(px + 22, 60, 20, 3);
        var c = FruitColors.of(next);
        int cx = px + panelW / 2, cy = 96, r = 30;
        Color fruitColor = new Color(c.r, c.g, c.b, 1f);
        // Soft duotone disc (a bright core fading to the fruit's true color at the
        // rim) instead of a flat fill — reads as a small rendered object, not an icon.
        g.setPaint(new java.awt.RadialGradientPaint(
                new java.awt.geom.Point2D.Float(cx - r * 0.35f, cy - r * 0.35f), r * 1.6f,
                new float[]{0f, 1f}, new Color[]{brighten(fruitColor, 0.55f), fruitColor}));
        g.fillOval(cx - r, cy - r, 2 * r, 2 * r);
        g.setPaint(new Color(0f, 0f, 0f, 0.35f));
        g.setStroke(new BasicStroke(2f));
        g.drawOval(cx - r, cy - r, 2 * r, 2 * r);
        g.setColor(new Color(0, 0, 0, 200));
        g.setFont(fontCaption);
        String tierNum = Integer.toString(next.tier);
        var fm = g.getFontMetrics();
        g.drawString(tierNum, cx - fm.stringWidth(tierNum) / 2, cy + fm.getAscent() / 2 - 1);

        // ---- bottom: control hints (collapsible — see §5's hotkey tray) ----
        g.setFont(fontTiny);
        String hints = aiName != null
                ? (use3d ? "RIGHT-DRAG ORBIT   ·   SCROLL ZOOM   ·   R RESTART   ·   ESC PAUSE"
                         : "SCROLL ZOOM   ·   R RESTART   ·   ESC PAUSE")
                : (use3d ? "CLICK DROP   ·   RIGHT-DRAG ORBIT   ·   SCROLL ZOOM   ·   R RESTART   ·   ESC PAUSE"
                         : "CLICK DROP   ·   SCROLL ZOOM   ·   R RESTART   ·   ESC PAUSE");
        if (trayExpanded) {
            float hintTracking = 1.1f;
            int hw = trackedWidth(g.getFontMetrics(), hints, hintTracking);
            int hpx = width / 2 - hw / 2;
            panel(g, hpx - 18, height - 54, hw + 36, 38, 17, null);
            drawTracked(g, fontTiny, hints, hpx, height - 29, FAINT, hintTracking);
            // tiny collapse arrow, right of the tray
            drawTrayArrow(g, true);
        } else {
            drawTrayArrow(g, false);
        }

        if (paused) drawPauseOverlay(g, bloomOn);

        // ---- game-over banner ----
        if (gameOver) {
            int bw = 470, bh = 178, bx = width / 2 - bw / 2, by = height / 2 - bh / 2;
            panel(g, bx, by, bw, bh, 26, DANGER);
            g.setColor(DANGER);
            g.setStroke(new BasicStroke(1.5f));
            g.drawRoundRect(bx, by, bw, bh, 26, 26);
            g.setFont(fontMed);
            g.setColor(DANGER);
            var fmM = g.getFontMetrics();
            String over = "GAME OVER";
            g.drawString(over, width / 2 - fmM.stringWidth(over) / 2, by + 62);
            g.setColor(TEXT);
            g.setFont(fontCaption);
            var fmC = g.getFontMetrics();
            String fin = "FINAL SCORE   " + score;
            g.drawString(fin, width / 2 - fmC.stringWidth(fin) / 2, by + 104);
            g.setColor(FAINT);
            g.setFont(fontSmall);
            String sub = aiName != null ? "next round starting…" : "press R to restart";
            var fmS2 = g.getFontMetrics();
            g.drawString(sub, width / 2 - fmS2.stringWidth(sub) / 2, by + 142);
        }
        g.dispose();
        packAndUpload();
    }

    // ---- §5: collapsible hotkey tray + in-RT pause menu — geometry shared with
    // RtLabLauncher's mouse-click hit-testing, so both sides always agree on where
    // these controls actually are. ----
    private static final int TRAY_ARROW_SIZE = 34;
    int trayArrowX() { return width / 2 + 210; }
    int trayArrowY() { return height - 54; }

    private static final int PAUSE_BTN_W = 260, PAUSE_BTN_H = 58, PAUSE_BTN_GAP = 16;
    int pauseResumeX() { return width / 2 - PAUSE_BTN_W / 2; }
    int pauseResumeY() { return height / 2 - PAUSE_BTN_H / 2 - PAUSE_BTN_H - PAUSE_BTN_GAP; }
    int pauseBloomX()  { return width / 2 - PAUSE_BTN_W / 2; }
    int pauseBloomY()  { return height / 2 - PAUSE_BTN_H / 2; }
    int pauseQuitX()   { return width / 2 - PAUSE_BTN_W / 2; }
    int pauseQuitY()   { return height / 2 - PAUSE_BTN_H / 2 + PAUSE_BTN_H + PAUSE_BTN_GAP; }
    int pauseBtnW() { return PAUSE_BTN_W; }
    int pauseBtnH() { return PAUSE_BTN_H; }

    private void drawTrayArrow(Graphics2D g, boolean expanded) {
        int ax = trayArrowX(), ay = trayArrowY();
        panel(g, ax, ay, TRAY_ARROW_SIZE, TRAY_ARROW_SIZE, 10, null);
        g.setColor(FAINT);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int cx = ax + TRAY_ARROW_SIZE / 2, cy = ay + TRAY_ARROW_SIZE / 2;
        // A simple chevron — up when the tray is out (tap to collapse it away),
        // down when it's collapsed (tap to bring the hints back).
        if (expanded) {
            g.drawLine(cx - 6, cy + 2, cx, cy - 4);
            g.drawLine(cx, cy - 4, cx + 6, cy + 2);
        } else {
            g.drawLine(cx - 6, cy - 2, cx, cy + 4);
            g.drawLine(cx, cy + 4, cx + 6, cy - 2);
        }
    }

    /** Live-updating graphics settings reachable from the pause menu — deliberately
     *  scoped to knobs that don't need a swapchain rebuild (resolution/fullscreen
     *  changes belong in the main app's Settings screen and take effect on RT Lab's
     *  next launch, not mid-session) so every control here really does apply live,
     *  no restart, the moment it's clicked. */
    private void drawPauseOverlay(Graphics2D g, boolean bloomOn) {
        g.setColor(new Color(6, 5, 12, 210));
        g.fillRect(0, 0, width, height);

        g.setColor(TEXT);
        g.setFont(fontMed);
        var fmT = g.getFontMetrics();
        String title = "PAUSED";
        g.drawString(title, width / 2 - fmT.stringWidth(title) / 2, pauseResumeY() - 46);

        drawPauseButton(g, pauseResumeX(), pauseResumeY(), "RESUME", VIOLET);
        drawPauseButton(g, pauseBloomX(), pauseBloomY(), "BLOOM: " + (bloomOn ? "ON" : "OFF"), GOLD_DEEP);
        drawPauseButton(g, pauseQuitX(), pauseQuitY(), "QUIT", DANGER);
    }

    private void drawPauseButton(Graphics2D g, int x, int y, String label, Color accent) {
        panel(g, x, y, PAUSE_BTN_W, PAUSE_BTN_H, 16, accent);
        g.setColor(accent);
        g.setStroke(new BasicStroke(1.5f));
        g.drawRoundRect(x, y, PAUSE_BTN_W, PAUSE_BTN_H, 16, 16);
        g.setColor(TEXT);
        g.setFont(fontCaption);
        var fm = g.getFontMetrics();
        g.drawString(label, x + PAUSE_BTN_W / 2 - fm.stringWidth(label) / 2, y + PAUSE_BTN_H / 2 + fm.getAscent() / 2 - 2);
    }

    /**
     * §5: a branded loading frame — drawn and blitted straight to the swapchain by
     * {@link RtLabLauncher} BEFORE the expensive resources (shader compilation,
     * photo textures, BLAS/TLAS builds) are created, so the player sees the app's
     * own look immediately instead of a blank/undefined window for however long
     * that takes. Unlike {@link #draw}, every pixel here must be fully OPAQUE —
     * this is composited by nothing, there's no RT frame underneath it yet.
     */
    void drawLoading(String subtitle) {
        Graphics2D g = canvas.createGraphics();
        g.setComposite(java.awt.AlphaComposite.Src);
        g.setPaint(new GradientPaint(0, 0, new Color(12, 9, 22, 255), 0, height, new Color(22, 15, 36, 255)));
        g.fillRect(0, 0, width, height);
        g.setComposite(java.awt.AlphaComposite.SrcOver);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setColor(VIOLET);
        g.setFont(fontBig);
        FontMetrics fmBig = g.getFontMetrics();
        String title = "SUIKA RT LAB";
        g.drawString(title, width / 2 - fmBig.stringWidth(title) / 2, height / 2 - 6);

        g.setColor(GOLD_DEEP);
        g.fillRect(width / 2 - 60, height / 2 + 18, 120, 3);

        g.setColor(DIM);
        g.setFont(fontSmall);
        FontMetrics fmSmall = g.getFontMetrics();
        g.drawString(subtitle, width / 2 - fmSmall.stringWidth(subtitle) / 2, height / 2 + 50);
        g.dispose();
        packAndUpload();
    }

    /** ARGB ints (Java2D's native layout) -> RGBA bytes for VK_FORMAT_R8G8B8A8_UNORM,
     *  staged for {@link #recordUpload}. Shared tail of every draw method. */
    private void packAndUpload() {
        int[] argb = ((DataBufferInt) canvas.getRaster().getDataBuffer()).getData();
        pixels.clear();
        for (int p : argb) {
            pixels.put((byte) (p >> 16)).put((byte) (p >> 8)).put((byte) p).put((byte) (p >>> 24));
        }
        pixels.flip();
        staging.uploadHostVisible(pixels);
        dirty = true;
    }

    /** A rounded panel with a subtle top-to-bottom gradient (flat fills read as cheap
     *  "boxes"; a graded one reads as a rendered surface, matching the studio-lit
     *  jar/table it floats over) plus a hairline edge and, when {@code accent} is
     *  given, a thin colored strip along the panel's top — the SAME "colored top
     *  strip on a dark card" motif every modal in the 2D game already uses
     *  (ControlCenterScreen's SETUP/SAVES panels, MainMenuScreen's AI picker), so this
     *  HUD reads as the same app's design language instead of a bolted-on overlay. */
    private void panel(Graphics2D g, int x, int y, int w, int h, int radius, Color accent) {
        RoundRectangle2D shape = new RoundRectangle2D.Float(x, y, w, h, radius, radius);
        g.setPaint(new GradientPaint(x, y, PANEL_TOP, x, y + h, PANEL_BOTTOM));
        g.fill(shape);
        g.setPaint(PANEL_EDGE);
        g.setStroke(new BasicStroke(1f));
        g.draw(shape);
        if (accent != null) {
            g.setColor(accent);
            g.fill(new RoundRectangle2D.Float(x + 1, y + h - 3f, w - 2, 3f, 3f, 3f));
        }
    }

    /** Manual letter-spacing: AWT/Java2D has no font-tracking API, but a little extra
     *  air between all-caps caption letters ("SCORE", "NEXT", the control hints) is
     *  the single cheapest thing that makes small UI text look designed rather than
     *  just printed. */
    private void drawTracked(Graphics2D g, Font font, String text, int x, int y, Color color, float tracking) {
        g.setFont(font);
        g.setColor(color);
        FontMetrics fm = g.getFontMetrics();
        float cx = x;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            g.drawString(String.valueOf(ch), cx, y);
            cx += fm.charWidth(ch) + tracking;
        }
    }

    private int trackedWidth(FontMetrics fm, String text, float tracking) {
        float w = 0;
        for (int i = 0; i < text.length(); i++) w += fm.charWidth(text.charAt(i)) + tracking;
        return Math.round(w);
    }

    /** Lightens {@code c} toward white by {@code amount} (0..1) — used for the
     *  next-fruit chip's soft highlight core. */
    private static Color brighten(Color c, float amount) {
        int r = c.getRed()   + Math.round((255 - c.getRed())   * amount);
        int g = c.getGreen() + Math.round((255 - c.getGreen()) * amount);
        int b = c.getBlue()  + Math.round((255 - c.getBlue())  * amount);
        return new Color(Math.min(255, r), Math.min(255, g), Math.min(255, b));
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
