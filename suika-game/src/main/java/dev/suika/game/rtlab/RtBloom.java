package dev.suika.game.rtlab;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static dev.suika.game.rtlab.VkCheck.ok;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_compute_shader;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Post-process compute stage: a cheap single-pass bloom (see bloom.comp) that also
 * doubles as the "copy the frame onto {@code present}" step {@link RtLabLauncher}
 * used to do with a plain {@code vkCmdCopyImage} — since this shader already visits
 * every pixel and writes a same-size output, running it once serves both jobs at no
 * extra pipeline stage cost. Structurally identical to {@link RtDenoiser} (same
 * two-storage-image binding layout); kept as a separate class because the two
 * shaders solve unrelated problems (Monte-Carlo noise vs. highlight glow) and may
 * one day get independent knobs.
 */
public final class RtBloom implements AutoCloseable {

    private final VkDevice device;
    private final long descriptorSetLayout;
    private final long pipelineLayout;
    private final long pipeline;
    private final long descriptorPool;
    private final long descriptorSet;

    public RtBloom(VkDevice device, RtOutputImage input, RtOutputImage output) {
        this.device = device;
        try (MemoryStack stack = stackPush()) {
            this.descriptorSetLayout = createDescriptorSetLayout(stack);
            this.pipelineLayout = createPipelineLayout(stack);
            this.pipeline = createPipeline(stack);
            this.descriptorPool = createDescriptorPool(stack);
            this.descriptorSet = allocateAndWriteDescriptorSet(stack, input, output);
        }
    }

    private long createDescriptorSetLayout(MemoryStack stack) {
        VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
        bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
        bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
        VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                .pBindings(bindings);
        LongBuffer pLayout = stack.mallocLong(1);
        ok(vkCreateDescriptorSetLayout(device, info, null, pLayout), "vkCreateDescriptorSetLayout (bloom)");
        return pLayout.get(0);
    }

    private long createPipelineLayout(MemoryStack stack) {
        VkPipelineLayoutCreateInfo info = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                .pSetLayouts(stack.longs(descriptorSetLayout));
        LongBuffer pLayout = stack.mallocLong(1);
        ok(vkCreatePipelineLayout(device, info, null, pLayout), "vkCreatePipelineLayout (bloom)");
        return pLayout.get(0);
    }

    private long createPipeline(MemoryStack stack) {
        String source;
        try (java.io.InputStream in = RtBloom.class.getResourceAsStream("/shaders/rtlab/bloom.comp")) {
            if (in == null) throw new java.io.IOException("bloom.comp resource not found");
            source = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to read bloom.comp", e);
        }

        long module;
        try (RtShaderCompiler compiler = new RtShaderCompiler()) {
            java.nio.ByteBuffer spirv = compiler.compile(source, shaderc_glsl_compute_shader, "bloom.comp");
            VkShaderModuleCreateInfo moduleInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(spirv);
            LongBuffer pModule = stack.mallocLong(1);
            ok(vkCreateShaderModule(device, moduleInfo, null, pModule), "vkCreateShaderModule (bloom)");
            module = pModule.get(0);
        }

        VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                .module(module)
                .pName(stack.UTF8("main"));

        VkComputePipelineCreateInfo.Buffer createInfo = VkComputePipelineCreateInfo.calloc(1, stack);
        createInfo.get(0).sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                .stage(stage)
                .layout(pipelineLayout);

        LongBuffer pPipeline = stack.mallocLong(1);
        ok(vkCreateComputePipelines(device, VK_NULL_HANDLE, createInfo, null, pPipeline), "vkCreateComputePipelines (bloom)");
        vkDestroyShaderModule(device, module, null);
        return pPipeline.get(0);
    }

    private long createDescriptorPool(MemoryStack stack) {
        VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(1, stack);
        sizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(2);
        VkDescriptorPoolCreateInfo info = VkDescriptorPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                .maxSets(1)
                .pPoolSizes(sizes);
        LongBuffer pPool = stack.mallocLong(1);
        ok(vkCreateDescriptorPool(device, info, null, pPool), "vkCreateDescriptorPool (bloom)");
        return pPool.get(0);
    }

    private long allocateAndWriteDescriptorSet(MemoryStack stack, RtOutputImage input, RtOutputImage output) {
        VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                .descriptorPool(descriptorPool)
                .pSetLayouts(stack.longs(descriptorSetLayout));
        LongBuffer pSet = stack.mallocLong(1);
        ok(vkAllocateDescriptorSets(device, allocInfo, pSet), "vkAllocateDescriptorSets (bloom)");
        long set = pSet.get(0);

        VkDescriptorImageInfo.Buffer inputInfo = VkDescriptorImageInfo.calloc(1, stack)
                .imageView(input.view).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
        VkDescriptorImageInfo.Buffer outputInfo = VkDescriptorImageInfo.calloc(1, stack)
                .imageView(output.view).imageLayout(VK_IMAGE_LAYOUT_GENERAL);

        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
        writes.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(set).dstBinding(0).descriptorCount(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(inputInfo);
        writes.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(set).dstBinding(1).descriptorCount(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(outputInfo);
        vkUpdateDescriptorSets(device, writes, null);
        return set;
    }

    /** Records a dispatch covering the full image (8x8 workgroups, rounded up). */
    public void dispatch(VkCommandBuffer cmd, int width, int height) {
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        try (MemoryStack stack = stackPush()) {
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(descriptorSet), null);
        }
        int groupsX = (width + 7) / 8;
        int groupsY = (height + 7) / 8;
        vkCmdDispatch(cmd, groupsX, groupsY, 1);
    }

    @Override
    public void close() {
        vkDestroyDescriptorPool(device, descriptorPool, null);
        vkDestroyPipeline(device, pipeline, null);
        vkDestroyPipelineLayout(device, pipelineLayout, null);
        vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null);
    }
}
