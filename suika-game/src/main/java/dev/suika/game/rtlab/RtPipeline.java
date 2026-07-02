package dev.suika.game.rtlab;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static dev.suika.game.rtlab.VkCheck.ok;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * The ray tracing pipeline itself: descriptor set layout, the four shader stages
 * (raygen / primary-miss / shadow-miss / closest-hit), and the shader binding table
 * that tells {@code vkCmdTraceRaysKHR} which shader handles live where.
 */
public final class RtPipeline implements AutoCloseable {

    // Descriptor bindings shared by every shader stage that needs them.
    public static final int BINDING_TLAS = 0;
    public static final int BINDING_OUTPUT_IMAGE = 1;
    public static final int BINDING_CAMERA = 2;
    public static final int BINDING_INSTANCES = 3;
    public static final int BINDING_VERTICES = 4;
    public static final int BINDING_INDICES = 5;
    public static final int BINDING_TEXTURES = 6;
    public static final int BINDING_ENVIRONMENT = 7;

    /** Fixed size of the PBR texture array binding — must match closesthit.rchit's
     *  {@code sampler2D textures[24]}. Unused slots are filled with texture 0 so
     *  every descriptor in the array stays valid. */
    public static final int MAX_TEXTURES = 24;

    public final long descriptorSetLayout;
    public final long pipelineLayout;
    public final long pipeline;
    public final long descriptorPool;
    public final long descriptorSet;

    private final RtBuffer sbtBuffer;
    public final VkStridedDeviceAddressRegionKHR raygenRegion;
    public final VkStridedDeviceAddressRegionKHR missRegion;
    public final VkStridedDeviceAddressRegionKHR hitRegion;
    public final VkStridedDeviceAddressRegionKHR callableRegion;

    private final VkDevice device;

    public RtPipeline(VkPhysicalDevice physicalDevice, VkDevice device) {
        this.device = device;
        try (MemoryStack stack = stackPush()) {
            this.descriptorSetLayout = createDescriptorSetLayout(stack);
            this.pipelineLayout = createPipelineLayout(stack);

            long[] shaderModules;
            long pipelineHandle;
            try (RtShaderCompiler compiler = new RtShaderCompiler()) {
                shaderModules = new long[]{
                        createShaderModule(stack, compiler, "raygen.rgen", shaderc_glsl_raygen_shader),
                        createShaderModule(stack, compiler, "miss.rmiss", shaderc_glsl_miss_shader),
                        createShaderModule(stack, compiler, "shadow_miss.rmiss", shaderc_glsl_miss_shader),
                        createShaderModule(stack, compiler, "closesthit.rchit", shaderc_glsl_closesthit_shader),
                };
                pipelineHandle = createPipeline(stack, shaderModules);
            } finally {
                // shader modules aren't needed once the pipeline that referenced them exists
            }
            this.pipeline = pipelineHandle;
            for (long m : shaderModules) vkDestroyShaderModule(device, m, null);

            this.descriptorPool = createDescriptorPool(stack);
            this.descriptorSet = allocateDescriptorSet(stack);

            SbtBuild sbt = buildShaderBindingTable(stack, physicalDevice);
            this.sbtBuffer = sbt.buffer;
            this.raygenRegion = sbt.raygen;
            this.missRegion = sbt.miss;
            this.hitRegion = sbt.hit;
            this.callableRegion = sbt.callable;
        }
    }

    // ---- descriptor set layout / pipeline layout ----

    private long createDescriptorSetLayout(MemoryStack stack) {
        VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(8, stack);
        bindings.get(BINDING_TLAS).binding(BINDING_TLAS)
                .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1)
                // Raygen only: all rays (primary, glass continuation, shadow) now
                // originate there — the hit shader never traces (see raygen.rgen).
                .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        bindings.get(BINDING_OUTPUT_IMAGE).binding(BINDING_OUTPUT_IMAGE)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        bindings.get(BINDING_CAMERA).binding(BINDING_CAMERA)
                .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        bindings.get(BINDING_INSTANCES).binding(BINDING_INSTANCES)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR);
        bindings.get(BINDING_VERTICES).binding(BINDING_VERTICES)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR);
        bindings.get(BINDING_INDICES).binding(BINDING_INDICES)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR);
        bindings.get(BINDING_TEXTURES).binding(BINDING_TEXTURES)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(MAX_TEXTURES)
                .stageFlags(VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR);
        bindings.get(BINDING_ENVIRONMENT).binding(BINDING_ENVIRONMENT)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                // Raygen owns ALL shading (see raygen.rgen), so the HDRI environment —
                // ambient fill, glass/metal reflections, ray-escape color — lives there.
                .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR);

        VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                .pBindings(bindings);
        LongBuffer pLayout = stack.mallocLong(1);
        ok(vkCreateDescriptorSetLayout(device, layoutInfo, null, pLayout), "vkCreateDescriptorSetLayout");
        return pLayout.get(0);
    }

    private long createPipelineLayout(MemoryStack stack) {
        VkPipelineLayoutCreateInfo info = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                .setLayoutCount(1)
                .pSetLayouts(stack.longs(descriptorSetLayout));
        LongBuffer pLayout = stack.mallocLong(1);
        ok(vkCreatePipelineLayout(device, info, null, pLayout), "vkCreatePipelineLayout");
        return pLayout.get(0);
    }

    // ---- shader modules ----

    private static String readResource(String name) {
        try (InputStream in = RtPipeline.class.getResourceAsStream("/shaders/rtlab/" + name)) {
            if (in == null) throw new IOException("resource not found: " + name);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read shader source " + name, e);
        }
    }

    private long createShaderModule(MemoryStack stack, RtShaderCompiler compiler, String resourceName, int kind) {
        String source = readResource(resourceName);
        ByteBuffer spirv = compiler.compile(source, kind, resourceName);
        VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                .pCode(spirv);
        LongBuffer pModule = stack.mallocLong(1);
        ok(vkCreateShaderModule(device, info, null, pModule), "vkCreateShaderModule (" + resourceName + ")");
        return pModule.get(0);
    }

    // ---- pipeline ----

    private long createPipeline(MemoryStack stack, long[] modules) {
        VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(4, stack);
        int[] stageFlags = {VK_SHADER_STAGE_RAYGEN_BIT_KHR, VK_SHADER_STAGE_MISS_BIT_KHR,
                VK_SHADER_STAGE_MISS_BIT_KHR, VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR};
        for (int i = 0; i < 4; i++) {
            stages.get(i).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(stageFlags[i]).module(modules[i]).pName(stack.UTF8("main"));
        }

        VkRayTracingShaderGroupCreateInfoKHR.Buffer groups = VkRayTracingShaderGroupCreateInfoKHR.calloc(4, stack);
        // group 0: raygen (GENERAL)
        groups.get(0).sType(VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR)
                .type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
                .generalShader(0).closestHitShader(VK_SHADER_UNUSED_KHR)
                .anyHitShader(VK_SHADER_UNUSED_KHR).intersectionShader(VK_SHADER_UNUSED_KHR);
        // group 1: primary miss (GENERAL)
        groups.get(1).sType(VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR)
                .type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
                .generalShader(1).closestHitShader(VK_SHADER_UNUSED_KHR)
                .anyHitShader(VK_SHADER_UNUSED_KHR).intersectionShader(VK_SHADER_UNUSED_KHR);
        // group 2: shadow miss (GENERAL)
        groups.get(2).sType(VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR)
                .type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
                .generalShader(2).closestHitShader(VK_SHADER_UNUSED_KHR)
                .anyHitShader(VK_SHADER_UNUSED_KHR).intersectionShader(VK_SHADER_UNUSED_KHR);
        // group 3: closest-hit (TRIANGLES_HIT_GROUP)
        groups.get(3).sType(VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR)
                .type(VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR)
                .generalShader(VK_SHADER_UNUSED_KHR).closestHitShader(3)
                .anyHitShader(VK_SHADER_UNUSED_KHR).intersectionShader(VK_SHADER_UNUSED_KHR);

        VkRayTracingPipelineCreateInfoKHR.Buffer createInfo = VkRayTracingPipelineCreateInfoKHR.calloc(1, stack);
        createInfo.get(0).sType(VK_STRUCTURE_TYPE_RAY_TRACING_PIPELINE_CREATE_INFO_KHR)
                .pStages(stages)
                .pGroups(groups)
                // 1 is now sufficient AND the portable maximum: every traceRayEXT in
                // the pipeline (primary, glass continuation, shadow) originates in the
                // raygen shader's iterative loop, never from a hit shader — see
                // raygen.rgen. Some RT GPUs (AMD) only guarantee a recursion depth of
                // 1, so this design keeps the RT Lab off the non-portable path the old
                // recursive shadow-from-closest-hit version needed depth 2 for.
                .maxPipelineRayRecursionDepth(1)
                .layout(pipelineLayout);

        LongBuffer pPipeline = stack.mallocLong(1);
        ok(vkCreateRayTracingPipelinesKHR(device, VK_NULL_HANDLE, VK_NULL_HANDLE, createInfo, null, pPipeline),
                "vkCreateRayTracingPipelinesKHR");
        return pPipeline.get(0);
    }

    // ---- descriptor pool / set (allocated once; contents updated per-frame by RtScene) ----

    private long createDescriptorPool(MemoryStack stack) {
        VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(5, stack);
        sizes.get(0).type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
        sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
        sizes.get(2).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1);
        sizes.get(3).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(3);
        sizes.get(4).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(MAX_TEXTURES + 1); // +1: env map

        VkDescriptorPoolCreateInfo info = VkDescriptorPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                .maxSets(1)
                .pPoolSizes(sizes);
        LongBuffer pPool = stack.mallocLong(1);
        ok(vkCreateDescriptorPool(device, info, null, pPool), "vkCreateDescriptorPool");
        return pPool.get(0);
    }

    private long allocateDescriptorSet(MemoryStack stack) {
        VkDescriptorSetAllocateInfo info = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                .descriptorPool(descriptorPool)
                .pSetLayouts(stack.longs(descriptorSetLayout));
        LongBuffer pSet = stack.mallocLong(1);
        ok(vkAllocateDescriptorSets(device, info, pSet), "vkAllocateDescriptorSets");
        return pSet.get(0);
    }

    /** Writes the PBR texture array and the HDRI environment map once at startup
     *  (these never change per frame). Fills every one of the {@link #MAX_TEXTURES}
     *  array slots — unused tail slots repeat texture 0 so no descriptor in the
     *  fixed-size array is left invalid. */
    public void writeTextures(java.util.List<RtTexture> textures, RtTexture environment) {
        if (textures.isEmpty()) throw new IllegalArgumentException("need at least one texture");
        try (MemoryStack stack = stackPush()) {
            VkDescriptorImageInfo.Buffer infos = VkDescriptorImageInfo.calloc(MAX_TEXTURES, stack);
            for (int i = 0; i < MAX_TEXTURES; i++) {
                RtTexture t = textures.get(Math.min(i, textures.size() - 1));
                infos.get(i).sampler(t.sampler).imageView(t.view)
                        .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
            VkDescriptorImageInfo.Buffer envInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .sampler(environment.sampler).imageView(environment.view)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            writes.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(descriptorSet).dstBinding(BINDING_TEXTURES).descriptorCount(MAX_TEXTURES)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(infos);
            writes.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(descriptorSet).dstBinding(BINDING_ENVIRONMENT).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(envInfo);
            vkUpdateDescriptorSets(device, writes, null);
        }
    }

    /** Points every per-frame binding at this frame's resources. Called once per frame
     *  since the TLAS handle changes every frame (rebuilt fresh — see RtAccelerationStructure). */
    public void updateDescriptorSet(long tlas, long outputImageView, RtBuffer cameraUbo,
                                     RtBuffer instanceData, RtBuffer vertices, RtBuffer indices) {
        try (MemoryStack stack = stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(6, stack);

            VkWriteDescriptorSetAccelerationStructureKHR asWrite =
                    VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR)
                            .accelerationStructureCount(1)
                            .pAccelerationStructures(stack.longs(tlas));
            writes.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .pNext(asWrite.address())
                    .dstSet(descriptorSet).dstBinding(BINDING_TLAS).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);

            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(outputImageView).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            writes.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(descriptorSet).dstBinding(BINDING_OUTPUT_IMAGE).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(imageInfo);

            writeBuffer(stack, writes, 2, BINDING_CAMERA, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, cameraUbo);
            writeBuffer(stack, writes, 3, BINDING_INSTANCES, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, instanceData);
            writeBuffer(stack, writes, 4, BINDING_VERTICES, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, vertices);
            writeBuffer(stack, writes, 5, BINDING_INDICES, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, indices);

            vkUpdateDescriptorSets(device, writes, null);
        }
    }

    private void writeBuffer(MemoryStack stack, VkWriteDescriptorSet.Buffer writes, int slot,
                              int binding, int type, RtBuffer buf) {
        VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack)
                .buffer(buf.buffer).offset(0).range(buf.size);
        writes.get(slot).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(descriptorSet).dstBinding(binding).descriptorCount(1)
                .descriptorType(type).pBufferInfo(info);
    }

    // ---- shader binding table ----

    private record SbtBuild(RtBuffer buffer, VkStridedDeviceAddressRegionKHR raygen,
                             VkStridedDeviceAddressRegionKHR miss, VkStridedDeviceAddressRegionKHR hit,
                             VkStridedDeviceAddressRegionKHR callable) {}

    private static long alignUp(long v, long a) { return (v + a - 1) / a * a; }

    private SbtBuild buildShaderBindingTable(MemoryStack stack, VkPhysicalDevice physicalDevice) {
        VkPhysicalDeviceRayTracingPipelinePropertiesKHR rtProps =
                VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_PROPERTIES_KHR);
        VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2)
                .pNext(rtProps.address());
        vkGetPhysicalDeviceProperties2(physicalDevice, props2);

        int handleSize = rtProps.shaderGroupHandleSize();
        int handleAlignment = rtProps.shaderGroupHandleAlignment();
        int baseAlignment = rtProps.shaderGroupBaseAlignment();
        int handleSizeAligned = (int) alignUp(handleSize, handleAlignment);

        int groupCount = 4;
        int dataSize = groupCount * handleSize;
        ByteBuffer handles = ByteBuffer.allocateDirect(dataSize);
        ok(vkGetRayTracingShaderGroupHandlesKHR(device, pipeline, 0, groupCount, handles),
                "vkGetRayTracingShaderGroupHandlesKHR");

        // Layout: [raygen: 1 handle][miss: 2 handles][hit: 1 handle], each region padded
        // up to baseAlignment (a hardware requirement — regions must start aligned).
        long raygenRegionSize = alignUp(handleSizeAligned, baseAlignment);
        long missRegionSize = alignUp((long) handleSizeAligned * 2, baseAlignment);
        long hitRegionSize = alignUp(handleSizeAligned, baseAlignment);
        long totalSize = raygenRegionSize + missRegionSize + hitRegionSize;

        RtBuffer sbt = new RtBuffer(physicalDevice, device, totalSize,
                VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                        | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

        ByteBuffer table = ByteBuffer.allocateDirect((int) totalSize);
        // group 0 -> raygen region
        copyHandle(handles, table, 0, 0, handleSize);
        // groups 1,2 -> miss region
        copyHandle(handles, table, 1, (int) raygenRegionSize, handleSize);
        copyHandle(handles, table, 2, (int) raygenRegionSize + handleSizeAligned, handleSize);
        // group 3 -> hit region
        copyHandle(handles, table, 3, (int) (raygenRegionSize + missRegionSize), handleSize);
        table.rewind();
        sbt.uploadHostVisible(table);

        long baseAddr = sbt.deviceAddress();

        VkStridedDeviceAddressRegionKHR raygenRegion = VkStridedDeviceAddressRegionKHR.calloc()
                .deviceAddress(baseAddr).stride(raygenRegionSize).size(raygenRegionSize);
        VkStridedDeviceAddressRegionKHR missRegion = VkStridedDeviceAddressRegionKHR.calloc()
                .deviceAddress(baseAddr + raygenRegionSize).stride(handleSizeAligned).size(missRegionSize);
        VkStridedDeviceAddressRegionKHR hitRegion = VkStridedDeviceAddressRegionKHR.calloc()
                .deviceAddress(baseAddr + raygenRegionSize + missRegionSize).stride(handleSizeAligned).size(hitRegionSize);
        VkStridedDeviceAddressRegionKHR callableRegion = VkStridedDeviceAddressRegionKHR.calloc()
                .deviceAddress(0).stride(0).size(0);

        return new SbtBuild(sbt, raygenRegion, missRegion, hitRegion, callableRegion);
    }

    private static void copyHandle(ByteBuffer src, ByteBuffer dst, int groupIndex, int dstOffset, int handleSize) {
        for (int i = 0; i < handleSize; i++) dst.put(dstOffset + i, src.get(groupIndex * handleSize + i));
    }

    @Override
    public void close() {
        raygenRegion.free();
        missRegion.free();
        hitRegion.free();
        callableRegion.free();
        sbtBuffer.close();
        vkDestroyDescriptorPool(device, descriptorPool, null);
        vkDestroyPipeline(device, pipeline, null);
        vkDestroyPipelineLayout(device, pipelineLayout, null);
        vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null);
    }
}
