package dev.suika.game.rtlab;

import dev.suika.ai.ActionSpec;
import dev.suika.ai.AgentPlugin;
import dev.suika.core.Fruit;
import dev.suika.core.FruitTier;
import dev.suika.core.GameState;
import dev.suika.core.PhysicsConfig;
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
 * across the whole jar cross-section — and, only in that 3D mode, right-click-and-
 * hold orbits the camera around the jar (left-click still drops; the two never
 * conflict). If this GPU/driver doesn't support ray tracing, this fails loudly to
 * the console and returns — it never touches or destabilises the main 2D game.
 *
 * <p>Optionally driven by a saved {@link dev.suika.ai.AgentPlugin} instead of a
 * human: see {@link #launch(boolean, dev.suika.ai.AgentPlugin)}.
 */
public final class RtLabLauncher {

    private RtLabLauncher() {}

    private static volatile boolean running = false;

    /** True while an RT Lab window is open — the rest of the app can use this to
     *  shed GPU-hungry work (e.g. the control center clamps its multi-board view). */
    public static boolean isRunning() { return running; }

    /** Launches the RT Lab window on a background thread for a HUMAN player, or does
     *  nothing (log only) if one is already open. Safe to call from the LibGDX
     *  render/UI thread.
     *
     *  @param use3dPhysics true = true 3D physics in the jar; false = classic 2D engine */
    public static void launch(boolean use3dPhysics) {
        launch(use3dPhysics, null);
    }

    /** Launches the RT Lab window on a background thread, or does nothing (log only)
     *  if one is already open. Safe to call from the LibGDX render/UI thread.
     *
     *  @param use3dPhysics true = true 3D physics in the jar; false = classic 2D engine
     *  @param aiDriver     when non-null, this agent plays automatically (loaded from
     *                      an {@link dev.suika.game.AiTechnique} save slot — see
     *                      {@code AiSlotPlayer}) instead of the mouse; the mouse still
     *                      orbits the camera in 3D mode and R/D/ESC still work */
    public static void launch(boolean use3dPhysics, AgentPlugin aiDriver) {
        if (running) return;
        running = true;
        Thread t = new Thread(() -> run(use3dPhysics, aiDriver), "rtlab");
        t.setDaemon(true);
        t.start();
    }

    private static void run(boolean use3dPhysics, AgentPlugin aiDriver) {
        try {
            runUnsafe(use3dPhysics, aiDriver);
        } catch (Throwable t) {
            System.err.println("[RT Lab] Not available on this system: " + t);
        } finally {
            running = false;
        }
    }

    private static void runUnsafe(boolean use3dPhysics, AgentPlugin aiDriver) {
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

            // Plain ASCII here (not the em-dash used in the window title below): this
            // goes through System.out, which on Windows renders via the console's OEM
            // codepage (cp437 by default) — no em-dash glyph there, so it would print
            // as mojibake. glfwSetWindowTitle instead goes through Win32's Unicode
            // SetWindowTextW, so the window title itself is unaffected and keeps the
            // nicer punctuation.
            System.out.println("[RT Lab] " + ctx.deviceName + " - " + session.modeName()
                    + (aiDriver != null ? " - AI: " + aiDriver.displayName() : ""));
            RtSwapchain swap = new RtSwapchain(ctx.physicalDevice, ctx.device, window.surface, width, height);

            long commandPool = createCommandPool(ctx.device, ctx.graphicsQueueFamily);
            try (RtOutputImage raw = new RtOutputImage(ctx.physicalDevice, ctx.device, commandPool, ctx.graphicsQueue, width, height);
                 RtOutputImage denoised = new RtOutputImage(ctx.physicalDevice, ctx.device, commandPool, ctx.graphicsQueue, width, height);
                 RtPipeline pipeline = new RtPipeline(ctx.physicalDevice, ctx.device);
                 RtDenoiser denoiser = new RtDenoiser(ctx.device, raw, denoised);
                 RtTextureSet textures = new RtTextureSet(ctx.physicalDevice, ctx.device, commandPool, ctx.graphicsQueue);
                 RtMeshLibrary meshes = new RtMeshLibrary(ctx.physicalDevice, ctx.device, commandPool, ctx.graphicsQueue);
                 RtScene scene = new RtScene(ctx.physicalDevice, ctx.device, commandPool, ctx.graphicsQueue, meshes, textures)) {

                pipeline.writeTextures(textures.all(), textures.environment());

                Camera cam = new Camera(width, height);
                // Right-click-and-hold orbit — 3D physics mode only (the 2D mode's jar
                // is always viewed from the same fixed slice angle, so orbiting it would
                // just rotate the camera around an otherwise flat scene). While the right
                // button is held, cursor movement drives the orbit instead of the aim
                // point; releasing it goes straight back to aiming from the current
                // cursor position (no snap — the next move event re-syncs lastCursor).
                boolean[] orbiting = {false};
                float[] lastCursor = {(float) width / 2f, (float) height / 2f};
                float ORBIT_SENSITIVITY = 0.006f;

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
                glfwSetCursorPosCallback(window.handle, (win, cx, cy) -> {
                    float fx = (float) cx, fy = (float) cy;
                    if (orbiting[0]) {
                        cam.orbitYaw   += (fx - lastCursor[0]) * ORBIT_SENSITIVITY;
                        cam.orbitPitch -= (fy - lastCursor[1]) * ORBIT_SENSITIVITY;
                    } else if (aiDriver == null) {
                        session.setPointer(fx / width, fy / height);
                    }
                    lastCursor[0] = fx; lastCursor[1] = fy;
                });
                glfwSetMouseButtonCallback(window.handle, (win, button, action, mods) -> {
                    if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS && aiDriver == null) session.drop();
                    else if (button == GLFW_MOUSE_BUTTON_RIGHT) orbiting[0] = use3dPhysics && action == GLFW_PRESS;
                });

                VkCommandBuffer cmd = allocateCommandBuffer(ctx.device, commandPool);
                long imageAvailable = createSemaphore(ctx.device);
                long renderFinished = createSemaphore(ctx.device);
                long inFlight = createFence(ctx.device);

                long startNs = System.nanoTime();
                long lastNs = startNs;
                long frame = 0;
                long lastTitleScore = -1;
                FruitTier lastTitleNext = null;
                boolean lastTitleOver = false;
                // AI autoplay paces itself like the classic AI-watch view (GameSettings.
                // aiMoveDelay) rather than dropping every frame — otherwise it would stack
                // fruit faster than the chute-clear guard even allows.
                final float AI_MOVE_INTERVAL = 0.55f;
                float[] aiTimer = {0.35f};
                String controlsHint = aiDriver != null
                        ? "AI: " + aiDriver.displayName() + " · R restart · D denoise"
                        : "click drop · R restart · D denoise";

                while (!window.shouldClose()) {
                    window.pollEvents();
                    long nowNs = System.nanoTime();
                    float dt = Math.min((nowNs - lastNs) / 1e9f, 0.05f);
                    lastNs = nowNs;
                    float time = (float) ((nowNs - startNs) / 1e9);

                    session.step(dt);

                    if (aiDriver != null && !session.gameOver()) {
                        aiTimer[0] -= dt;
                        if (aiTimer[0] <= 0f) {
                            driveAi(session, aiDriver, use3dPhysics);
                            aiTimer[0] = AI_MOVE_INTERVAL;
                        }
                    }

                    // HUD lives in the title bar; update only when something changed.
                    if (session.score() != lastTitleScore || session.nextTier() != lastTitleNext
                            || session.gameOver() != lastTitleOver) {
                        lastTitleScore = session.score();
                        lastTitleNext = session.nextTier();
                        lastTitleOver = session.gameOver();
                        String status = lastTitleOver ? "GAME OVER — press R" : "next: " + lastTitleNext;
                        glfwSetWindowTitle(window.handle, "Suika RT Lab — " + session.modeName()
                                + " — score " + lastTitleScore + " — " + status
                                + "  ·  " + controlsHint);
                    }

                    List<RtScene.FruitInstance> instances = new ArrayList<>();
                    for (RtGameSession.Ball b : session.fruits()) {
                        instances.add(new RtScene.FruitInstance(b.x(), b.y(), b.z(), b.radius(), b.tier()));
                    }
                    boolean playing = !session.gameOver();
                    if (playing) {
                        // The not-yet-dropped fruit peeks out of the chute's lower
                        // opening, over the aim point — like the reference scene's
                        // strawberries emerging from the metal tube.
                        FruitTier cur = session.currentTier();
                        instances.add(new RtScene.FruitInstance(session.hoverX(),
                                RtScene.CHUTE_BOTTOM_Y - cur.radius * 0.4f,
                                session.hoverZ(), cur.radius, cur));
                        // Next-up preview floats beside the jar's neck, always tier-sized
                        // relative to a fixed chip scale so it reads as UI, not gameplay.
                        FruitTier next = session.nextTier();
                        instances.add(new RtScene.FruitInstance(-6.9f, 17.6f, 0f,
                                0.45f + 0.22f * next.radius, next));
                    }

                    // First frame has no history to blend against — write through fully.
                    float blend = frame == 0 ? 1.0f : 0.30f;
                    scene.updateFrame(pipeline, raw, instances,
                            session.hoverX(), session.hoverZ(), playing, cam.frame(time, blend));
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
        System.out.println("[RT Lab] closed - final score " + session.score());
    }

    /**
     * Builds a synthetic {@link GameState} from the session's current fruits and asks
     * {@code driver} for a drop column, exactly like the classic AI-watch view does
     * against a real {@link dev.suika.core.GameCore} — just re-derived from RT world
     * coordinates instead of read straight off a live core, since {@link Rt3DSession}
     * has no 2D {@code GameCore} to snapshot. Both sessions expose fruit X already
     * shifted into RT world space (jar axis at x=0); {@code + 5} recovers the game-space
     * x∈[0,10] the encoder/agents expect. 3D mode's Z is intentionally ignored — no
     * bundled technique was trained on a 3D cross-section, so the agent always aims
     * along the center slice (z=0), same as {@link Rt2DSession} does structurally.
     */
    private static void driveAi(RtGameSession session, AgentPlugin driver, boolean use3dPhysics) {
        // session.drop() below is already a safe no-op while the chute is blocked or
        // on cooldown (see Rt2DSession/Rt3DSession) — no extra gating needed here.
        List<Fruit> fruits = new ArrayList<>();
        int id = 0;
        for (RtGameSession.Ball b : session.fruits()) {
            fruits.add(new Fruit(id++, b.tier(), b.x() + 5.0, b.y(), 0, 0, 0, 0, true));
        }
        GameState synthetic = new GameState(fruits, session.currentTier(), session.nextTier(),
                session.score(), session.score(), session.gameOver(), 0.0, 0, 0);
        ActionSpec spec = ActionSpec.discrete(32);
        Object action = driver.selectAction(synthetic, spec);
        double gx = spec.toDropX(action, PhysicsConfig.DROP_X_MIN, PhysicsConfig.DROP_X_MAX);

        float nx;
        if (use3dPhysics) {
            float worldX = (float) (gx - 5.0);
            nx = worldX / (2f * (float) Jar3DPhysics.PLAY_RADIUS) + 0.5f;
        } else {
            nx = (float) (gx / PhysicsConfig.CONTAINER_WIDTH);
        }
        session.setPointer(nx, 0.5f);
        session.drop();
    }

    /** Studio camera: in front of the jar, slightly above, focused on the fruit
     *  plane so the tan wall far behind lands in bokeh and the table edge near the
     *  lens blurs as foreground. Framed like the reference photo — the full jar
     *  (body, shoulder, open mouth) plus the chute's lower half in view, the
     *  chute's top running out of the frame.
     *
     *  <p>In 3D physics mode the player can right-click-drag to orbit: {@link #orbitYaw}
     *  and {@link #orbitPitch} accumulate mouse delta from RtLabLauncher's cursor
     *  callback and are added to the base framing each frame in {@link #frame}, so
     *  the camera swings around the fixed {@link #TARGET_*} point at a constant
     *  {@link #radius} rather than translating through the scene. */
    private static final class Camera {
        private static final float TARGET_X = 0f, TARGET_Y = 8.4f, TARGET_Z = 0f;
        private static final float BASE_X = 0f, BASE_Y = 12.5f, BASE_Z = 26.5f;
        // Keeps the orbit from flipping over the top or diving under the table.
        private static final float MIN_PITCH = (float) Math.toRadians(-10);
        private static final float MAX_PITCH = (float) Math.toRadians(75);

        final float tanHalfFov = (float) Math.tan(Math.toRadians(28)); // 56° vertical
        final float aperture = 0.34f;
        final float aspect;
        private final float radius, baseYaw, basePitch;

        /** Accumulated right-drag orbit offsets (radians) — mutated from the GLFW
         *  cursor callback thread, read from the render loop; both run on the same
         *  "rtlab" thread so no synchronization is needed. */
        float orbitYaw = 0f, orbitPitch = 0f;

        Camera(int width, int height) {
            aspect = (float) width / height;
            float dx = BASE_X - TARGET_X, dy = BASE_Y - TARGET_Y, dz = BASE_Z - TARGET_Z;
            radius = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            baseYaw = (float) Math.atan2(dx, dz);
            basePitch = (float) Math.asin(dy / radius);
        }

        RtScene.CameraFrame frame(float time, float blend) {
            float yaw = baseYaw + orbitYaw;
            float pitch = Math.max(MIN_PITCH, Math.min(MAX_PITCH, basePitch + orbitPitch));
            float cp = (float) Math.cos(pitch);
            float posX = TARGET_X + radius * cp * (float) Math.sin(yaw);
            float posY = TARGET_Y + radius * (float) Math.sin(pitch);
            float posZ = TARGET_Z + radius * cp * (float) Math.cos(yaw);

            float[] fwd = normalize(TARGET_X - posX, TARGET_Y - posY, TARGET_Z - posZ);
            // right = normalize(cross(fwd, worldUp)) — the sign matters now: mouse-x
            // maps straight to world x, so screen-right MUST be world +X when looking
            // down -Z (the old showcase used the mirrored vector and its symmetric
            // orbiting scene hid it).
            float[] right = normalize(-fwd[2], 0f, fwd[0]);
            float[] up = cross(right, fwd);

            return new RtScene.CameraFrame(posX, posY, posZ,
                    fwd[0], fwd[1], fwd[2], right[0], right[1], right[2], up[0], up[1], up[2],
                    tanHalfFov, time, aperture, radius, blend, aspect);
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
