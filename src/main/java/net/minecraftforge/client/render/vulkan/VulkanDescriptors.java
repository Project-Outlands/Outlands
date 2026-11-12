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
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.vkAllocateDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkUpdateDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkCreatePipelineLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyPipelineLayout;

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
    private static final int MAX_SETS = 16384;

    private final VkDevice device;
    private final long setLayout;
    private final long lightmapSetLayout;
    private final long pipelineLayout;
    private final long pool;
    private final long[] lightmapSets = new long[VulkanSwapchain.FRAMES_IN_FLIGHT];
    private int allocated;
    /**
     * Sets whose texture is gone, ready to be reused.
     *
     * <p>A set names an image, so it cannot be reused while its image is alive; when the image goes, the set is only
     * a pool slot again. Kept as a fastutil {@code LongArrayList} because the alternative is boxing a long per entry
     * for no reason. Reuse is safe without waiting on a fence: a set is only freed when the texture that owned it was
     * replaced or deleted, and that texture's last use was a recorded draw in an earlier frame.</p>
     */
    private final it.unimi.dsi.fastutil.longs.LongArrayList freeSets = new it.unimi.dsi.fastutil.longs.LongArrayList();

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

            // Set 1 is the lightmap: one sampler that every draw reads and the game replaces a handful of times per
            // second, so it is bound once per frame instead of travelling in every draw's set.
            VkDescriptorSetLayoutBinding.Buffer lightmapBinding = VkDescriptorSetLayoutBinding.calloc(1, stack);
            lightmapBinding.get(0)
                    .binding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            VkDescriptorSetLayoutCreateInfo lightmapLayoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pBindings(lightmapBinding);
            LongBuffer lightmapLayoutOut = stack.mallocLong(1);
            VkCheck.check(vkCreateDescriptorSetLayout(device, lightmapLayoutInfo, null, lightmapLayoutOut),
                    "vkCreateDescriptorSetLayout");
            this.lightmapSetLayout = lightmapLayoutOut.get(0);

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
                    .pSetLayouts(stack.longs(setLayout, lightmapSetLayout))
                    .pPushConstantRanges(pushConstants);
            LongBuffer pipelineLayoutOut = stack.mallocLong(1);
            try {
                VkCheck.check(vkCreatePipelineLayout(device, pipelineLayoutInfo, null, pipelineLayoutOut),
                        "vkCreatePipelineLayout");
            } catch (RuntimeException e) {
                vkDestroyDescriptorSetLayout(device, lightmapSetLayout, null);
                vkDestroyDescriptorSetLayout(device, setLayout, null);
                throw e;
            }
            this.pipelineLayout = pipelineLayoutOut.get(0);

            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(1, stack);
            sizes.get(0)
                    .type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(MAX_SETS + VulkanSwapchain.FRAMES_IN_FLIGHT);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .maxSets(MAX_SETS + VulkanSwapchain.FRAMES_IN_FLIGHT)
                    .pPoolSizes(sizes);
            LongBuffer poolOut = stack.mallocLong(1);
            try {
                VkCheck.check(vkCreateDescriptorPool(device, poolInfo, null, poolOut), "vkCreateDescriptorPool");
            } catch (RuntimeException e) {
                vkDestroyPipelineLayout(device, pipelineLayout, null);
                vkDestroyDescriptorSetLayout(device, lightmapSetLayout, null);
                vkDestroyDescriptorSetLayout(device, setLayout, null);
                throw e;
            }
            this.pool = poolOut.get(0);

            for (int slot = 0; slot < VulkanSwapchain.FRAMES_IN_FLIGHT; slot++) {
                VkDescriptorSetAllocateInfo lightmapAllocate = VkDescriptorSetAllocateInfo.calloc(stack)
                        .sType$Default()
                        .descriptorPool(pool)
                        .pSetLayouts(stack.longs(lightmapSetLayout));
                LongBuffer lightmapSetOut = stack.mallocLong(1);
                VkCheck.check(vkAllocateDescriptorSets(device, lightmapAllocate, lightmapSetOut),
                        "vkAllocateDescriptorSets");
                this.lightmapSets[slot] = lightmapSetOut.get(0);
            }
        }
    }

    /**
     * Points set 1 at the given image for the given frame slot.
     */
    void writeLightmap(int slot, long view, long sampler) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer image = VkDescriptorImageInfo.calloc(1, stack);
            image.get(0).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).imageView(view).sampler(sampler);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
            write.get(0)
                    .sType$Default()
                    .dstSet(lightmapSets[slot])
                    .dstBinding(0)
                    .descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(image);
            vkUpdateDescriptorSets(device, write, null);
        }
    }

    /** The lightmap set for a frame slot, for binding once per frame. */
    long lightmapSet(int slot) {
        return lightmapSets[slot];
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
    /** Gives a set back to the pool. Its image must be gone or going. */
    void free(long set) {
        if (set != 0L) {
            freeSets.add(set);
        }
    }

    long allocate(long imageView, long sampler) {
        if (!freeSets.isEmpty()) {
            long reused = freeSets.popLong();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                writeSet(reused, imageView, sampler, stack);
            }
            return reused;
        }
        if (allocated >= MAX_SETS) {
            throw new VkCheck.VulkanException("descriptor pool exhausted: " + MAX_SETS
                    + " texture sets are already allocated. Each sampled texture uses one per frame in flight.");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetAllocateInfo allocateInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default()
                    .descriptorPool(pool)
                    // One layout, one set: the pipeline layout names both sets, but a set is allocated from exactly
                    // the layout it will be bound against. Naming two here asked for one set out of two layouts, which
                    // LWJGL rejected with "Number of remaining elements is 1, must be at least 2".
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

    /** Points an existing set at an image, which is what reusing one means. */
    private void writeSet(long set, long imageView, long sampler, MemoryStack stack) {
        VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack);
        imageInfo.get(0).sampler(sampler).imageView(imageView)
                .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
        write.get(0).sType$Default().dstSet(set).dstBinding(0).dstArrayElement(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                .pImageInfo(imageInfo);
        vkUpdateDescriptorSets(device, write, null);
    }

    @Override
    public void close() {
        // The pool owns its sets, so destroying it releases every one of them.
        vkDestroyDescriptorPool(device, pool, null);
        vkDestroyPipelineLayout(device, pipelineLayout, null);
        vkDestroyDescriptorSetLayout(device, lightmapSetLayout, null);
        vkDestroyDescriptorSetLayout(device, setLayout, null);
    }
}
