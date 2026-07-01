package dev.suika.game.rtlab;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static dev.suika.game.rtlab.VkCheck.ok;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK12.*;

/** A single Vulkan acceleration structure (BLAS or TLAS) plus the two buffers that
 *  back it: the AS storage buffer itself, and the scratch buffer only the GPU build
 *  command needs (freed right after — nothing at trace-time reads scratch memory). */
public final class RtAccelerationStructure implements AutoCloseable {

    public final long handle;
    public final RtBuffer storage;
    private final VkDevice device;

    private RtAccelerationStructure(VkDevice device, long handle, RtBuffer storage) {
        this.device = device;
        this.handle = handle;
        this.storage = storage;
    }

    public long deviceAddress() {
        try (MemoryStack stack = stackPush()) {
            VkAccelerationStructureDeviceAddressInfoKHR info = VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_DEVICE_ADDRESS_INFO_KHR)
                    .accelerationStructure(handle);
            return vkGetAccelerationStructureDeviceAddressKHR(device, info);
        }
    }

    @Override
    public void close() {
        vkDestroyAccelerationStructureKHR(device, handle, null);
        storage.close();
    }

    /** Builds a bottom-level AS from one triangle mesh stored at the given byte
     *  offsets inside shared vertex/index buffers (see {@link RtMeshLibrary} — all
     *  meshes live in two big buffers; each BLAS reads its own slice via offset). */
    public static RtAccelerationStructure buildBlas(VkPhysicalDevice pd, VkDevice device, long commandPool, VkQueue queue,
                                                     RtBuffer vertexBuffer, long vertexByteOffset, int vertexCount, int vertexStrideBytes,
                                                     RtBuffer indexBuffer, long indexByteOffset, int triangleCount) {
        try (MemoryStack stack = stackPush()) {
            VkAccelerationStructureGeometryTrianglesDataKHR triangles =
                    VkAccelerationStructureGeometryTrianglesDataKHR.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_TRIANGLES_DATA_KHR)
                            .vertexFormat(VK_FORMAT_R32G32B32_SFLOAT)
                            .vertexData(a -> a.deviceAddress(vertexBuffer.deviceAddress() + vertexByteOffset))
                            // Only the first 12 bytes of each vertex are position; the
                            // stride skips over the interleaved UV/normal data that the
                            // closest-hit shader reads through the same buffer as an SSBO.
                            .vertexStride(vertexStrideBytes)
                            .maxVertex(vertexCount - 1)
                            .indexType(VK_INDEX_TYPE_UINT32)
                            .indexData(a -> a.deviceAddress(indexBuffer.deviceAddress() + indexByteOffset));

            VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
            geometry.get(0)
                    .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR)
                    .geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                    .geometry(g -> g.triangles(triangles))
                    .flags(VK_GEOMETRY_OPAQUE_BIT_KHR);

            return build(pd, device, commandPool, queue, stack,
                    VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, geometry, triangleCount);
        }
    }

    /** Builds a top-level AS instancing the BLAS once per fruit, each with its own
     *  transform (translate to the fruit's centre, scale to its radius). Rebuilt fresh
     *  every frame — simpler and safer than in-place UPDATE mode, and TLAS rebuilds are
     *  cheap relative to BLAS rebuilds for a scene this small (dozens of instances). */
    public static RtAccelerationStructure buildTlas(VkPhysicalDevice pd, VkDevice device, long commandPool, VkQueue queue,
                                                     RtBuffer instanceBuffer, int instanceCount) {
        try (MemoryStack stack = stackPush()) {
            VkAccelerationStructureGeometryInstancesDataKHR instancesData =
                    VkAccelerationStructureGeometryInstancesDataKHR.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_INSTANCES_DATA_KHR)
                            .arrayOfPointers(false)
                            .data(a -> a.deviceAddress(instanceBuffer.deviceAddress()));

            VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
            geometry.get(0)
                    .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR)
                    .geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR)
                    .geometry(g -> g.instances(instancesData))
                    .flags(VK_GEOMETRY_OPAQUE_BIT_KHR);

            return build(pd, device, commandPool, queue, stack,
                    VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR, geometry, instanceCount);
        }
    }

    private static RtAccelerationStructure build(VkPhysicalDevice pd, VkDevice device, long commandPool, VkQueue queue,
                                                  MemoryStack stack, int asType,
                                                  VkAccelerationStructureGeometryKHR.Buffer geometry, int primitiveCount) {
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer buildInfo = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        buildInfo.get(0)
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR)
                .type(asType)
                .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .geometryCount(1)
                .pGeometries(geometry);

        VkAccelerationStructureBuildSizesInfoKHR sizeInfo = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR);
        vkGetAccelerationStructureBuildSizesKHR(device, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                buildInfo.get(0), stack.ints(primitiveCount), sizeInfo);

        RtBuffer storage = new RtBuffer(pd, device, sizeInfo.accelerationStructureSize(),
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

        VkAccelerationStructureCreateInfoKHR createInfo = VkAccelerationStructureCreateInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR)
                .buffer(storage.buffer)
                .size(sizeInfo.accelerationStructureSize())
                .type(asType);
        LongBuffer pAS = stack.mallocLong(1);
        ok(vkCreateAccelerationStructureKHR(device, createInfo, null, pAS), "vkCreateAccelerationStructureKHR");
        long asHandle = pAS.get(0);

        RtBuffer scratch = new RtBuffer(pd, device, sizeInfo.buildScratchSize(),
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

        buildInfo.get(0)
                .dstAccelerationStructure(asHandle)
                .scratchData(a -> a.deviceAddress(scratch.deviceAddress()));

        VkAccelerationStructureBuildRangeInfoKHR.Buffer rangeInfo = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
        rangeInfo.get(0).primitiveCount(primitiveCount).primitiveOffset(0).firstVertex(0).transformOffset(0);

        PointerBuffer ppRangeInfos = stack.mallocPointer(1);
        ppRangeInfos.put(0, rangeInfo.get(0).address());

        OneShotCommands.submit(device, commandPool, queue, cmd ->
                vkCmdBuildAccelerationStructuresKHR(cmd, buildInfo, ppRangeInfos));

        scratch.close(); // only needed during the build command we just waited on
        return new RtAccelerationStructure(device, asHandle, storage);
    }
}
