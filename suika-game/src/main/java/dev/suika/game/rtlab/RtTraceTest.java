package dev.suika.game.rtlab;

import dev.suika.core.FruitTier;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static dev.suika.game.rtlab.VkCheck.ok;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.vkCmdTraceRaysKHR;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Headless (no window) visual QA harness for the RT game scene: renders the REAL
 * scene — pine-wood table, tan wall in bokeh, glass jar, textured fruits — with the
 * REAL gameplay camera, accumulates several temporal frames, runs the denoiser, and
 * writes PNGs for inspection. Not wired into the shipped app; run directly:
 * {@code java -cp ... dev.suika.game.rtlab.RtTraceTest [outDir]}.
 */
public final class RtTraceTest {
    public static void main(String[] args) throws Exception {
        Configuration.STACK_SIZE.set(RtContext.STACK_SIZE_KB);
        String outDir = args.length > 0 ? args[0] : ".";
        // "3d": scatter the pile across the jar's depth axis like true-3D gameplay
        // produces, so the 3D mode's look (off-plane shadows, depth-of-field falloff
        // INSIDE the jar) gets screenshot coverage too, not just the 2D slice.
        boolean scatter3d = args.length > 1 && "3d".equalsIgnoreCase(args[1]);

        PointerBuffer noSurfaceExtensions = org.lwjgl.system.MemoryUtil.memAllocPointer(0);

        int width = 780, height = 1040;
        try (RtContext ctx = new RtContext(noSurfaceExtensions)) {
            System.out.println("device = " + ctx.deviceName);

            long commandPool = createCommandPool(ctx.device, ctx.graphicsQueueFamily);
            try (RtOutputImage raw = new RtOutputImage(ctx.physicalDevice, ctx.device, commandPool, ctx.graphicsQueue, width, height);
                 RtOutputImage denoised = new RtOutputImage(ctx.physicalDevice, ctx.device, commandPool, ctx.graphicsQueue, width, height);
                 RtPipeline pipeline = new RtPipeline(ctx.physicalDevice, ctx.device);
                 RtDenoiser denoiser = new RtDenoiser(ctx.device, raw, denoised);
                 RtTextureSet textures = new RtTextureSet(ctx.physicalDevice, ctx.device, commandPool, ctx.graphicsQueue);
                 RtMeshLibrary meshes = new RtMeshLibrary(ctx.physicalDevice, ctx.device, commandPool, ctx.graphicsQueue);
                 RtScene scene = new RtScene(ctx.physicalDevice, ctx.device, commandPool, ctx.graphicsQueue, meshes, textures)) {

                pipeline.writeTextures(textures.all(), textures.environment());
                System.out.println("pipeline + " + textures.all().size() + " textures + HDRI env + 6 BLAS meshes ready");

                // A representative mid-game pile: settled fruits of several tiers, one
                // falling, plus the hover fruit hanging in the chute's mouth — matching
                // exactly what RtLabLauncher's live loop adds to the scene (no separate
                // "next" chip floating in 3D any more; that lives in the HUD overlay now).
                // In 3D-scatter mode the same pile spreads across the depth axis the
                // way Jar3DPhysics settles it — each z-offset is sized so
                // sqrt(x^2+z^2)+radius stays inside RtScene.JAR_RADIUS (5.45); the two
                // largest fruits (watermelon, melon) sit close to their x-only limit at
                // FruitTier's current (10%-bigger) radii, so they get little or no
                // z-scatter — a bigger offset there would poke the fruit visibly through
                // the glass, which an earlier version of this test did (a QA-harness bug,
                // not a physics one: real Jar3DPhysics clamps every drop to PLAY_RADIUS
                // via clampDrop()). Radii are read straight from FruitTier so this harness
                // can never silently drift from the real gameplay sizes.
                float z1 = scatter3d ? 0.5f : 0f, z2 = 0f,
                      z3 = scatter3d ? -2.0f : 0f, z4 = scatter3d ? 1.8f : 0f,
                      z5 = scatter3d ? 0.9f : 0f, z6 = scatter3d ? -1.1f : 0f;
                // Hover fruit peeks from the chute exit; the chute follows the same aim.
                float chuteX = 1.2f, chuteZ = scatter3d ? -0.8f : 0f;
                List<RtScene.FruitInstance> fruits = new ArrayList<>(List.of(
                        new RtScene.FruitInstance(-1.5f, FruitTier.WATERMELON.radius + 0.06f, z1, FruitTier.WATERMELON.radius, FruitTier.WATERMELON),
                        new RtScene.FruitInstance( 2.0f, FruitTier.MELON.radius + 0.06f, z2, FruitTier.MELON.radius, FruitTier.MELON),
                        new RtScene.FruitInstance( 0.6f, 7.10f, z5, FruitTier.APPLE.radius, FruitTier.APPLE),
                        new RtScene.FruitInstance(-3.4f, 7.60f, z3, FruitTier.DEKOPON.radius, FruitTier.DEKOPON),
                        new RtScene.FruitInstance( 3.9f, 6.30f, z4, FruitTier.GRAPE.radius, FruitTier.GRAPE),
                        new RtScene.FruitInstance(-1.2f, 9.20f, z6, FruitTier.STRAWBERRY.radius, FruitTier.STRAWBERRY),
                        new RtScene.FruitInstance( 1.8f, 12.0f, z5, FruitTier.CHERRY.radius, FruitTier.CHERRY),     // falling
                        new RtScene.FruitInstance(chuteX, RtScene.CHUTE_BOTTOM_Y - FruitTier.CHERRY.radius * 0.75f, chuteZ,
                                FruitTier.CHERRY.radius, FruitTier.CHERRY)                                           // held in the chute's mouth
                ));
                String suffix = scatter3d ? "_3d" : "";

                // Same camera as RtLabLauncher (kept in sync by hand — it's 6 numbers).
                float px = 0f, py = 12.5f, pz = 26.5f, tx = 0f, ty = 8.4f, tz = 0f;
                float[] fwd = norm(tx - px, ty - py, tz - pz);
                float[] right = norm(-fwd[2], 0f, fwd[0]);
                float[] up = {right[1] * fwd[2] - right[2] * fwd[1],
                              right[2] * fwd[0] - right[0] * fwd[2],
                              right[0] * fwd[1] - right[1] * fwd[0]};
                float focus = (float) Math.sqrt((tx-px)*(tx-px) + (ty-py)*(ty-py) + (tz-pz)*(tz-pz));

                // Accumulate several temporal frames exactly like the live loop does
                // (frame 0 writes through, the rest EMA-blend), then denoise.
                int frames = 24;
                for (int f = 0; f < frames; f++) {
                    RtScene.CameraFrame cam = new RtScene.CameraFrame(px, py, pz,
                            fwd[0], fwd[1], fwd[2], right[0], right[1], right[2], up[0], up[1], up[2],
                            (float) Math.tan(Math.toRadians(28)), f * 0.016f, 0.34f, focus,
                            f == 0 ? 1.0f : 0.30f, (float) width / height);
                    scene.updateFrame(pipeline, raw, fruits, chuteX, chuteZ, true, cam);
                    traceOneFrame(ctx.device, ctx.graphicsQueue, commandPool, pipeline, width, height);
                }
                savePng(ctx.physicalDevice, ctx.device, commandPool, ctx.graphicsQueue, raw, width, height,
                        new File(outDir, "rtlab_accumulated" + suffix + ".png").getPath());
                System.out.println("wrote rtlab_accumulated" + suffix + ".png (" + frames + " temporal frames, no spatial denoise)");

                OneShotCommands.submit(ctx.device, commandPool, ctx.graphicsQueue, cmd -> denoiser.dispatch(cmd, width, height));
                savePng(ctx.physicalDevice, ctx.device, commandPool, ctx.graphicsQueue, denoised, width, height,
                        new File(outDir, "rtlab_denoised" + suffix + ".png").getPath());
                System.out.println("wrote rtlab_denoised" + suffix + ".png (temporal + bilateral)");

                // Same present-image + HUD composite path the live window runs, with a
                // representative HUD state — verifies the full GUI overlay headlessly.
                try (RtOutputImage present = new RtOutputImage(ctx.physicalDevice, ctx.device, commandPool, ctx.graphicsQueue, width, height);
                     RtHud hudOverlay = new RtHud(ctx.physicalDevice, ctx.device, commandPool, ctx.graphicsQueue, width, height);
                     RtHudCompositor compositor = new RtHudCompositor(ctx.device, present, hudOverlay)) {
                    hudOverlay.draw(1234, FruitTier.PERSIMMON, scatter3d ? "3D physics" : "2D physics",
                            "MCTS", false, scatter3d);
                    OneShotCommands.submit(ctx.device, commandPool, ctx.graphicsQueue, cmd -> {
                        try (MemoryStack stack = stackPush()) {
                            VkImageCopy.Buffer copy = VkImageCopy.calloc(1, stack);
                            copy.get(0).srcSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1));
                            copy.get(0).dstSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1));
                            copy.get(0).extent(e -> e.width(width).height(height).depth(1));
                            vkCmdCopyImage(cmd, denoised.image, VK_IMAGE_LAYOUT_GENERAL, present.image, VK_IMAGE_LAYOUT_GENERAL, copy);
                            hudOverlay.recordUpload(cmd);
                            VkMemoryBarrier.Buffer bar = VkMemoryBarrier.calloc(1, stack)
                                    .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
                            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                    0, bar, null, null);
                            compositor.dispatch(cmd, width, height);
                        }
                    });
                    savePng(ctx.physicalDevice, ctx.device, commandPool, ctx.graphicsQueue, present, width, height,
                            new File(outDir, "rtlab_hud" + suffix + ".png").getPath());
                }
                System.out.println("SUCCESS: wrote rtlab_hud" + suffix + ".png (denoised + GUI overlay, what the player sees)");
            }
            vkDestroyCommandPool(ctx.device, commandPool, null);
        }
    }

    private static float[] norm(float x, float y, float z) {
        float l = (float) Math.sqrt(x * x + y * y + z * z);
        return new float[]{x / l, y / l, z / l};
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

    private static void traceOneFrame(VkDevice device, VkQueue queue, long commandPool,
                                       RtPipeline pipeline, int width, int height) {
        OneShotCommands.submit(device, commandPool, queue, cmd -> {
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipeline.pipeline);
            try (MemoryStack stack = stackPush()) {
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipeline.pipelineLayout,
                        0, stack.longs(pipeline.descriptorSet), null);
            }
            vkCmdTraceRaysKHR(cmd, pipeline.raygenRegion, pipeline.missRegion, pipeline.hitRegion,
                    pipeline.callableRegion, width, height, 1);
        });
    }

    private static void savePng(VkPhysicalDevice pd, VkDevice device, long commandPool, VkQueue queue,
                                 RtOutputImage output, int width, int height, String path) throws Exception {
        long bufSize = (long) width * height * 4 * 4; // RGBA32F
        RtBuffer staging = new RtBuffer(pd, device, bufSize, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        try {
            OneShotCommands.submit(device, commandPool, queue, cmd -> {
                try (MemoryStack stack = stackPush()) {
                    VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack)
                            .bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
                    region.get(0).imageSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .mipLevel(0).baseArrayLayer(0).layerCount(1));
                    region.get(0).imageOffset(o -> o.set(0, 0, 0));
                    region.get(0).imageExtent(e -> e.width(width).height(height).depth(1));
                    vkCmdCopyImageToBuffer(cmd, output.image, VK_IMAGE_LAYOUT_GENERAL, staging.buffer, region);
                }
            });

            try (MemoryStack stack = stackPush()) {
                PointerBuffer pData = stack.mallocPointer(1);
                ok(vkMapMemory(device, staging.memory, 0, bufSize, 0, pData), "vkMapMemory (readback)");
                ByteBuffer pixels = org.lwjgl.system.MemoryUtil.memByteBuffer(pData.get(0), (int) bufSize)
                        .order(java.nio.ByteOrder.nativeOrder());

                BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int base = (y * width + x) * 16;
                        float r = pixels.getFloat(base);
                        float g = pixels.getFloat(base + 4);
                        float b = pixels.getFloat(base + 8);
                        img.setRGB(x, y, (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b));
                    }
                }
                vkUnmapMemory(device, staging.memory);
                ImageIO.write(img, "png", new File(path));
            }
        } finally {
            staging.close();
        }
    }

    private static int clamp255(float v) {
        int i = Math.round(v * 255f);
        return Math.max(0, Math.min(255, i));
    }
}
