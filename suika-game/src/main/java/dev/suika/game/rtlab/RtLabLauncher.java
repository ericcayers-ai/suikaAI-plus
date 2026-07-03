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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
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
 * bokeh). Move the mouse to aim, click to drop, R restarts. The denoiser is
 * always on. The score and next fruit live in the window title (rendering crisp
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
    /** Set when the most recent launch attempt failed (e.g. no Vulkan RT support on
     *  this GPU/driver); cleared at the start of every new attempt. {@code null} means
     *  either nothing has been tried yet or the last attempt succeeded. RT Lab needs no
     *  up-front capability gate any more — the caller (MainMenuScreen) just launches
     *  and, if {@link #isRunning()} never goes true, reads this for a friendly message. */
    private static volatile String lastFailure = null;

    /** True while an RT Lab window is open — the rest of the app can use this to
     *  shed GPU-hungry work (e.g. the control center clamps its multi-board view). */
    public static boolean isRunning() { return running; }

    /** Why the most recent launch attempt failed, or {@code null} if the last attempt
     *  (if any) succeeded. */
    public static String lastFailure() { return lastFailure; }

    private static boolean inRect(float x, float y, int rx, int ry, int rw, int rh) {
        return x >= rx && x <= rx + rw && y >= ry && y <= ry + rh;
    }

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
        lastFailure = null;
        Thread t = new Thread(() -> run(use3dPhysics, aiDriver), "rtlab");
        t.setDaemon(true);
        t.start();
    }

    private static void run(boolean use3dPhysics, AgentPlugin aiDriver) {
        try {
            runUnsafe(use3dPhysics, aiDriver);
        } catch (Throwable t) {
            String reason = friendlyFailureReason(t);
            lastFailure = reason;
            System.err.println("[RT Lab] Not available on this system: " + t);
        } finally {
            running = false;
        }
    }

    /** Turns a raw exception into something worth showing a player, rather than a
     *  stack-trace-flavoured string. Falls back to the exception's own message. */
    private static String friendlyFailureReason(Throwable t) {
        String msg = String.valueOf(t.getMessage());
        if (msg.contains("Vulkan not supported")) return "This GPU/driver has no Vulkan support.";
        if (msg.toLowerCase(java.util.Locale.ROOT).contains("ray tracing")
                || msg.toLowerCase(java.util.Locale.ROOT).contains("raytracing"))
            return "This GPU doesn't support hardware ray tracing.";
        if (msg.contains("glfwInit failed")) return "Couldn't initialise the window system.";
        return "RT Lab isn't available on this system (" + t.getClass().getSimpleName() + ").";
    }

    private static void runUnsafe(boolean use3dPhysics, AgentPlugin aiDriver) throws Exception {
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

            // §5: HUD is cheap to build (Java2D + one small staging upload — no shader
            // compilation, no photo textures, no BLAS/TLAS builds), so it's created
            // FIRST and used to paint + present a branded loading frame before any of
            // the genuinely expensive resources below start loading. That frame stays
            // on screen (GLFW retains the last presented buffer) for however long that
            // takes — a real loading screen, not a simulated delay.
            // Optional one-shot visual QA capture: this window is a separate
            // GLFW/Vulkan surface the LibGDX-side CaptureHarness can never reach, so
            // -Dsuika.rt.capture.dir=<dir> drives a small scripted sequence — a couple
            // of drops, a few screenshots, then a clean exit (see the switch below).
            String rtCaptureDir = System.getProperty("suika.rt.capture.dir");

            try (RtHud hud = new RtHud(ctx.physicalDevice, ctx.device, commandPool, ctx.graphicsQueue, width, height)) {
                showLoadingScreen(ctx, swap, commandPool, hud, width, height, rtCaptureDir);

                try (RtOutputImage raw = new RtOutputImage(ctx.physicalDevice, ctx.device, commandPool, ctx.graphicsQueue, width, height);
                     RtOutputImage denoised = new RtOutputImage(ctx.physicalDevice, ctx.device, commandPool, ctx.graphicsQueue, width, height);
                     // The frame actually presented: raw/denoised copied here first, then the
                     // HUD composites onto it — never onto raw, which doubles as the temporal
                     // accumulation history (UI pixels would bleed into next frame's EMA).
                     RtOutputImage present = new RtOutputImage(ctx.physicalDevice, ctx.device, commandPool, ctx.graphicsQueue, width, height);
                     RtPipeline pipeline = new RtPipeline(ctx.physicalDevice, ctx.device);
                     RtDenoiser denoiser = new RtDenoiser(ctx.device, raw, denoised);
                     // Bloom doubles as the old "copy denoised onto present" step (see its
                     // class doc) — reads the denoised frame, writes the bloomed result
                     // straight into present, no separate vkCmdCopyImage needed any more.
                     RtBloom bloom = new RtBloom(ctx.device, denoised, present);
                     RtHudCompositor hudCompositor = new RtHudCompositor(ctx.device, present, hud);
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

                // §5 pause menu state + the live graphics settings it exposes (bloom,
                // denoise, depth of field, temporal accumulation) — see
                // RtGraphicsSettings for why the set is scoped to knobs that don't
                // need a swapchain rebuild.
                boolean[] paused = {false};
                RtGraphicsSettings gfx = new RtGraphicsSettings();
                boolean[] trayExpanded = {true};

                // Always on now (was a D-key toggle) — the improved multi-sample
                // shadow/DoF path above still benefits from denoising, and there's no
                // good reason a player would want to see raw RT noise in normal play.
                glfwSetKeyCallback(window.handle, (win, key, scancode, action, mods) -> {
                    if (action != GLFW_PRESS) return;
                    if (key == GLFW_KEY_R) {
                        session.reset();
                    } else if (key == GLFW_KEY_ESCAPE) {
                        paused[0] = !paused[0];
                    }
                });
                glfwSetCursorPosCallback(window.handle, (win, cx, cy) -> {
                    float fx = (float) cx, fy = (float) cy;
                    if (orbiting[0]) {
                        cam.orbitYaw   += (fx - lastCursor[0]) * ORBIT_SENSITIVITY;
                        cam.orbitPitch -= (fy - lastCursor[1]) * ORBIT_SENSITIVITY;
                    } else if (aiDriver == null && !paused[0]) {
                        session.setPointer(fx / width, fy / height);
                    }
                    lastCursor[0] = fx; lastCursor[1] = fy;
                });
                glfwSetMouseButtonCallback(window.handle, (win, button, action, mods) -> {
                    if (button != GLFW_MOUSE_BUTTON_LEFT || action != GLFW_PRESS) {
                        if (button == GLFW_MOUSE_BUTTON_RIGHT) orbiting[0] = use3dPhysics && action == GLFW_PRESS;
                        return;
                    }
                    float mx = lastCursor[0], my = lastCursor[1];
                    if (paused[0]) {
                        // Rows 0-5 share RtHud's pauseBtnY(i) geometry exactly.
                        for (int i = 0; i < RtHud.PAUSE_BTN_COUNT; i++) {
                            if (!inRect(mx, my, hud.pauseBtnX(), hud.pauseBtnY(i), hud.pauseBtnW(), hud.pauseBtnH())) continue;
                            switch (i) {
                                case 0 -> paused[0] = false;
                                case 1 -> gfx.toggleBloom();
                                case 2 -> gfx.toggleDenoise();
                                case 3 -> gfx.cycleDof();
                                case 4 -> gfx.cycleAccum();
                                default -> glfwSetWindowShouldClose(win, true);
                            }
                            break;
                        }
                    } else if (inRect(mx, my, hud.trayArrowX(), hud.trayArrowY(), hud.trayArrowSize(), hud.trayArrowSize())) {
                        trayExpanded[0] = !trayExpanded[0];
                    } else if (aiDriver == null) {
                        session.drop();
                    }
                });
                glfwSetScrollCallback(window.handle, (win, sx, sy) -> cam.zoom((float) sy));

                VkCommandBuffer cmd = allocateCommandBuffer(ctx.device, commandPool);
                long imageAvailable = createSemaphore(ctx.device);
                long renderFinished = createSemaphore(ctx.device);
                long inFlight = createFence(ctx.device);

                glfwSetWindowTitle(window.handle, "Suika RT Lab — " + session.modeName()
                        + (aiDriver != null ? " — AI: " + aiDriver.displayName() : ""));

                long startNs = System.nanoTime();
                long lastNs = startNs;
                long frame = 0;
                long lastHudScore = -1;
                FruitTier lastHudNext = null;
                boolean lastHudOver = false;
                boolean lastHudPaused = false;
                int lastHudGfxRevision = -1;
                boolean lastHudTrayExpanded = true;
                // AI autoplay paces itself like the classic AI-watch view rather than
                // dropping every frame. The actual selectAction runs on its own thread
                // (aiExec) — planning agents like MCTS think for hundreds of ms, and
                // running that on the render thread froze the whole window every move.
                final float AI_MOVE_INTERVAL = 0.55f;
                final float AI_RESTART_DELAY = 2.0f;
                float aiTimer = 0.35f;
                float overTimer = 0f;
                java.util.concurrent.ExecutorService aiExec = aiDriver == null ? null
                        : java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                            Thread t = new Thread(r, "rtlab-ai");
                            t.setDaemon(true);
                            return t;
                        });
                java.util.concurrent.Future<Double> aiFuture = null;
                RtMergeFx mergeFx = new RtMergeFx();

                int rtCaptureStage = 0;

                while (!window.shouldClose()) {
                    window.pollEvents();
                    long nowNs = System.nanoTime();
                    float dt = Math.min((nowNs - lastNs) / 1e9f, 0.05f);
                    lastNs = nowNs;
                    float time = (float) ((nowNs - startNs) / 1e9);

                    if (!paused[0]) {
                        session.step(dt);
                        mergeFx.spawn(session.drainMerges());
                        mergeFx.update(dt);
                    }

                    if (aiDriver != null && !paused[0]) {
                        if (session.gameOver()) {
                            // Keep rounds flowing: a short beat to read the final score,
                            // then a fresh game — no waiting on a keypress between rounds.
                            if (aiFuture != null) { aiFuture.cancel(false); aiFuture = null; }
                            overTimer += dt;
                            if (overTimer >= AI_RESTART_DELAY) {
                                session.reset();
                                overTimer = 0f;
                                aiTimer = 0.35f;
                            }
                        } else {
                            overTimer = 0f;
                            aiTimer -= dt;
                            if (aiFuture == null && aiTimer <= 0f) {
                                // Snapshot on the render thread (cheap), think on the AI
                                // thread, apply next loop iteration once the future lands.
                                GameState snapshot = syntheticState(session);
                                aiFuture = aiExec.submit(() -> chooseDropX(aiDriver, snapshot));
                            } else if (aiFuture != null && aiFuture.isDone()) {
                                try {
                                    applyAiDrop(session, aiFuture.get(), use3dPhysics);
                                } catch (Exception e) {
                                    System.err.println("[RT Lab] AI move failed: " + e);
                                }
                                aiFuture = null;
                                aiTimer = AI_MOVE_INTERVAL;
                            }
                        }
                    }

                    List<RtScene.FruitInstance> instances = new ArrayList<>();
                    for (RtGameSession.Ball b : session.fruits()) {
                        instances.add(new RtScene.FruitInstance(b.x(), b.y(), b.z(), b.radius(), b.tier()));
                    }
                    boolean playing = !session.gameOver();
                    if (playing) {
                        // The pending fruit hangs from the chute's mouth with just its
                        // top quarter inside the tube — held in the grip of the opening,
                        // about to release, rather than floating loose inside the barrel.
                        // (The next-up preview now lives in the HUD, not the scene.)
                        FruitTier cur = session.currentTier();
                        instances.add(new RtScene.FruitInstance(session.hoverX(),
                                RtScene.CHUTE_BOTTOM_Y - cur.radius * 0.75f,
                                session.hoverZ(), cur.radius, cur));
                    }
                    mergeFx.appendTo(instances);

                    // First frame has no history to blend against — write through fully.
                    // Otherwise the temporal-accumulation strength is a live pause-menu
                    // graphics setting, as is the thin-lens aperture (depth of field).
                    float blend = frame == 0 ? 1.0f : gfx.accumBlend();
                    cam.aperture = gfx.aperture();
                    scene.updateFrame(pipeline, raw, instances,
                            session.hoverX(), session.hoverZ(), playing, cam.frame(time, blend));
                    frame++;

                    // Redraw the HUD overlay only when its content changed. Safe here:
                    // updateFrame's one-shot TLAS build just waited the queue idle, so no
                    // in-flight copy can still be reading the HUD staging buffer.
                    if (session.score() != lastHudScore || session.nextTier() != lastHudNext
                            || session.gameOver() != lastHudOver || paused[0] != lastHudPaused
                            || gfx.revision != lastHudGfxRevision || trayExpanded[0] != lastHudTrayExpanded) {
                        lastHudScore = session.score();
                        lastHudNext = session.nextTier();
                        lastHudOver = session.gameOver();
                        lastHudPaused = paused[0];
                        lastHudGfxRevision = gfx.revision;
                        lastHudTrayExpanded = trayExpanded[0];
                        hud.draw(lastHudScore, lastHudNext, session.modeName(),
                                aiDriver != null ? aiDriver.displayName() : null,
                                lastHudOver, use3dPhysics, paused[0], gfx, trayExpanded[0]);
                    }

                    renderFrame(ctx, swap, pipeline, denoiser, bloom, gfx, raw, denoised, present, hud, hudCompositor,
                            cmd, imageAvailable, renderFinished, inFlight, width, height);

                    if (rtCaptureDir != null) {
                        switch (rtCaptureStage) {
                            case 0 -> { if (time > 0.6f) {
                                vkDeviceWaitIdle(ctx.device);
                                savePng(ctx.physicalDevice, ctx.device, commandPool, ctx.graphicsQueue,
                                        present, width, height, rtCaptureDir + "/rt-01-chute.png");
                                rtCaptureStage = 1;
                            } }
                            case 1 -> { if (time > 0.8f) { session.setPointer(0.3f, 0.5f); session.drop(); rtCaptureStage = 2; } }
                            case 2 -> { if (time > 1.6f) { session.setPointer(0.5f, 0.5f); session.drop(); rtCaptureStage = 3; } }
                            case 3 -> { if (time > 2.4f) { session.setPointer(0.7f, 0.5f); session.drop(); rtCaptureStage = 4; } }
                            case 4 -> { if (time > 3.6f) {
                                vkDeviceWaitIdle(ctx.device);
                                savePng(ctx.physicalDevice, ctx.device, commandPool, ctx.graphicsQueue,
                                        present, width, height, rtCaptureDir + "/rt-02-jar.png");
                                // §4 merge FX: gameplay RNG won't reliably produce a same-tier
                                // merge in a short scripted capture, so force one deterministically
                                // — this is exactly what needs visual verification, not whether
                                // random drops happen to touch.
                                mergeFx.spawn(List.of(new RtGameSession.MergeInfo(0f, 10f, 0f, FruitTier.APPLE)));
                                rtCaptureStage = 45;
                            } }
                            case 45 -> { if (time > 3.75f) {
                                vkDeviceWaitIdle(ctx.device);
                                savePng(ctx.physicalDevice, ctx.device, commandPool, ctx.graphicsQueue,
                                        present, width, height, rtCaptureDir + "/rt-03-mergefx.png");
                                // §5: open the pause menu for a shot too.
                                paused[0] = true;
                                rtCaptureStage = 46;
                            } }
                            case 46 -> { if (time > 3.95f) {
                                vkDeviceWaitIdle(ctx.device);
                                savePng(ctx.physicalDevice, ctx.device, commandPool, ctx.graphicsQueue,
                                        present, width, height, rtCaptureDir + "/rt-04-pause.png");
                                paused[0] = false;
                                rtCaptureStage = 5;
                            } }
                            default -> glfwSetWindowShouldClose(window.handle, true);
                        }
                    }
                }
                if (aiExec != null) aiExec.shutdownNow();
                vkDeviceWaitIdle(ctx.device);

                vkDestroySemaphore(ctx.device, imageAvailable, null);
                vkDestroySemaphore(ctx.device, renderFinished, null);
                vkDestroyFence(ctx.device, inFlight, null);
                }
            }
            swap.close();
            vkDestroyCommandPool(ctx.device, commandPool, null);
        }
        System.out.println("[RT Lab] closed - final score " + session.score());
    }

    /**
     * Builds a synthetic {@link GameState} from the session's current fruits, exactly
     * like the classic AI-watch view reads a real {@link dev.suika.core.GameCore} —
     * just re-derived from RT world coordinates, since {@link Rt3DSession} has no 2D
     * {@code GameCore} to snapshot. Both sessions expose fruit X already shifted into
     * RT world space (jar axis at x=0); {@code + 5} recovers the game-space x∈[0,10]
     * the encoder/agents expect. Runs on the render thread (cheap: a list copy), so
     * the AI thread never touches the live session.
     */
    private static GameState syntheticState(RtGameSession session) {
        List<Fruit> fruits = new ArrayList<>();
        int id = 0;
        for (RtGameSession.Ball b : session.fruits()) {
            fruits.add(new Fruit(id++, b.tier(), b.x() + 5.0, b.y(), 0, 0, 0, 0, true));
        }
        return new GameState(fruits, session.currentTier(), session.nextTier(),
                session.score(), session.score(), session.gameOver(), 0.0, 0, 0);
    }

    /** The potentially-slow part (MCTS thinks for hundreds of ms) — runs on the
     *  dedicated "rtlab-ai" thread against the immutable snapshot, never the session. */
    private static double chooseDropX(AgentPlugin driver, GameState snapshot) {
        ActionSpec spec = ActionSpec.discrete(32);
        Object action = driver.selectAction(snapshot, spec);
        return spec.toDropX(action, PhysicsConfig.DROP_X_MIN, PhysicsConfig.DROP_X_MAX);
    }

    /** Applies a chosen drop back on the render thread. 3D mode's Z is intentionally
     *  center-slice (z=0) — no bundled technique was trained on a 3D cross-section.
     *  session.drop() is already a safe no-op while the chute is blocked or on
     *  cooldown (see Rt2DSession/Rt3DSession), so no extra gating is needed here. */
    private static void applyAiDrop(RtGameSession session, double gx, boolean use3dPhysics) {
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
        /** Scroll-wheel zoom bounds. ZOOM_MAX must stay well inside |RtScene.WALL_Z|
         *  so a fully zoomed-out, fully orbited camera can never reach the backdrop. */
        private static final float ZOOM_MIN = 15f, ZOOM_MAX = 40f;

        final float tanHalfFov = (float) Math.tan(Math.toRadians(28)); // 56° vertical
        // Thin-lens aperture — set every frame from the pause menu's DEPTH OF FIELD
        // setting (0 = pinhole, 0.55 = the cinematic default the scene was framed for).
        float aperture = 0.55f;
        final float aspect;
        private final float baseYaw, basePitch;
        private float radius;

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

        /** Scroll-wheel zoom: dolly toward/away from the jar along the view ray.
         *  Focus distance tracks the radius (see {@link #frame}), so the jar stays
         *  on the focal plane at every zoom level. */
        void zoom(float scrollY) {
            radius = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, radius - scrollY * 1.6f));
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

    /** Draws + presents ONE branded loading frame, synchronously, before the caller
     *  goes on to build the genuinely expensive RT resources — see the call site's
     *  comment. Self-contained sync primitives (not the main loop's) since this runs
     *  well before those exist. */
    private static void showLoadingScreen(RtContext ctx, RtSwapchain swap, long commandPool, RtHud hud,
                                           int width, int height, String rtCaptureDir) {
        hud.drawLoading("Loading the studio scene...");

        VkCommandBuffer cmd = allocateCommandBuffer(ctx.device, commandPool);
        long imageAvailable = createSemaphore(ctx.device);
        long renderFinished = createSemaphore(ctx.device);
        long inFlight = createFence(ctx.device);
        try (MemoryStack stack = stackPush()) {
            java.nio.IntBuffer pIndex = stack.mallocInt(1);
            ok(vkAcquireNextImageKHR(ctx.device, swap.handle, Long.MAX_VALUE, imageAvailable, VK_NULL_HANDLE, pIndex),
                    "vkAcquireNextImageKHR (loading)");
            int imageIndex = pIndex.get(0);
            long swapImage = swap.images[imageIndex];

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            ok(vkBeginCommandBuffer(cmd, beginInfo), "vkBeginCommandBuffer (loading)");

            hud.recordUpload(cmd);
            transition(stack, cmd, swapImage, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT);

            // hud.image is already fully opaque (drawLoading fills every pixel, unlike
            // the transparent chrome draw() produces for alpha-compositing) — a plain
            // blit onto the swapchain is enough, no clear or compositor pass needed.
            VkMemoryBarrier.Buffer preBlit = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, preBlit, null, null);

            VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
            blit.get(0).srcSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1));
            blit.get(0).srcOffsets(0, o -> o.set(0, 0, 0));
            blit.get(0).srcOffsets(1, o -> o.set(swap.width, swap.height, 1));
            blit.get(0).dstSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1));
            blit.get(0).dstOffsets(0, o -> o.set(0, 0, 0));
            blit.get(0).dstOffsets(1, o -> o.set(swap.width, swap.height, 1));
            vkCmdBlitImage(cmd, hud.image, VK_IMAGE_LAYOUT_GENERAL, swapImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    blit, VK_FILTER_LINEAR);

            transition(stack, cmd, swapImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_ACCESS_TRANSFER_WRITE_BIT, 0);
            ok(vkEndCommandBuffer(cmd), "vkEndCommandBuffer (loading)");

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .waitSemaphoreCount(1)
                    .pWaitSemaphores(stack.longs(imageAvailable))
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_TRANSFER_BIT))
                    .pCommandBuffers(stack.pointers(cmd))
                    .pSignalSemaphores(stack.longs(renderFinished));
            ok(vkQueueSubmit(ctx.graphicsQueue, submitInfo, inFlight), "vkQueueSubmit (loading)");

            VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                    .pWaitSemaphores(stack.longs(renderFinished))
                    .swapchainCount(1)
                    .pSwapchains(stack.longs(swap.handle))
                    .pImageIndices(stack.ints(imageIndex));
            ok(vkQueuePresentKHR(ctx.graphicsQueue, presentInfo), "vkQueuePresentKHR (loading)");

            ok(vkWaitForFences(ctx.device, inFlight, true, Long.MAX_VALUE), "vkWaitForFences (loading)");

            // QA-only: the swapchain image is mid-present-ownership right after this
            // (awkward to read back safely), so verify via hud.image instead — it's
            // exactly the pixels that were blitted, just not through the swapchain's
            // own format. Good enough to confirm drawLoading()'s content is correct.
            if (rtCaptureDir != null) {
                try {
                    saveHudPng(ctx.physicalDevice, ctx.device, commandPool, ctx.graphicsQueue, hud,
                            width, height, rtCaptureDir + "/rt-00-loading.png");
                } catch (Exception e) {
                    System.err.println("[RT Lab] loading-screen capture failed: " + e);
                }
            }
        } finally {
            vkDestroySemaphore(ctx.device, imageAvailable, null);
            vkDestroySemaphore(ctx.device, renderFinished, null);
            vkDestroyFence(ctx.device, inFlight, null);
        }
    }

    /** QA-only readback of {@link RtHud}'s RGBA8 image (used to verify the loading
     *  screen's content — see {@link #showLoadingScreen}). */
    private static void saveHudPng(VkPhysicalDevice pd, VkDevice device, long commandPool, VkQueue queue,
                                    RtHud hud, int w, int h, String path) throws Exception {
        long bufSize = (long) w * h * 4;
        RtBuffer staging = new RtBuffer(pd, device, bufSize,
                VK_BUFFER_USAGE_TRANSFER_DST_BIT, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        try {
            OneShotCommands.submit(device, commandPool, queue, cmd -> {
                try (MemoryStack stack = stackPush()) {
                    VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack)
                            .bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
                    region.get(0).imageSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1));
                    region.get(0).imageOffset(o -> o.set(0, 0, 0));
                    region.get(0).imageExtent(e -> e.width(w).height(h).depth(1));
                    vkCmdCopyImageToBuffer(cmd, hud.image, VK_IMAGE_LAYOUT_GENERAL, staging.buffer, region);
                }
            });
            try (MemoryStack stack = stackPush()) {
                PointerBuffer pData = stack.mallocPointer(1);
                ok(vkMapMemory(device, staging.memory, 0, bufSize, 0, pData), "vkMapMemory (hud capture)");
                ByteBuffer pixels = org.lwjgl.system.MemoryUtil.memByteBuffer(pData.get(0), (int) bufSize);
                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int base = (y * w + x) * 4;
                        int r = pixels.get(base) & 0xFF, g = pixels.get(base + 1) & 0xFF,
                            b = pixels.get(base + 2) & 0xFF, a = pixels.get(base + 3) & 0xFF;
                        img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                    }
                }
                vkUnmapMemory(device, staging.memory);
                ImageIO.write(img, "png", new File(path));
                System.out.println("[RT Lab] wrote " + path);
            }
        } finally {
            staging.close();
        }
    }

    private static void renderFrame(RtContext ctx, RtSwapchain swap, RtPipeline pipeline, RtDenoiser denoiser,
                                     RtBloom bloom, RtGraphicsSettings gfx, RtOutputImage raw, RtOutputImage denoised, RtOutputImage present,
                                     RtHud hud, RtHudCompositor hudCompositor,
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

            // The next consumer of the RT write is either the denoiser's compute pass
            // or (DENOISE OFF — a live pause-menu graphics setting) a plain image copy
            // of the raw traced frame into the denoised slot, so the rest of the chain
            // (bloom/composite) is identical either way. The source image's layout is
            // already GENERAL for both — a memory barrier is enough.
            boolean denoiseOn = gfx.denoise;
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(denoiseOn ? (VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT)
                                             : (VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT));
            vkCmdPipelineBarrier(cmd,
                    org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    denoiseOn ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT : VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0, barrier, null, null);
            if (denoiseOn) {
                denoiser.dispatch(cmd, width, height);
            } else {
                VkImageCopy.Buffer rawCopy = VkImageCopy.calloc(1, stack);
                rawCopy.get(0).srcSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1));
                rawCopy.get(0).dstSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1));
                rawCopy.get(0).extent(e -> e.width(width).height(height).depth(1));
                vkCmdCopyImage(cmd, raw.image, VK_IMAGE_LAYOUT_GENERAL, denoised.image, VK_IMAGE_LAYOUT_GENERAL, rawCopy);
            }

            long swapImage = swap.images[imageIndex];
            transition(stack, cmd, swapImage, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT);

            // Bloom reads the just-denoised frame and writes the bloomed result onto
            // the dedicated present image (replacing the old plain vkCmdCopyImage —
            // see RtBloom's class doc) — never onto raw, which doubles as the
            // temporal-accumulation history (UI/bloom pixels would bleed into next
            // frame's EMA blend if written there instead). The one live-updating
            // graphics setting the pause menu exposes: BLOOM ON falls through to the
            // compute pass above; OFF does a plain image copy instead — same
            // destination, same barrier shape either way.
            boolean bloomOn = gfx.bloom;
            VkMemoryBarrier.Buffer preBloom = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    // denoised's last write was the denoiser's compute pass, or the
                    // raw->denoised transfer copy when DENOISE is off.
                    .srcAccessMask(denoiseOn ? VK_ACCESS_SHADER_WRITE_BIT : VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(bloomOn ? (VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT)
                                           : (VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT));
            vkCmdPipelineBarrier(cmd, denoiseOn ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT : VK_PIPELINE_STAGE_TRANSFER_BIT,
                    bloomOn ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT : VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0, preBloom, null, null);
            if (bloomOn) {
                bloom.dispatch(cmd, width, height);
            } else {
                VkImageCopy.Buffer copy = VkImageCopy.calloc(1, stack);
                copy.get(0).srcSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1));
                copy.get(0).dstSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1));
                copy.get(0).extent(e -> e.width(width).height(height).depth(1));
                vkCmdCopyImage(cmd, denoised.image, VK_IMAGE_LAYOUT_GENERAL, present.image, VK_IMAGE_LAYOUT_GENERAL, copy);
            }

            hud.recordUpload(cmd);

            // present's last write came from bloom's compute pass when bloom is on,
            // or the plain copy above when it's off — the barrier into the
            // compositor's compute read has to match whichever actually happened.
            VkMemoryBarrier.Buffer preComposite = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(bloomOn ? VK_ACCESS_SHADER_WRITE_BIT : VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
            vkCmdPipelineBarrier(cmd, bloomOn ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT : VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, preComposite, null, null);

            hudCompositor.dispatch(cmd, width, height);

            VkMemoryBarrier.Buffer preBlit = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0, preBlit, null, null);

            VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
            blit.get(0).srcSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1));
            blit.get(0).srcOffsets(0, o -> o.set(0, 0, 0));
            blit.get(0).srcOffsets(1, o -> o.set(width, height, 1));
            blit.get(0).dstSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1));
            blit.get(0).dstOffsets(0, o -> o.set(0, 0, 0));
            blit.get(0).dstOffsets(1, o -> o.set(swap.width, swap.height, 1));
            vkCmdBlitImage(cmd, present.image, VK_IMAGE_LAYOUT_GENERAL, swapImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
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

    /** Copies an RGBA32F {@link RtOutputImage} to a host-visible staging buffer and
     *  writes it out as a PNG — the {@code -Dsuika.rt.capture.dir} QA path's only way
     *  to see this window's contents (mirrors {@link RtTraceTest#savePng}). Caller
     *  must have already waited the device idle so the image contents are final. */
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
                System.out.println("[RT Lab] wrote " + path);
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
