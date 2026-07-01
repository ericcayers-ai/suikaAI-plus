package dev.suika.game.rtlab;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static dev.suika.game.rtlab.VkCheck.ok;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR;
import static org.lwjgl.vulkan.VK12.*;

/** A VkBuffer + its backing VkDeviceMemory, bound together. Every acceleration
 *  structure, vertex/index buffer, and uniform buffer in the RT Lab goes through
 *  this so device-address-enabled allocation (required for BLAS/TLAS/SBT) is
 *  handled in exactly one place. */
public final class RtBuffer implements AutoCloseable {

    public final long buffer;
    public final long memory;
    public final long size;
    private final VkDevice device;

    public RtBuffer(VkPhysicalDevice physicalDevice, VkDevice device, long size, int usage, int memoryProps) {
        this.device = device;
        this.size = size;
        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(size)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuffer = stack.mallocLong(1);
            ok(vkCreateBuffer(device, bufferInfo, null, pBuffer), "vkCreateBuffer");
            this.buffer = pBuffer.get(0);

            VkMemoryRequirements memReq = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(device, buffer, memReq);

            boolean deviceAddress = (usage & VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT) != 0;
            VkMemoryAllocateFlagsInfo flagsInfo = null;
            if (deviceAddress) {
                flagsInfo = VkMemoryAllocateFlagsInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO)
                        .flags(VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT);
            }

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memReq.size())
                    .memoryTypeIndex(findMemoryType(physicalDevice, memReq.memoryTypeBits(), memoryProps));
            if (flagsInfo != null) allocInfo.pNext(flagsInfo.address());

            LongBuffer pMemory = stack.mallocLong(1);
            ok(vkAllocateMemory(device, allocInfo, null, pMemory), "vkAllocateMemory");
            this.memory = pMemory.get(0);
            ok(vkBindBufferMemory(device, buffer, memory, 0), "vkBindBufferMemory");
        }
    }

    public static int findMemoryType(VkPhysicalDevice physicalDevice, int typeBits, int properties) {
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceMemoryProperties memProps = VkPhysicalDeviceMemoryProperties.malloc(stack);
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, memProps);
            for (int i = 0; i < memProps.memoryTypeCount(); i++) {
                boolean typeOk = (typeBits & (1 << i)) != 0;
                boolean propsOk = (memProps.memoryTypes(i).propertyFlags() & properties) == properties;
                if (typeOk && propsOk) return i;
            }
        }
        throw new IllegalStateException("No suitable Vulkan memory type for typeBits=" + typeBits + " properties=" + properties);
    }

    /** Copies {@code data} into this buffer via map/memcpy/unmap — only valid for
     *  HOST_VISIBLE memory (staging buffers, not device-local geometry buffers). */
    public void uploadHostVisible(java.nio.ByteBuffer data) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            ok(vkMapMemory(device, memory, 0, size, 0, pData), "vkMapMemory");
            MemoryUtil.memCopy(data, MemoryUtil.memByteBuffer(pData.get(0), (int) size));
            vkUnmapMemory(device, memory);
        }
    }

    public long deviceAddress() {
        try (MemoryStack stack = stackPush()) {
            VkBufferDeviceAddressInfo info = VkBufferDeviceAddressInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO)
                    .buffer(buffer);
            // Core (non-KHR-suffixed) entry point: we enable buffer device address via
            // VkPhysicalDeviceVulkan12Features, not the (now-redundant on 1.2) KHR
            // extension string, so the KHR-suffixed function pointer is never resolved.
            return vkGetBufferDeviceAddress(device, info);
        }
    }

    @Override
    public void close() {
        vkDestroyBuffer(device, buffer, null);
        vkFreeMemory(device, memory, null);
    }
}
