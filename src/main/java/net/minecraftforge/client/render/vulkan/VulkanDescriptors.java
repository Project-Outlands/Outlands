package net.minecraftforge.client.render.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK10.vkAllocateDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkCreatePipelineLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyPipelineLayout;
import static org.lwjgl.vulkan.VK10.vkUpdateDescriptorSets;

/**
 * The one descriptor set layout the renderer uses, and the pool its sets come from.
 *
 * <p><b>One binding: a combined image sampler at set 0, binding 0.</b> The draws in a frame differ only in which
 * texture they sample, so the layout is a constant and a draw's descriptor set is "which image". Separate image and
 * sampler bindings would allow reusing samplers across images, which matters when samplers are expensive to create;
 * here they are created once per texture and never change, so combined is fewer moving parts.</p>
 *
 * <p><b>The pipeline layout lives here too, and that is not tidiness.</b> Every pipeline shares this set layout and
 * the same single push constant, so they must also share one {@code VkPipelineLayout}: a descriptor set bound against
 * one layout is incompatible with a pipeline built for another, which the validation layers report as
 * {@code VUID-vkCmdBindDescriptorSets-pipelineLayout} on every draw after the first pipeline switch. Creating the
 * layout per pipeline is the obvious-looking version and it fails exactly that way.</p>
 *
 * <p><b>Why a pool with a fixed ceiling instead of growing.</b> The number of distinct textures a frame samples is
 * bounded by what the game loaded, and hitting the ceiling is a bug worth an exception with the number in it rather
 * than a driver hiccup. Sets are never freed individually: a texture handle keeps its set for the life of the
 * backend, because vanilla's own {@code deleteTexture} on this path keeps the handle too (see
 * {@link net.minecraftforge.client.render.texture.TextureBackendVk#delete}).</p>
 */
final class VulkanDescriptors implements AutoCloseable {

    /**
     * How many texture descriptor sets can exist at once.
     *
     * <p>Sized for the streams and the terrain layer both sampling: vanilla's atlases plus every GUI texture plus
     * every entity texture a frame touches, times the frames in flight. Comfortably above what the game loads, small
     * enough that the pool itself is a rounding error.</p>
     */
    private static final int MAX_SETS = 1024;

    private final VkDevice device;
    private final long setLayout;
    private final long pipelineLayout;
    private final long pool;
    private int allocated;

    VulkanDescriptors(VkDevice device) {
        this.device = device;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer binding = VkDescriptorSetLayoutBinding.calloc(1, stack);
            binding.get(0)
                    .binding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pBindings(binding);
            LongBuffer layoutOut = stack.mallocLong(1);
            VkCheck.check(vkCreateDescriptorSetLayout(device, layoutInfo, null, layoutOut),
                    "vkCreateDescriptorSetLayout");
            this.setLayout = layoutOut.get(0);

            VkPushConstantRange.Buffer pushConstants = VkPushConstantRange.calloc(1, stack);
            // Must match VulkanPipeline's own copy of this range exactly, and it must cover the fragment stage:
            // the alpha test's reference value lives in these bytes and the fragment shader reads it. A range that
            // only names the vertex stage is what the validation layers reported as
            // VUID-VkGraphicsPipelineCreateInfo-layout-07987.
            pushConstants.get(0)
                    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT)
                    .offset(0)
                    .size(VulkanPipeline.PUSH_CONSTANT_BYTES);
            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pSetLayouts(stack.longs(setLayout))
                    .pPushConstantRanges(pushConstants);
            LongBuffer pipelineLayoutOut = stack.mallocLong(1);
            try {
                VkCheck.check(vkCreatePipelineLayout(device, pipelineLayoutInfo, null, pipelineLayoutOut),
                        "vkCreatePipelineLayout");
            } catch (RuntimeException e) {
                vkDestroyDescriptorSetLayout(device, setLayout, null);
                throw e;
            }
            this.pipelineLayout = pipelineLayoutOut.get(0);

            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(1, stack);
            sizes.get(0)
                    .type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(MAX_SETS);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .maxSets(MAX_SETS)
                    .pPoolSizes(sizes);
            LongBuffer poolOut = stack.mallocLong(1);
            try {
                VkCheck.check(vkCreateDescriptorPool(device, poolInfo, null, poolOut), "vkCreateDescriptorPool");
            } catch (RuntimeException e) {
                vkDestroyPipelineLayout(device, pipelineLayout, null);
                vkDestroyDescriptorSetLayout(device, setLayout, null);
                throw e;
            }
            this.pool = poolOut.get(0);
        }
    }

    long layout() {
        return setLayout;
    }

    /** The layout every pipeline using this descriptor set must be built with. */
    long pipelineLayout() {
        return pipelineLayout;
    }

    /**
     * Allocates a set that samples one image through one sampler.
     *
     * @throws VkCheck.VulkanException when the pool is exhausted, which means more textures were sampled than
     *                                 {@link #MAX_SETS} allows
     */
    long allocate(long imageView, long sampler) {
        if (allocated >= MAX_SETS) {
            throw new VkCheck.VulkanException("descriptor pool exhausted: " + MAX_SETS
                    + " texture sets are already allocated. Each sampled texture uses one per frame in flight.");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetAllocateInfo allocateInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default()
                    .descriptorPool(pool)
                    .pSetLayouts(stack.longs(setLayout));
            LongBuffer setOut = stack.mallocLong(1);
            VkCheck.check(vkAllocateDescriptorSets(device, allocateInfo, setOut), "vkAllocateDescriptorSets");
            long set = setOut.get(0);

            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack);
            imageInfo.get(0)
                    .sampler(sampler)
                    .imageView(imageView)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
            write.get(0)
                    .sType$Default()
                    .dstSet(set)
                    .dstBinding(0)
                    .dstArrayElement(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .pImageInfo(imageInfo);
            vkUpdateDescriptorSets(device, write, null);

            allocated++;
            return set;
        }
    }

    @Override
    public void close() {
        // The pool owns its sets, so destroying it releases every one of them.
        vkDestroyDescriptorPool(device, pool, null);
        vkDestroyPipelineLayout(device, pipelineLayout, null);
        vkDestroyDescriptorSetLayout(device, setLayout, null);
    }
}
