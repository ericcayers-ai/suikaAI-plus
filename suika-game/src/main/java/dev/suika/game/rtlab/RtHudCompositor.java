package dev.suika.game.rtlab;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static dev.suika.game.rtlab.VkCheck.ok;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_compute_shader;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Compute pass that alpha-blends {@link RtHud}'s overlay image onto the present
 * image right before the swapchain blit (see hud_composite.comp for why it must be
 * the present image and never the raw RT output). Structure mirrors
 * {@link RtDenoiser} — two storage-image bindings, one full-screen dispatch.
 */
final class RtHudCompositor implements AutoCloseable {

    private final VkDevice device;
    private final long descriptorSetLayout;
    private final long pipelineLayout;
    private final long pipeline;
    private final long descriptorPool;
    private final long descriptorSet;

    RtHudCompositor(VkDevice device, RtOutputImage target, RtHud hud) {
        this.device = device;
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
            bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(bindings);
            LongBuffer pLayout = stack.mallocLong(1);
            ok(vkCreateDescriptorSetLayout(device, layoutInfo, null, pLayout), "vkCreateDescriptorSetLayout (HUD)");
            this.descriptorSetLayout = pLayout.get(0);

            VkPipelineLayoutCreateInfo plInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(descriptorSetLayout));
            LongBuffer pPl = stack.mallocLong(1);
            ok(vkCreatePipelineLayout(device, plInfo, null, pPl), "vkCreatePipelineLayout (HUD)");
            this.pipelineLayout = pPl.get(0);

            String source;
            try (java.io.InputStream in = RtHudCompositor.class.getResourceAsStream("/shaders/rtlab/hud_composite.comp")) {
                if (in == null) throw new java.io.IOException("hud_composite.comp resource not found");
                source = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Failed to read hud_composite.comp", e);
            }
            long module;
            try (RtShaderCompiler compiler = new RtShaderCompiler()) {
                java.nio.ByteBuffer spirv = compiler.compile(source, shaderc_glsl_compute_shader, "hud_composite.comp");
                VkShaderModuleCreateInfo moduleInfo = VkShaderModuleCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                        .pCode(spirv);
                LongBuffer pModule = stack.mallocLong(1);
                ok(vkCreateShaderModule(device, moduleInfo, null, pModule), "vkCreateShaderModule (HUD)");
                module = pModule.get(0);
            }
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer createInfo = VkComputePipelineCreateInfo.calloc(1, stack);
            createInfo.get(0).sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                    .stage(stage).layout(pipelineLayout);
            LongBuffer pPipeline = stack.mallocLong(1);
            ok(vkCreateComputePipelines(device, VK_NULL_HANDLE, createInfo, null, pPipeline),
                    "vkCreateComputePipelines (HUD)");
            vkDestroyShaderModule(device, module, null);
            this.pipeline = pPipeline.get(0);

            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(1, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(2);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .maxSets(1).pPoolSizes(sizes);
            LongBuffer pPool = stack.mallocLong(1);
            ok(vkCreateDescriptorPool(device, poolInfo, null, pPool), "vkCreateDescriptorPool (HUD)");
            this.descriptorPool = pPool.get(0);

            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(descriptorPool)
                    .pSetLayouts(stack.longs(descriptorSetLayout));
            LongBuffer pSet = stack.mallocLong(1);
            ok(vkAllocateDescriptorSets(device, allocInfo, pSet), "vkAllocateDescriptorSets (HUD)");
            this.descriptorSet = pSet.get(0);

            VkDescriptorImageInfo.Buffer targetInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(target.view).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer hudInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(hud.view).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            writes.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(descriptorSet).dstBinding(0).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(targetInfo);
            writes.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(descriptorSet).dstBinding(1).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(hudInfo);
            vkUpdateDescriptorSets(device, writes, null);
        }
    }

    void dispatch(VkCommandBuffer cmd, int width, int height) {
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        try (MemoryStack stack = stackPush()) {
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0,
                    stack.longs(descriptorSet), null);
        }
        vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
    }

    @Override
    public void close() {
        vkDestroyDescriptorPool(device, descriptorPool, null);
        vkDestroyPipeline(device, pipeline, null);
        vkDestroyPipelineLayout(device, pipelineLayout, null);
        vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null);
    }
}
