package net.minecraftforge.client.render.vulkan;

import net.minecraftforge.client.render.shader.GlShaders;
import net.minecraftforge.client.render.vertex.DrawState;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_GRAPHICS;
import static org.lwjgl.vulkan.VK10.vkAllocateDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCmdBindDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCmdBindPipeline;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkResetDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkUpdateDescriptorSets;

/**
 * The programs the game linked, and the per-frame memory their draws bind.
 *
 * <p><b>What a program draw needs that a fixed-function one did not.</b> The stream path has one pipeline layout for
 * the whole renderer and passes everything except the texture in push constants. A program brings its own layout, its
 * own uniform block and its own samplers, so it also brings its own descriptor sets - built per draw, because both the
 * block's contents and the textures are per-draw state.</p>
 *
 * <p><b>The arena, and why it is not one buffer per draw.</b> Uniform writes are a few hundred bytes and there are a
 * handful of program draws per frame, so each slot of the frame loop gets one host-visible buffer and draws take
 * slices of it. A slice is selected with a dynamic offset, which means the descriptor set for a program's block can be
 * rebuilt cheaply - it names a buffer and a range, not the bytes.</p>
 *
 * <p><b>Sets come from a per-slot pool that is reset, not freed.</b> A descriptor set cannot be freed while the
 * command buffer that bound it is still in flight, so the pool is reset when the slot's fence has been waited on: that
 * is exactly the moment the sets of the slot's previous frame are known to be idle.</p>
 */
final class VulkanPrograms implements AutoCloseable {

    /**
     * Bytes of uniform arena per frame in flight.
     *
     * <p>Large enough that the whole outline chain - four passes, and the same program twice with different
     * directions - fits many times over, and small enough to be one allocation of no consequence. Running out is
     * reported with the byte count rather than growing the buffer, because growing it would invalidate every
     * descriptor set that names it.</p>
     */
    private static final int ARENA_BYTES = 128 * 1024;

    /** How many descriptor sets one frame may allocate for program draws. */
    private static final int POOL_SETS = 256;
    /** How many sampler bindings those sets may hold in total. */
    private static final int POOL_SAMPLERS = 1024;

    private final VulkanContext context;
    private final VkDevice device;
    private final int colorFormat;
    private final int depthFormat;
    private final VulkanTextures textures;
    private final int alignment;
    private final VulkanBuffer[] arenas = new VulkanBuffer[VulkanSwapchain.FRAMES_IN_FLIGHT];
    private final int[] arenaCursor = new int[VulkanSwapchain.FRAMES_IN_FLIGHT];
    private final long[] pools = new long[VulkanSwapchain.FRAMES_IN_FLIGHT];
    private final Map<Integer, VulkanProgram> programs = new HashMap<>();

    VulkanPrograms(VulkanContext context, int colorFormat, int depthFormat, VulkanTextures textures) {
        this.context = context;
        this.device = context.device();
        this.colorFormat = colorFormat;
        this.depthFormat = depthFormat;
        this.textures = textures;
        this.alignment = Math.max(1, context.minUniformBufferOffsetAlignment());
        try {
            for (int slot = 0; slot < arenas.length; slot++) {
                arenas[slot] = VulkanBuffer.createDynamic(device, context,
                        org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, ARENA_BYTES);
                pools[slot] = createPool(slot);
            }
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    private long createPool(int slot) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(POOL_SETS);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(POOL_SAMPLERS);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .maxSets(POOL_SETS)
                    .pPoolSizes(sizes);
            LongBuffer poolOut = stack.mallocLong(1);
            VkCheck.check(vkCreateDescriptorPool(device, poolInfo, null, poolOut),
                    "vkCreateDescriptorPool(programs, slot " + slot + ")");
            return poolOut.get(0);
        }
    }

    /**
     * Starts a frame: its uniform arena is empty and its descriptor sets are reusable.
     *
     * @param slot the frame in flight, whose fence has already been waited on
     */
    void prepareFrame(int slot) {
        arenaCursor[slot] = 0;
        vkResetDescriptorPool(device, pools[slot], 0);
    }

    /**
     * Records one draw with a program: its uniforms, its samplers, its pipeline.
     *
     * <p>The caller binds the vertex buffer and issues the draw itself, because that part is identical to the stream
     * path and the stream already owns the offsets.</p>
     */
    void record(VkCommandBuffer commandBuffer, int slot, DrawState state,
                VulkanPipeline.VK10Topology topology, boolean flippedWinding) {
        GlShaders.Program source = state.program();
        if (source == null) {
            throw new IllegalStateException("a draw without a program reached the program path");
        }
        VulkanProgram program = program(source);
        VulkanPipeline pipeline = program.pipeline(VulkanPipeline.PipelineState.of(topology, state, flippedWinding),
                colorFormat, depthFormat);
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());

        long[] setLayouts = program.setLayouts();
        if (setLayouts.length == 0) {
            return;
        }

        int dynamicOffset = 0;
        if (program.blockSize() > 0) {
            dynamicOffset = reserve(slot, state.programBlock(), program.blockSize(), program.programId());
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer sets = stack.mallocLong(setLayouts.length);
            for (int set = 0; set < setLayouts.length; set++) {
                sets.put(set, allocateSet(slot, setLayouts[set]));
            }

            // One write per binding, in the same walk the layouts were built by, so a binding cannot be written into
            // the wrong set.
            VkDescriptorBufferInfo.Buffer bufferInfo = program.blockSize() > 0
                    ? VkDescriptorBufferInfo.calloc(1, stack) : null;
            if (bufferInfo != null) {
                bufferInfo.get(0).buffer(arenas[slot].handle()).offset(0L).range(program.blockSize());
            }
            List<GlShaders.Uniform> samplers = program.samplers();
            VkDescriptorImageInfo.Buffer imageInfos = samplers.isEmpty()
                    ? null : VkDescriptorImageInfo.calloc(samplers.size(), stack);
            for (int i = 0; i < samplers.size(); i++) {
                VulkanTextures.ImageBinding binding =
                        textures.binding(state.programSamplers()[i], commandBuffer, slot);
                imageInfos.get(i)
                        .sampler(binding.sampler())
                        .imageView(binding.view())
                        .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }

            int writes = (program.blockSize() > 0 ? 1 : 0) + samplers.size();
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(writes, stack);
            int at = 0;
            if (program.blockSize() > 0) {
                write.get(at++)
                        .sType$Default()
                        .dstSet(sets.get(program.blockSet()))
                        .dstBinding(program.blockBinding())
                        .descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC)
                        .pBufferInfo(bufferInfo);
            }
            for (int i = 0; i < samplers.size(); i++) {
                GlShaders.Uniform sampler = samplers.get(i);
                write.get(at++)
                        .sType$Default()
                        .dstSet(sets.get(sampler.descriptorSet()))
                        .dstBinding(sampler.binding())
                        .descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .pImageInfo(imageInfos.slice(i, 1));
            }
            vkUpdateDescriptorSets(device, write, null);

            if (program.blockSize() > 0) {
                vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout(), 0,
                        sets, stack.ints(dynamicOffset));
            } else {
                vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout(), 0,
                        sets, null);
            }
        }
    }

    private long allocateSet(int slot, long setLayout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetAllocateInfo info = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default()
                    .descriptorPool(pools[slot])
                    .pSetLayouts(stack.longs(setLayout));
            LongBuffer out = stack.mallocLong(1);
            VkCheck.check(vkAllocateDescriptorSets(device, info, out),
                    "vkAllocateDescriptorSets(program, slot " + slot + ")");
            return out.get(0);
        }
    }

    /**
     * Copies a draw's uniform block into this slot's arena and returns its offset.
     *
     * <p>The offset is aligned to the device's uniform buffer offset alignment, which is what a dynamic offset has to
     * be: the driver is allowed to be strict about it and some are.</p>
     */
    private int reserve(int slot, byte[] block, int size, int programId) {
        int offset = (arenaCursor[slot] + alignment - 1) / alignment * alignment;
        if (offset + size > arenas[slot].size()) {
            throw new IllegalStateException("program " + programId + " needs " + size + " bytes of uniform arena at "
                    + offset + ", but the arena is " + arenas[slot].size() + " bytes. Raise VulkanPrograms.ARENA_BYTES"
                    + " or stop issuing this many program draws in one frame.");
        }
        arenaCursor[slot] = offset + size;
        ByteBuffer target = MemoryUtil.memByteBuffer(arenas[slot].mappedAddress(), (int) arenas[slot].size());
        target.position(offset);
        target.put(block, 0, Math.min(size, block.length));
        return offset;
    }

    /** The device objects for a program, built on first use and rebuilt if the program was re-linked. */
    private VulkanProgram program(GlShaders.Program source) {
        VulkanProgram existing = programs.get(source.id());
        if (existing != null && existing.matches(source)) {
            return existing;
        }
        if (existing != null) {
            existing.close();
            programs.remove(source.id());
        }
        VulkanProgram created = VulkanProgram.create(device, source);
        programs.put(source.id(), created);
        return created;
    }

    /** Drops a program's device objects, for a program the game deleted. */
    void delete(int programId) {
        VulkanProgram existing = programs.remove(programId);
        if (existing != null) {
            // The sets that name its layouts are released when their pool is reset, and the pipelines are idle once
            // the frame that used them has been fenced - which is the same condition whenever this is reached.
            existing.close();
        }
    }

    @Override
    public void close() {
        for (VulkanProgram program : programs.values()) {
            program.close();
        }
        programs.clear();
        for (int slot = 0; slot < pools.length; slot++) {
            if (pools[slot] != 0L) {
                vkDestroyDescriptorPool(device, pools[slot], null);
                pools[slot] = 0L;
            }
        }
        for (int slot = 0; slot < arenas.length; slot++) {
            if (arenas[slot] != null) {
                arenas[slot].close();
                arenas[slot] = null;
            }
        }
    }
}
