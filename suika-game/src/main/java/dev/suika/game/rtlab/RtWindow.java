package dev.suika.game.rtlab;

import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkInstance;

import java.nio.LongBuffer;

import static dev.suika.game.rtlab.VkCheck.ok;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/** A GLFW window with no OpenGL/graphics API attached (Vulkan owns the surface instead). */
public final class RtWindow implements AutoCloseable {

    public final long handle;
    public final long surface;
    private final VkInstance instance;

    public RtWindow(VkInstance instance, int width, int height, String title) {
        this.instance = instance;
        if (!GLFWVulkan.glfwVulkanSupported()) {
            throw new IllegalStateException("GLFW reports no Vulkan loader/ICD found on this system.");
        }
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE); // MVP: fixed size, no swapchain-recreate-on-resize yet
        this.handle = glfwCreateWindow(width, height, title, 0L, 0L);
        if (handle == 0L) throw new IllegalStateException("glfwCreateWindow failed");
        this.surface = createSurface();
    }

    private long createSurface() {
        try (MemoryStack stack = stackPush()) {
            LongBuffer pSurface = stack.mallocLong(1);
            ok(GLFWVulkan.glfwCreateWindowSurface(instance, handle, null, pSurface), "glfwCreateWindowSurface");
            return pSurface.get(0);
        }
    }

    public boolean shouldClose() { return glfwWindowShouldClose(handle); }
    public void pollEvents() { glfwPollEvents(); }

    @Override
    public void close() {
        org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR(instance, surface, null);
        glfwDestroyWindow(handle);
    }
}
