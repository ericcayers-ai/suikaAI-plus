package dev.suika.game.rtlab;

import dev.suika.core.FruitTier;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static dev.suika.game.rtlab.VkCheck.ok;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.vkCmdTraceRaysKHR;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Public entry point for the experimental hardware ray-traced game ("RT Lab").
 * Launches on its own daemon thread with its own GLFW window and its own Vulkan
 * instance/device — entirely separate from LibGDX's OpenGL rendering, because
 * hardware ray tracing has no OpenGL equivalent and LibGDX ships no Vulkan backend.
 *
 * <p>This is REAL gameplay, not a tech demo: a full game runs inside the window —
 * fruits dropped into a cylindrical glass jar standing on a pine-wood table in a
 * brightly lit tan studio (the wall sits past the focal plane and melts into
 * bokeh). Move the mouse to aim, click to drop, R restarts, D toggles the
 * denoiser. The score and next fruit live in the window title (rendering crisp
 * text through a raw Vulkan RT pipeline isn't worth the complexity for a lab).
 *
 * <p>Physics is selectable at launch: the classic 2D engine presented as a slice
 * through the jar, or true 3D physics ({@link Jar3DPhysics}) where the mouse aims
 * across the whole jar cross-section. If this GPU/driver doesn't support ray
 * tracing, this fails loudly to the console and returns — it never touches or
 * destabilises the main 2D game.
 */
public final class RtLabLauncher {

    private RtLabLauncher() {}

    private static volatile boolean running = false;

    /** True while an RT Lab window is open — the rest of the app can use this to
     *  shed GPU-hungry work (e.g. the control center clamps its multi-board view). */
    public static boolean isRunning() { return running; }

    /** Launches the RT Lab window on a background thread, or does nothing (log only)
     *  if one is already open. Safe to call from the LibGDX render/UI thread.
     *
     *  @param use3dPhysics true = true 3D physics in the jar; false = classic 2D engine */
    public static void launch(boolean use3dPhysics) {
        if (running) return;
        running = true;
        Thread t = new Thread(() -> run(use3dPhysics), "rtlab");
        t.setDaemon(true);
        t.start();
    }

    private static void run(boolean use3dPhysics) {
        try {
            runUnsafe(use3dPhysics);
        } catch (Throwable t) {
            System.err.println("[RT Lab] Not available on this system: " + t);
        } finally {
            running = false;
        }
    }

    private static void runUnsafe(boolean use3dPhysics) {
        Configuration.STACK_SIZE.set(RtContext.STACK_SIZE_KB);
        // glfwInit() is idempotent — safe even though LibGDX's Lwjgl3Application has
        // already initialised GLFW for the main game window. We must NEVER call
        // glfwTerminate() here though, since that would tear down the main window too.
        if (!glfwInit()) throw new IllegalStateException("glfwInit failed");

        // Portrait-ish: the jar is taller than wide, like the classic board.
        int width = 780, height = 1040;
        PointerBuffer surfaceExtensions = GLFWVulkan.glfwGetRequiredInstanceExtensions();
        if (surfaceExtensions == null) throw new IllegalStateException("Vulkan not supported by GLFW on this system");

        RtGameSession session = use3dPhysics
                ? new Rt3DSession(System.nanoTime())
                : new Rt2DSession(System.nanoTime());

        try (RtContext ctx = new RtContext(surfaceExtensions);
             RtWindow window = new RtWindow(ctx.instance, width, height, "Suika RT Lab (experimental)")) {

            System.out.println("[RT Lab] " + ctx.deviceName + " — " + session.modeName());
            RtSwapchain swap = new RtSwapchain(ctx.physicalDevice, ctx.device, window.surface, width, height);

            long commandPool = createCommandPool(ctx.device, ctx.graphicsQueueFamily);
            try (RtOutputImage raw = new RtOutputImage(ctx.physicalDevice, ctx.device, commandPool, ctx.graphicsQueue, width, height);
                 RtOutputImage denoised = new RtOutputImage(ctx.physicalDevice, ctx.device, commandPool, ctx.graphicsQueue, width, height);
                 RtPipeline pipeline = new RtPipeline(ctx.physicalDevice, ctx.device);
                 RtDenoiser denoiser = new RtDenoiser(ctx.device, raw, denoised);
                 RtTextureSet textures = new RtTextureSet(ctx.physicalDevice, ctx.device, commandPool, ctx.graphicsQueue);
                 RtMeshLibrary meshes = new RtMeshLibrary(ctx.physicalDevice, ctx.device, commandPool, ctx.graphicsQueue);
                 RtScene scene = new RtScene(ctx.physicalDevice, ctx.device, commandPool, ctx.graphicsQueue, meshes, textures)) {

                pipeline.writeTextures(textures.all());

                boolean[] denoiseOn = {true};
                glfwSetKeyCallback(window.handle, (win, key, scancode, action, mods) -> {
                    if (action != GLFW_PRESS) return;
                    if (key == GLFW_KEY_D) {
                        denoiseOn[0] = !denoiseOn[0];
                        System.out.println("[RT Lab] denoiser " + (denoiseOn[0] ? "ON" : "OFF"));
                    } else if (key == GLFW_KEY_R) {
                        session.reset();
                    } else if (key == GLFW_KEY_ESCAPE) {
                        glfwSetWindowShouldClose(win, true);
                    }
                });
                glfwSetCursorPosCallback(window.handle, (win, cx, cy) ->
                        session.setPointer((float) (cx / width), (float) (cy / height)));
                glfwSetMouseButtonCallback(window.handle, (win, button, action, mods) -> {
                    if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS) session.drop();
                });

                VkCommandBuffer cmd = allocateCommandBuffer(ctx.device, commandPool);
                long imageAvailable = createSemaphore(ctx.device);
                long renderFinished = createSemaphore(ctx.device);
                long inFlight = createFence(ctx.device);

                Camera cam = new Camera(width, height);

                long startNs = System.nanoTime();
                long lastNs = startNs;
                long frame = 0;
                long lastTitleScore = -1;
                FruitTier lastTitleNext = null;
                boolean lastTitleOver = false;

                while (!window.shouldClose()) {
                    window.pollEvents();
                    long nowNs = System.nanoTime();
                    float dt = Math.min((nowNs - lastNs) / 1e9f, 0.05f);
                    lastNs = nowNs;
                    float time = (float) ((nowNs - startNs) / 1e9);

                    session.step(dt);

                    // HUD lives in the title bar; update only when something changed.
                    if (session.score() != lastTitleScore || session.nextTier() != lastTitleNext
                            || session.gameOver() != lastTitleOver) {
                        lastTitleScore = session.score();
                        lastTitleNext = session.nextTier();
                        lastTitleOver = session.gameOver();
                        String status = lastTitleOver ? "GAME OVER — press R" : "next: " + lastTitleNext;
                        glfwSetWindowTitle(window.handle, "Suika RT Lab — " + session.modeName()
                                + " — score " + lastTitleScore + " — " + status
                                + "  ·  click drop · R restart · D denoise");
                    }

                    List<RtScene.FruitInstance> instances = new ArrayList<>();
                    for (RtGameSession.Ball b : session.fruits()) {
                        instances.add(new RtScene.FruitInstance(b.x(), b.y(), b.z(), b.radius(), b.tier()));
                    }
                    if (!session.gameOver()) {
                        // The not-yet-dropped fruit hovers over the aim point at the rim.
                        FruitTier cur = session.currentTier();
                        instances.add(new RtScene.FruitInstance(session.hoverX(), (float) Jar3DPhysics.DROP_Y,
                                session.hoverZ(), cur.radius, cur));
                        // Next-up preview floats beside the jar's rim, always tier-sized
                        // relative to a fixed chip scale so it reads as UI, not gameplay.
                        FruitTier next = session.nextTier();
                        instances.add(new RtScene.FruitInstance(-6.9f, 16.6f, 0f,
                                0.45f + 0.22f * next.radius, next));
                    }

                    // First frame has no history to blend against — write through fully.
                    float blend = frame == 0 ? 1.0f : 0.30f;
                    scene.updateFrame(pipeline, raw, instances, cam.frame(time, blend));
                    frame++;

                    renderFrame(ctx, swap, pipeline, denoiser, raw, denoised, denoiseOn[0],
                            cmd, imageAvailable, renderFinished, inFlight, width, height);
                }
                vkDeviceWaitIdle(ctx.device);

                vkDestroySemaphore(ctx.device, imageAvailable, null);
                vkDestroySemaphore(ctx.device, renderFinished, null);
                vkDestroyFence(ctx.device, inFlight, null);
            }
            swap.close();
            vkDestroyCommandPool(ctx.device, commandPool, null);
        }
        System.out.println("[RT Lab] closed — final score " + session.score());
    }

    /** Fixed studio camera: in front of the jar, slightly above, focused on the
     *  fruit plane so the tan wall far behind lands in bokeh and the table edge
     *  near the lens blurs as foreground. */
    private static final class Camera {
        final float posX = 0f, posY = 10.5f, posZ = 21f;
        final float[] fwd, right, up;
        final float tanHalfFov = (float) Math.tan(Math.toRadians(27)); // 54° vertical
        final float aperture = 0.28f;
        final float focusDist;
        final float aspect;

        Camera(int width, int height) {
            float tx = 0f, ty = 6.8f, tz = 0f;
            fwd = normalize(tx - posX, ty - posY, tz - posZ);
            // right = normalize(cross(fwd, worldUp)) — the sign matters now: mouse-x
            // maps straight to world x, so screen-right MUST be world +X when looking
            // down -Z (the old showcase used the mirrored vector and its symmetric
            // orbiting scene hid it).
            right = normalize(-fwd[2], 0f, fwd[0]);
            up = cross(right, fwd);
            focusDist = (float) Math.sqrt((tx - posX) * (tx - posX) + (ty - posY) * (ty - posY) + (tz - posZ) * (tz - posZ));
            aspect = (float) width / height;
        }

        RtScene.CameraFrame frame(float time, float blend) {
            return new RtScene.CameraFrame(posX, posY, posZ,
                    fwd[0], fwd[1], fwd[2], right[0], right[1], right[2], up[0], up[1], up[2],
                    tanHalfFov, time, aperture, focusDist, blend, aspect);
        }
    }

    private static float[] normalize(float x, float y, float z) {
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        return len > 1e-6f ? new float[]{x / len, y / len, z / len} : new float[]{0, 0, -1};
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[]{
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]
        };
    }

    private static void renderFrame(RtContext ctx, RtSwapchain swap, RtPipeline pipeline, RtDenoiser denoiser,
                                     RtOutputImage raw, RtOutputImage denoised, boolean denoiseOn,
                                     VkCommandBuffer cmd, long imageAvailable, long renderFinished, long inFlight,
                                     int width, int height) {
        try (MemoryStack stack = stackPush()) {
            ok(vkWaitForFences(ctx.device, inFlight, true, Long.MAX_VALUE), "vkWaitForFences");
            ok(vkResetFences(ctx.device, inFlight), "vkResetFences");

            java.nio.IntBuffer pIndex = stack.mallocInt(1);
            ok(vkAcquireNextImageKHR(ctx.device, swap.handle, Long.MAX_VALUE, imageAvailable, VK_NULL_HANDLE, pIndex),
                    "vkAcquireNextImageKHR");
            int imageIndex = pIndex.get(0);

            ok(vkResetCommandBuffer(cmd, 0), "vkResetCommandBuffer");
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            ok(vkBeginCommandBuffer(cmd, beginInfo), "vkBeginCommandBuffer");

            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipeline.pipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipeline.pipelineLayout,
                    0, stack.longs(pipeline.descriptorSet), null);
            vkCmdTraceRaysKHR(cmd, pipeline.raygenRegion, pipeline.missRegion, pipeline.hitRegion,
                    pipeline.callableRegion, width, height, 1);

            long sourceImage = raw.image;
            if (denoiseOn) {
                // The compute shader must see the RT write, and the source image's
                // layout is already GENERAL for both — a memory barrier is enough,
                // no layout transition needed.
                VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack)
                        .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                        .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
                vkCmdPipelineBarrier(cmd,
                        org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, barrier, null, null);
                denoiser.dispatch(cmd, width, height);
                sourceImage = denoised.image;
            }

            long swapImage = swap.images[imageIndex];
            transition(stack, cmd, swapImage, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT);
            // The RT/denoiser output is GENERAL and already shader-writable; a barrier
            // (not a layout change) is enough before reading it as a blit source.
            VkMemoryBarrier.Buffer preBlit = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
            vkCmdPipelineBarrier(cmd,
                    denoiseOn ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT : org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, 0, preBlit, null, null);

            VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
            blit.get(0).srcSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1));
            blit.get(0).srcOffsets(0, o -> o.set(0, 0, 0));
            blit.get(0).srcOffsets(1, o -> o.set(width, height, 1));
            blit.get(0).dstSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1));
            blit.get(0).dstOffsets(0, o -> o.set(0, 0, 0));
            blit.get(0).dstOffsets(1, o -> o.set(swap.width, swap.height, 1));
            vkCmdBlitImage(cmd, sourceImage, VK_IMAGE_LAYOUT_GENERAL, swapImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    blit, VK_FILTER_LINEAR);

            transition(stack, cmd, swapImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_ACCESS_TRANSFER_WRITE_BIT, 0);
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
                .oldLayout(oldLayout).newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image).srcAccessMask(srcAccess).dstAccessMask(dstAccess);
        barrier.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barrier);
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
                    .commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
            PointerBuffer pBuffers = stack.mallocPointer(1);
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
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO).flags(VK_FENCE_CREATE_SIGNALED_BIT);
            LongBuffer p = stack.mallocLong(1);
            ok(vkCreateFence(device, info, null, p), "vkCreateFence");
            return p.get(0);
        }
    }
}
