package dev.suika.game.rtlab;

import static org.lwjgl.glfw.GLFW.glfwInit;

/** Mimics how RtLabLauncher.launch() actually gets called from the running game:
 *  GLFW already initialized (by the main app's window), launch() called from a
 *  "UI thread", never call glfwTerminate() ourselves. Runs for a few seconds then
 *  exits the process (there's no main window here to keep the JVM alive). */
public final class RtLabIntegrationTest {
    public static void main(String[] args) throws InterruptedException {
        System.setProperty("org.lwjgl.system.stackSize", String.valueOf(RtContext.STACK_SIZE_KB));
        if (!glfwInit()) throw new IllegalStateException("glfwInit failed");
        System.out.println("[test] GLFW initialized (simulating main game window already up)");

        boolean use3d = args.length > 0 && "3d".equalsIgnoreCase(args[0]);
        RtLabLauncher.launch(use3d);
        System.out.println("[test] RtLabLauncher.launch(" + use3d + ") returned immediately (background thread)");

        Thread.sleep(4000);
        System.out.println("[test] SUCCESS: ran for 4s on a background thread without crashing the process");
        System.exit(0);
    }
}
