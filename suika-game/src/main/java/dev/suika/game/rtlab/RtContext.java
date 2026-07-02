package dev.suika.game.rtlab;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.HashSet;
import java.util.Set;

import static dev.suika.game.rtlab.VkCheck.ok;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Owns a Vulkan instance + a ray-tracing-capable logical device. Entirely separate
 * from LibGDX's OpenGL context — the RT Lab renders in its own window with its own
 * graphics API, because hardware ray tracing (VK_KHR_ray_tracing_pipeline) has no
 * OpenGL equivalent and LibGDX ships no Vulkan backend to plug into.
 *
 * <p>This is genuinely experimental: it requires a real ray-tracing-capable GPU
 * (NVIDIA RTX / AMD RDNA2+ / Intel Arc) and an up-to-date driver. {@link RtLabLauncher}
 * catches every failure from this class and reports it plainly instead of crashing
 * the rest of the app — the main 2D game never depends on any of this working.
 */
public final class RtContext implements AutoCloseable {

    /** LWJGL's default per-thread MemoryStack is too small once VkInstance's own
     *  internal capability probing (device/extension enumeration) nests inside a stack
     *  frame that also holds our own struct chain — bump it before any Vulkan call.
     *  {@code Configuration.STACK_SIZE} is in KILOBYTES, not bytes. Callers must set
     *  {@code Configuration.STACK_SIZE.set(STACK_SIZE_KB)} first. */
    public static final int STACK_SIZE_KB = 4096; // 4 MiB

    public final VkInstance instance;
    public final VkPhysicalDevice physicalDevice;
    public final VkDevice device;
    public final int graphicsQueueFamily;
    public final VkQueue graphicsQueue;
    public final String deviceName;
    public final boolean validationEnabled;

    private final long debugMessenger;

    /** Device extensions every stage of the RT Lab needs, on top of the swapchain. */
    static final String[] RT_DEVICE_EXTENSIONS = {
        VK_KHR_SWAPCHAIN_EXTENSION_NAME,
        VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,
        VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME,
        VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME,
    };

    public RtContext(PointerBuffer requiredSurfaceExtensions) {
        try (MemoryStack stack = stackPush()) {
            boolean wantValidation = layerAvailable(stack, "VK_LAYER_KHRONOS_validation");
            this.instance = createInstance(stack, requiredSurfaceExtensions, wantValidation);
            this.validationEnabled = wantValidation;
            this.debugMessenger = wantValidation ? createDebugMessenger(stack) : NULL;
            this.physicalDevice = pickPhysicalDevice(stack, instance);
            this.deviceName = physicalDeviceName(stack, physicalDevice);
            this.graphicsQueueFamily = findGraphicsQueueFamily(stack, physicalDevice);
            this.device = createLogicalDevice(stack, physicalDevice, graphicsQueueFamily, wantValidation);
            PointerBuffer pQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(device, graphicsQueueFamily, 0, pQueue);
            this.graphicsQueue = new VkQueue(pQueue.get(0), device);
        }
    }

    // ---- instance ----

    private static boolean layerAvailable(MemoryStack stack, String name) {
        IntBuffer count = stack.mallocInt(1);
        ok(vkEnumerateInstanceLayerProperties(count, null), "enumerate instance layers (count)");
        if (count.get(0) == 0) return false;
        VkLayerProperties.Buffer layers = VkLayerProperties.malloc(count.get(0), stack);
        ok(vkEnumerateInstanceLayerProperties(count, layers), "enumerate instance layers");
        for (VkLayerProperties l : layers) if (l.layerNameString().equals(name)) return true;
        return false;
    }

    private static VkInstance createInstance(MemoryStack stack, PointerBuffer surfaceExtensions, boolean validation) {
        VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pApplicationName(stack.UTF8("Suika RT Lab"))
                .applicationVersion(VK_MAKE_VERSION(0, 8, 0))
                .pEngineName(stack.UTF8("SuikaRT"))
                .engineVersion(VK_MAKE_VERSION(0, 8, 0))
                .apiVersion(VK_API_VERSION_1_2);

        int extraCount = validation ? 1 : 0;
        PointerBuffer extensions = stack.mallocPointer(surfaceExtensions.remaining() + extraCount);
        extensions.put(surfaceExtensions);
        surfaceExtensions.rewind();
        if (validation) extensions.put(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME));
        extensions.flip();

        VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pApplicationInfo(appInfo)
                .ppEnabledExtensionNames(extensions); // enabledExtensionCount is a read-only, LWJGL derives it from this buffer's length

        if (validation) {
            PointerBuffer layers = stack.mallocPointer(1);
            layers.put(0, stack.UTF8("VK_LAYER_KHRONOS_validation"));
            createInfo.ppEnabledLayerNames(layers);
        }

        PointerBuffer pInstance = stack.mallocPointer(1);
        ok(vkCreateInstance(createInfo, null, pInstance), "vkCreateInstance");
        return new VkInstance(pInstance.get(0), createInfo);
    }

    private long createDebugMessenger(MemoryStack stack) {
        VkDebugUtilsMessengerCreateInfoEXT createInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT)
                .messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT
                        | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                .messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT
                        | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                        | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                .pfnUserCallback((messageSeverity, messageTypes, pCallbackData, pUserData) -> {
                    VkDebugUtilsMessengerCallbackDataEXT data = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
                    System.err.println("[vk-validation] " + data.pMessageString());
                    return VK_FALSE;
                });
        LongBuffer pMessenger = stack.mallocLong(1);
        int res = vkCreateDebugUtilsMessengerEXT(instance, createInfo, null, pMessenger);
        if (res != VK_SUCCESS) return NULL; // non-fatal: validation is best-effort, not required
        return pMessenger.get(0);
    }

    // ---- physical device ----

    private static VkPhysicalDevice pickPhysicalDevice(MemoryStack stack, VkInstance instance) {
        IntBuffer count = stack.mallocInt(1);
        ok(vkEnumeratePhysicalDevices(instance, count, null), "enumerate physical devices (count)");
        int n = count.get(0);
        if (n == 0) throw new IllegalStateException("No Vulkan-capable GPU found on this system.");
        PointerBuffer devices = stack.mallocPointer(n);
        ok(vkEnumeratePhysicalDevices(instance, count, devices), "enumerate physical devices");

        VkPhysicalDevice best = null;
        boolean bestIsDiscrete = false;
        for (int i = 0; i < n; i++) {
            VkPhysicalDevice pd = new VkPhysicalDevice(devices.get(i), instance);
            if (!supportsRtExtensions(stack, pd)) continue;
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(pd, props);
            boolean discrete = props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU;
            if (best == null || (discrete && !bestIsDiscrete)) {
                best = pd;
                bestIsDiscrete = discrete;
            }
        }
        if (best == null) {
            throw new IllegalStateException(
                "No GPU on this system reports support for hardware ray tracing " +
                "(VK_KHR_ray_tracing_pipeline + VK_KHR_acceleration_structure). RT Lab needs " +
                "an NVIDIA RTX, AMD RDNA2+, or Intel Arc GPU with an up-to-date driver.");
        }
        return best;
    }

    private static boolean supportsRtExtensions(MemoryStack stack, VkPhysicalDevice pd) {
        IntBuffer count = stack.mallocInt(1);
        ok(vkEnumerateDeviceExtensionProperties(pd, (String) null, count, null), "enumerate device extensions (count)");
        int n = count.get(0);
        if (n == 0) return false;
        VkExtensionProperties.Buffer exts = VkExtensionProperties.malloc(n, stack);
        ok(vkEnumerateDeviceExtensionProperties(pd, (String) null, count, exts), "enumerate device extensions");
        Set<String> names = new HashSet<>();
        for (VkExtensionProperties e : exts) names.add(e.extensionNameString());
        for (String required : RT_DEVICE_EXTENSIONS) if (!names.contains(required)) return false;
        return true;
    }

    private static String physicalDeviceName(MemoryStack stack, VkPhysicalDevice pd) {
        VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
        vkGetPhysicalDeviceProperties(pd, props);
        return props.deviceNameString();
    }

    static int findGraphicsQueueFamily(MemoryStack stack, VkPhysicalDevice pd) {
        IntBuffer count = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(pd, count, null);
        int n = count.get(0);
        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(n, stack);
        vkGetPhysicalDeviceQueueFamilyProperties(pd, count, families);
        for (int i = 0; i < n; i++) {
            if ((families.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) return i;
        }
        throw new IllegalStateException("No graphics-capable queue family found on the selected GPU.");
    }

    // ---- logical device ----

    private static VkDevice createLogicalDevice(MemoryStack stack, VkPhysicalDevice pd, int queueFamily, boolean validation) {
        VkDeviceQueueCreateInfo.Buffer queueCreateInfos = VkDeviceQueueCreateInfo.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                .queueFamilyIndex(queueFamily)
                .pQueuePriorities(stack.floats(1.0f));

        VkPhysicalDeviceRayTracingPipelineFeaturesKHR rtPipelineFeatures =
                VkPhysicalDeviceRayTracingPipelineFeaturesKHR.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR)
                        .rayTracingPipeline(true);

        VkPhysicalDeviceAccelerationStructureFeaturesKHR asFeatures =
                VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR)
                        .accelerationStructure(true)
                        .pNext(rtPipelineFeatures.address());

        VkPhysicalDeviceVulkan12Features vk12Features = VkPhysicalDeviceVulkan12Features.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES)
                .bufferDeviceAddress(true)
                // The closest-hit shader indexes the PBR texture array by a per-instance
                // value (gl_InstanceCustomIndexEXT-derived), which is non-uniform across
                // a wavefront — needs real descriptor indexing, not just a fixed array.
                // Safe to require unconditionally: VK_KHR_acceleration_structure itself
                // mandates descriptorIndexing support, so every device that passed the
                // RT-extension check in pickPhysicalDevice() has this.
                .descriptorIndexing(true)
                .shaderSampledImageArrayNonUniformIndexing(true)
                .pNext(asFeatures.address());

        VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)
                .pNext(vk12Features.address());

        PointerBuffer extensions = stack.mallocPointer(RT_DEVICE_EXTENSIONS.length);
        for (String ext : RT_DEVICE_EXTENSIONS) extensions.put(stack.UTF8(ext));
        extensions.flip();

        VkDeviceCreateInfo deviceCreateInfo = VkDeviceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .pNext(features2.address())
                .pQueueCreateInfos(queueCreateInfos)
                .ppEnabledExtensionNames(extensions);
        // pEnabledFeatures must stay null: VkPhysicalDeviceFeatures2 in pNext supersedes it (spec requirement).

        if (validation) {
            PointerBuffer layers = stack.mallocPointer(1);
            layers.put(0, stack.UTF8("VK_LAYER_KHRONOS_validation"));
            deviceCreateInfo.ppEnabledLayerNames(layers);
        }

        PointerBuffer pDevice = stack.mallocPointer(1);
        ok(vkCreateDevice(pd, deviceCreateInfo, null, pDevice), "vkCreateDevice");
        return new VkDevice(pDevice.get(0), pd, deviceCreateInfo);
    }

    @Override
    public void close() {
        vkDeviceWaitIdle(device);
        vkDestroyDevice(device, null);
        if (debugMessenger != NULL) vkDestroyDebugUtilsMessengerEXT(instance, debugMessenger, null);
        vkDestroyInstance(instance, null);
    }
}
