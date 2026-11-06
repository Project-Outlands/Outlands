package net.minecraftforge.client.render.vulkan;

import net.minecraftforge.client.render.state.backend.Capability;
import net.minecraftforge.client.render.state.backend.FrameStates;
import net.minecraftforge.client.render.vertex.FrameDraws;
import net.minecraftforge.client.render.vertex.PrimitiveShape;
import net.minecraftforge.client.render.vertex.VertexConverter;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_GRAPHICS;
import static org.lwjgl.vulkan.VK10.vkCmdBindPipeline;
import static org.lwjgl.vulkan.VK10.vkCmdBindVertexBuffers;
import static org.lwjgl.vulkan.VK10.vkCmdBindIndexBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdDraw;
import static org.lwjgl.vulkan.VK10.vkCmdDrawIndexed;
import static org.lwjgl.vulkan.VK10.vkCmdPushConstants;

/**
 * Replays a frame's collected draws.
 *
 * <p><b>What one draw costs.</b> Convert the vertices into the canonical layout, append them to one buffer for the
 * whole frame, then a {@code vkCmdDraw} that reads an offset into it. The GL path did the same amount of copying -
 * {@code glDrawArrays} from client memory hands the bytes to the driver on every call - so the conversion is not
 * new work, it is the old copy with a layout change attached.</p>
 *
 * <p><b>One buffer per frame, not per draw.</b> Vanilla calls this thousands of times per frame for GUI text;
 * creating and destroying a buffer per draw would dominate. The buffer is destroyed at the start of the next
 * replay, which is after the fence for the frame that used it has been waited on.</p>
 *
 * <p><b>Pipeline cache keyed by state, not by vertex layout.</b> Every draw is converted to the canonical layout
 * first, so the input description is a constant. What varies is topology, depth and blend, which is a handful of
 * combinations rather than one per vertex format.</p>
 */
final class VulkanStream implements AutoCloseable {

    private final VkDevice device;
    private final VulkanContext context;
    private final VulkanShader vertex;
    private final VulkanShader fragment;
    private final int colorFormat;
    private final int depthFormat;
    private final Map<VulkanPipeline.PipelineState, VulkanPipeline> pipelines = new HashMap<>();
    private static final java.util.concurrent.atomic.AtomicBoolean DIAGNOSE =
            new java.util.concurrent.atomic.AtomicBoolean();
    /**
     * One buffer per frame in flight.
     *
     * <p>Freeing a single shared buffer when the current slot's fence is waited on is not enough: the other slot may
     * still be reading it, which the validation layers report as destroying a buffer that is in use. Indexing by
     * slot means a buffer is released only when the frame that used it has actually finished.</p>
     */
    private final VulkanBuffer[] pending;
    private final VulkanBuffer[] pendingIndices;
    private int drawsThisFrame;

    VulkanStream(VkDevice device, VulkanContext context, int colorFormat, int depthFormat) {
        this.device = device;
        this.context = context;
        this.colorFormat = colorFormat;
        this.depthFormat = depthFormat;
        this.pending = new VulkanBuffer[VulkanSwapchain.FRAMES_IN_FLIGHT];
        this.pendingIndices = new VulkanBuffer[VulkanSwapchain.FRAMES_IN_FLIGHT];
        this.vertex = VulkanShader.load(device, "/assets/outlands/shaders/terrain.vert.spv");
        this.fragment = VulkanShader.load(device, "/assets/outlands/shaders/terrain.frag.spv");
    }

    /** How many draws the last replayed frame had. Diagnostics. */
    int drawsThisFrame() {
        return drawsThisFrame;
    }

    /**
     * Records every draw into {@code commandBuffer}.
     *
     * <p>Called inside the rendering instance the frame already opened, so the colour and depth attachments are
     * whatever that instance targets.</p>
     */
    void replay(VkCommandBuffer commandBuffer, List<FrameDraws.Draw> draws, int slot) {
        drawsThisFrame = draws.size();
        if (DIAGNOSE.compareAndSet(false, true)) {
            // Once, because this is a question about what the frame contains, not a per-frame measurement.
            java.util.Map<String, Integer> histogram = new java.util.TreeMap<>();
            for (FrameDraws.Draw draw : draws) {
                String key = "stride " + draw.layout().stride() + " mode " + draw.mode();
                histogram.merge(key, draw.vertexCount(), Integer::sum);
            }
            org.apache.logging.log4j.LogManager.getLogger("Outlands|render").info(
                    "Frame draws: {} calls, vertices per (stride, mode): {}", draws.size(), histogram);
        }
        // This slot's fence has been waited on, so whatever it used last time is finished.
        if (pending[slot] != null) {
            pending[slot].close();
            pending[slot] = null;
        }
        if (pendingIndices[slot] != null) {
            pendingIndices[slot].close();
            pendingIndices[slot] = null;
        }
        if (draws.isEmpty()) {
            return;
        }

        // Convert everything first: the buffer's size is the total, and one allocation beats one per draw.
        byte[][] converted = new byte[draws.size()][];
        int totalBytes = 0;
        for (int i = 0; i < draws.size(); i++) {
            FrameDraws.Draw draw = draws.get(i);
            converted[i] = VertexConverter.convert(java.nio.ByteBuffer.wrap(draw.vertices()),
                    draw.layout(), draw.vertexCount());
            totalBytes += converted[i].length;
        }
        byte[] all = new byte[totalBytes];
        int[] offsets = new int[draws.size()];
        // Indices are collected for the whole frame and always 32-bit: switching width per draw would mean
        // rebinding the index buffer mid-frame for no benefit.
        int[] indexOffsets = new int[draws.size()];
        java.util.List<Integer> allIndices = new java.util.ArrayList<>();
        int at = 0;
        for (int i = 0; i < draws.size(); i++) {
            offsets[i] = at;
            System.arraycopy(converted[i], 0, all, at, converted[i].length);
            at += converted[i].length;

            PrimitiveShape shape = PrimitiveShape.ofGlMode(draws.get(i).mode());
            if (shape.needsIndexing()) {
                indexOffsets[i] = allIndices.size();
                for (int index : shape.indexArray(draws.get(i).vertexCount())) {
                    allIndices.add(index);
                }
            }
        }

        pending[slot] = VulkanBuffer.create(device, context, all);
        if (!allIndices.isEmpty()) {
            java.nio.ByteBuffer indexBytes = java.nio.ByteBuffer.allocate(allIndices.size() * Integer.BYTES)
                    .order(java.nio.ByteOrder.nativeOrder());
            for (int index : allIndices) {
                indexBytes.putInt(index);
            }
            pendingIndices[slot] = VulkanBuffer.create(device, context, indexBytes.array());
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // longs(), not mallocLong().put(index, value).flip(): put(int, long) does not advance the position,
            // so flip() leaves the limit at zero and bindingCount becomes zero - which the validation layers caught
            // as "no buffer bound" on every draw rather than as an obvious error.
            vkCmdBindVertexBuffers(commandBuffer, 0, stack.longs(pending[slot].handle()), stack.longs(0L));

            java.nio.ByteBuffer constants = stack.malloc(16 * Float.BYTES);

            for (int i = 0; i < draws.size(); i++) {
                FrameDraws.Draw draw = draws.get(i);
                PrimitiveShape shape = PrimitiveShape.ofGlMode(draw.mode());
                // Each draw carries the matrix and enable bits it was issued with. Using the frame's current state
                // here instead would apply one matrix to every draw - which drew the whole menu with the panorama's
                // perspective matrix and left the GUI off-screen.
                VulkanPipeline pipeline = pipelineFor(stateFor(shape, draw.state()));
                vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
                constants.asFloatBuffer().put(draw.state().mvp());
                vkCmdPushConstants(commandBuffer, pipeline.layout(), org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT,
                        0, constants);
                if (shape.needsIndexing()) {
                    vkCmdBindIndexBuffer(commandBuffer, pendingIndices[slot].handle(), 0L,
                            org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT32);
                    vkCmdDrawIndexed(commandBuffer, shape.indexCount(draw.vertexCount()), 1, indexOffsets[i],
                            offsets[i] / VertexConverter.STRIDE, 0);
                } else {
                    vkCmdDraw(commandBuffer, draw.vertexCount(), 1, offsets[i] / VertexConverter.STRIDE, 0);
                }
            }
        }
    }

    /**
     * The pipeline state for a draw.
     *
     * <p>Blend, depth test and culling come from the frame state because that is where the game's
     * {@code GlStateManager} calls landed. Depth comparison and blend factors are not in the key yet: the shader's
     * blend is on or off and the comparison is fixed, which is enough for the GUI path and is stated here rather
     * than assumed silently.</p>
     */
    private VulkanPipeline.PipelineState stateFor(PrimitiveShape shape,
            net.minecraftforge.client.render.vertex.DrawState state) {
        return new VulkanPipeline.PipelineState(VulkanPipeline.VK10Topology.of(shape.topology()),
                state.depthTest(), state.depthWrite(), state.blend(), state.cull());
    }

    private VulkanPipeline pipelineFor(VulkanPipeline.PipelineState state) {
        return pipelines.computeIfAbsent(state,
                key -> VulkanPipeline.create(device, vertex, fragment, colorFormat, depthFormat, key));
    }

    @Override
    public void close() {
        for (int slot = 0; slot < pending.length; slot++) {
            if (pending[slot] != null) {
                pending[slot].close();
                pending[slot] = null;
            }
            if (pendingIndices[slot] != null) {
                pendingIndices[slot].close();
                pendingIndices[slot] = null;
            }
        }
        for (VulkanPipeline pipeline : pipelines.values()) {
            pipeline.close();
        }
        pipelines.clear();
        vertex.close();
        fragment.close();
    }
}
