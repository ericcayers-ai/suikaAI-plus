package dev.suika.game.rtlab;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.Configuration;

import static org.lwjgl.glfw.GLFW.*;

/** Standalone entry point for iterating on {@link RtContext} without going through
 *  the full LibGDX app launch. Not wired into the shipped game — run directly via
 *  `java -cp ... dev.suika.game.rtlab.RtSmokeTest` during development. */
public final class RtSmokeTest {
    public static void main(String[] args) {
        // LWJGL's default per-thread MemoryStack (64KB) is too small for VkInstance's
        // own internal capability probing plus our nested struct allocations — bump it
        // before any Vulkan call touches the stack. See RtContext.STACK_SIZE_BYTES.
        Configuration.STACK_SIZE.set(RtContext.STACK_SIZE_KB);

        if (!glfwInit()) throw new IllegalStateException("glfwInit failed");
        if (!GLFWVulkan.glfwVulkanSupported()) throw new IllegalStateException("GLFW reports no Vulkan loader found");

        PointerBuffer requiredExtensions = GLFWVulkan.glfwGetRequiredInstanceExtensions();
        if (requiredExtensions == null) throw new IllegalStateException("glfwGetRequiredInstanceExtensions failed");

        System.out.println("Creating Vulkan instance + RT-capable device...");
        try (RtContext ctx = new RtContext(requiredExtensions)) {
            System.out.println("SUCCESS");
            System.out.println("  device            = " + ctx.deviceName);
            System.out.println("  graphicsQueueFam   = " + ctx.graphicsQueueFamily);
            System.out.println("  validation layers  = " + ctx.validationEnabled);
        }
        glfwTerminate();
    }
}
