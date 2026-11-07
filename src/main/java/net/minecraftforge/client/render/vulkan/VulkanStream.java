package net.minecraftforge.client.render.vulkan;

import net.minecraftforge.client.render.state.backend.Capability;
import net.minecraftforge.client.render.state.backend.FrameStates;
import net.minecraftforge.client.render.texture.TextureBackends;
import net.minecraftforge.client.render.texture.TextureImage;
import net.minecraftforge.client.render.vertex.DrawState;
import net.minecraftforge.client.render.vertex.FrameDraws;
import net.minecraftforge.client.render.vertex.PrimitiveShape;
import net.minecraftforge.client.render.vertex.VertexConverter;
import net.minecraftforge.client.render.vertex.VertexLayout;
import net.minecraft.client.renderer.vertex.VertexFormatElement;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_GRAPHICS;
import static org.lwjgl.vulkan.VK10.vkCmdBindDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCmdBindIndexBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdBindPipeline;
import static org.lwjgl.vulkan.VK10.vkCmdBindVertexBuffers;
import static org.lwjgl.vulkan.VK10.vkCmdDraw;
import static org.lwjgl.vulkan.VK10.vkCmdDrawIndexed;
import static org.lwjgl.vulkan.VK13.vkCmdEndRendering;
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
 * first, so the input description is a constant. What varies is topology, depth, blend and the blend factors, which
 * is a handful of combinations rather than one per vertex format.</p>
 *
 * <p><b>Textures travel with the draw, like matrices.</b> The game binds a texture, draws, binds another. Reading the
 * binding at replay time would sample one texture for the whole frame - the same mistake that once drew the entire
 * menu with the panorama's matrix, and it fails just as quietly: the picture comes out in the wrong colours instead of
 * raising an error.</p>
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
    private final VulkanDescriptors descriptors;
    private final VulkanTextures textures;
    /**
     * Descriptor sets resolved for the frame being recorded, by texture handle.
     *
     * <p>Filled by {@link #prepareTextures} <i>before</i> the rendering instance opens, because resolving a texture
     * for the first time records a copy and a layout transition, and Vulkan forbids both inside a render pass
     * ({@code VUID-vkCmdCopyBufferToImage-renderpass}). The replay then only binds what is already there.</p>
     */
    private final Map<Integer, Long> resolved = new HashMap<>();
    private int drawsThisFrame;

    VulkanStream(VkDevice device, VulkanContext context, int colorFormat, int depthFormat,
                 VulkanDescriptors descriptors, VulkanTextures textures) {
        this.device = device;
        this.context = context;
        this.colorFormat = colorFormat;
        this.depthFormat = depthFormat;
        this.pending = new VulkanBuffer[VulkanSwapchain.FRAMES_IN_FLIGHT];
        this.pendingIndices = new VulkanBuffer[VulkanSwapchain.FRAMES_IN_FLIGHT];
        this.vertex = VulkanShader.load(device, "/assets/outlands/shaders/terrain.vert.spv");
        this.fragment = VulkanShader.load(device, "/assets/outlands/shaders/terrain.frag.spv");
        this.descriptors = descriptors;
        this.textures = textures;
    }

    /** How many draws the last replayed frame had. Diagnostics. */
    int drawsThisFrame() {
        return drawsThisFrame;
    }

    /**
     * Resolves every texture this frame will sample, recording any first-use uploads.
     *
     * <p>Must be called after {@code vkBeginCommandBuffer} and before {@code vkCmdBeginRendering}: a texture that is
     * new this frame becomes a staging copy plus two image layout transitions, none of which a render pass allows
     * inside itself. Resolving them up front also means the replay is a pure sequence of binds and draws, which is
     * what makes it cheap.</p>
     *
     * @param extra additional texture handles to resolve, for paths outside the draw list (the terrain node's atlas)
     */
    void prepareTextures(VkCommandBuffer commandBuffer, List<FrameDraws.Operation> ops, int slot, int... extra) {
        resolved.clear();
        for (FrameDraws.Operation op : ops) {
            if (op instanceof FrameDraws.Draw draw && draw.state().samplesTexture()) {
                resolve(draw.state().texture(), commandBuffer, slot);
            } else if (op instanceof FrameDraws.FramebufferCopy copy) {
                // The destination has to exist before the copy can write into it, and it is resolved here for the
                // same reason every other texture is: image creation and its first layout transition belong outside
                // the rendering instance.
                resolve(copy.texture(), commandBuffer, slot);
            }
        }
        for (int texture : extra) {
            resolve(texture, commandBuffer, slot);
        }
    }

    /** Texture handles already reported, so the resolve log says one line per texture rather than one per frame. */
    private final java.util.Set<Integer> reportedTextures = new java.util.HashSet<>();

    private void resolve(int texture, VkCommandBuffer commandBuffer, int slot) {
        if (!resolved.containsKey(texture)) {
            long set = textures.descriptorSet(texture, commandBuffer, slot);
            resolved.put(texture, set);
            if (reportedTextures.add(texture) && Boolean.getBoolean("outlands.render.debug")) {
                // Which descriptor set a texture got, and whether it is the blank stand-in. This is the fact that
                // separates "the draw sampled the wrong image" from "the draw sampled its own image", and neither
                // is visible from outside. One line per texture, not per frame.
                org.apache.logging.log4j.LogManager.getLogger("Outlands|render").info(
                        "Resolved texture {} -> set {} ({})", texture, set,
                        set == textures.whiteDescriptorSet() ? "WHITE STAND-IN" : "its own image");
            }
        }
    }

    /**
     * The set resolved for a texture earlier in this frame.
     *
     * @return the resolved set, or the white stand-in when the handle was not part of the pre-flight
     */
    long descriptorSetOf(int texture) {
        Long set = resolved.get(texture);
        return set != null ? set : textures.whiteDescriptorSet();
    }

    /**
     * Records every draw into {@code commandBuffer}.
     *
     * <p>Called inside the rendering instance the frame already opened, so the colour and depth attachments are
     * whatever that instance targets.</p>
     */
    void replay(VkCommandBuffer commandBuffer, List<FrameDraws.Operation> ops, int slot, int viewportWidth,
                int viewportHeight, FrameRenderTarget target) {
        List<FrameDraws.Draw> draws = ops.stream()
                .filter(FrameDraws.Draw.class::isInstance).map(FrameDraws.Draw.class::cast).toList();
        drawsThisFrame = draws.size();
        if (DIAGNOSE.compareAndSet(false, true)) {
            // Once, because this is a question about what the frame contains, not a per-frame measurement.
            Map<String, Integer> histogram = new java.util.TreeMap<>();
            for (FrameDraws.Draw draw : draws) {
                String key = "stride " + draw.layout().stride() + " mode " + draw.mode();
                histogram.merge(key, draw.vertexCount(), Integer::sum);
            }
            org.apache.logging.log4j.LogManager.getLogger("Outlands|render").info(
                    "Frame draws: {} calls, {} textures, vertices per (stride, mode): {}",
                    draws.size(), textures.resident(), histogram);
            logProjectionSummary(draws);
        }
        // This slot's fence has been waited on, so whatever it used last time is finished.
        releaseSlot(slot);
        if (draws.isEmpty()) {
            return;
        }

        // Convert everything first: the buffer's size is the total, and one allocation beats one per draw.
        byte[][] converted = new byte[draws.size()][];
        int totalBytes = 0;
        for (int i = 0; i < draws.size(); i++) {
            FrameDraws.Draw draw = draws.get(i);
            converted[i] = VertexConverter.convert(ByteBuffer.wrap(draw.vertices()),
                    draw.layout(), draw.vertexCount(), draw.state().colorArgb());
            totalBytes += converted[i].length;
        }
        byte[] all = new byte[totalBytes];
        int[] offsets = new int[draws.size()];
        // Indices are collected for the whole frame and always 32-bit: switching width per draw would mean
        // rebinding the index buffer mid-frame for no benefit.
        int[] indexOffsets = new int[draws.size()];
        List<Integer> allIndices = new ArrayList<>();
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

        long[] textureSets = new long[draws.size()];
        for (int i = 0; i < draws.size(); i++) {
            DrawState state = draws.get(i).state();
            // A draw with texturing disabled samples nothing: GL replaces the sample with white and takes the colour
            // from the vertices alone. Binding the white stand-in is that, expressed as data.
            textureSets[i] = state.samplesTexture() ? descriptorSetOf(state.texture())
                    : textures.whiteDescriptorSet();
        }

        pending[slot] = VulkanBuffer.create(device, context, all);
        if (!allIndices.isEmpty()) {
            ByteBuffer indexBytes = ByteBuffer.allocate(allIndices.size() * Integer.BYTES)
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

            ByteBuffer constants = stack.malloc(16 * Float.BYTES);
            long boundSet = 0L;
            // Scissor is dynamic state, so it is set only when a draw asks for a different rectangle. The starting
            // value is the whole viewport, which is what an unclipped frame uses.
            org.lwjgl.vulkan.VkRect2D.Buffer scissor = org.lwjgl.vulkan.VkRect2D.calloc(1, stack);
            // Sentinel that cannot match a real rectangle, so the first draw always sets one.
            int[] appliedScissor = {Integer.MIN_VALUE, 0, 0, 0};

            int indexOfOp = -1;
            for (FrameDraws.Operation op : ops) {
                if (op instanceof FrameDraws.FramebufferCopy copy) {
                    // The copy reads the framebuffer this instance is painting, so the instance is closed around it
                    // and reopened with LOAD afterwards. Splitting is the only way to keep both the source content
                    // and the ordering vanilla asked for; Vulkan forbids the copy itself inside a render pass.
                    vkCmdEndRendering(commandBuffer);
                    textures.copyFromFramebuffer(commandBuffer, copy, target);
                    target.beginRendering(commandBuffer);
                    continue;
                }
                FrameDraws.Draw draw = (FrameDraws.Draw) op;
                indexOfOp++;
                PrimitiveShape shape = PrimitiveShape.ofGlMode(draw.mode());
                // Each draw carries the matrix and enable bits it was issued with. Using the frame's current state
                // here instead would apply one matrix to every draw - which drew the whole menu with the panorama's
                // perspective matrix and left the GUI off-screen.
                VulkanPipeline pipeline = pipelineFor(stateFor(shape, draw.state()));
                vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
                writeMvp(constants.asFloatBuffer(), draw.state().mvp());
                vkCmdPushConstants(commandBuffer, pipeline.layout(), org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT,
                        0, constants);
                applyScissor(commandBuffer, scissor, appliedScissor, draw.state(), viewportWidth, viewportHeight);
                if (textureSets[indexOfOp] != boundSet) {
                    vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout(), 0,
                            stack.longs(textureSets[indexOfOp]), null);
                    boundSet = textureSets[indexOfOp];
                }
                if (shape.needsIndexing()) {
                    vkCmdBindIndexBuffer(commandBuffer, pendingIndices[slot].handle(), 0L,
                            org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT32);
                    vkCmdDrawIndexed(commandBuffer, shape.indexCount(draw.vertexCount()), 1, indexOffsets[indexOfOp],
                            offsets[indexOfOp] / VertexConverter.STRIDE, 0);
                } else {
                    vkCmdDraw(commandBuffer, draw.vertexCount(), 1, offsets[indexOfOp] / VertexConverter.STRIDE, 0);
                }
            }
        }
    }

    /**
     * Dumps the translucent full-screen quads in a frame, once.
     *
     * <p>"A grey film covers the buttons and the background" is a translucent quadrilateral painted over everything,
     * and the only way to name it is to read the colour the vertices actually carry - the global
     * {@code GlStateManager.color} is white for most GUI draws, so the interesting value is in the vertex data. This
     * prints each quad-sized draw's first vertex colour, its texture and its blend factors, which is enough to pick
     * it out of a few hundred without another round of guessing.</p>
     */
    private void logProjectionSummary(List<FrameDraws.Draw> draws) {
        if (!Boolean.getBoolean("outlands.render.debug")) {
            // Off by default: these dumps answer questions about one frame, and a normal run has none of them.
            return;
        }
        StringBuilder report = new StringBuilder();
        int shown = 0;
        for (int i = 0; i < draws.size() && shown < 40; i++) {
            FrameDraws.Draw draw = draws.get(i);
            // The full-screen overlays: textured quads only, plus anything drawn with no texture bound. Those are
            // the ones whose alpha decides whether the background is visible at all.
            if (draw.state().samplesTexture()) {
                continue;
            }
            int colour = firstVertexColor(draw);
            DrawState state = draw.state();
            report.append('\n').append("  #").append(i)
                    .append(" tex=").append(state.texture())
                    .append(" argb=").append(Integer.toHexString(colour))
                    .append(" stride=").append(draw.layout().stride())
                    .append(" blend=").append(state.blend()
                            ? state.blendSourceRgb() + "->" + state.blendDestinationRgb() : "off")
                    .append(" scissor=").append(state.scissorEnabled())
                    .append(" verts=").append(draw.vertexCount())
                    .append(" mode=").append(draw.mode())
                    .append(" bytes=").append(hex(draw.vertices(), draw.layout().stride()));
            shown++;
        }
        org.apache.logging.log4j.LogManager.getLogger("Outlands|render").info(
                "Non-sampling draws in frame ({} of {} draws):{}", shown, draws.size(), report);
    }

    /** The first {@code count} bytes as hex, for a diagnostic that has to state the data rather than describe it. */
    private static String hex(byte[] data, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(count, data.length); i++) {
            out.append(String.format(java.util.Locale.ROOT, "%02x", data[i] & 0xFF));
        }
        return out.toString();
    }

    /** The first vertex's {@code 0xAARRGGBB} colour, or {@code 0} when the layout carries none. */
    private static int firstVertexColor(FrameDraws.Draw draw) {
        java.nio.ByteBuffer data = ByteBuffer.wrap(draw.vertices()).order(java.nio.ByteOrder.nativeOrder());
        for (VertexLayout.Attribute attribute : draw.layout().attributes()) {
            if (attribute.usage() != VertexFormatElement.EnumUsage.COLOR) {
                continue;
            }
            int at = attribute.offset();
            if (attribute.type() == VertexFormatElement.EnumType.UBYTE && attribute.count() == 4) {
                return ((data.get(at + 3) & 0xFF) << 24) | ((data.get(at) & 0xFF) << 16)
                        | ((data.get(at + 1) & 0xFF) << 8) | (data.get(at + 2) & 0xFF);
            }
            if (attribute.type() == VertexFormatElement.EnumType.FLOAT) {
                int r = Math.round(Math.max(0.0F, Math.min(1.0F, data.getFloat(at))) * 255.0F);
                int g = Math.round(Math.max(0.0F, Math.min(1.0F, data.getFloat(at + 4))) * 255.0F);
                int b = Math.round(Math.max(0.0F, Math.min(1.0F, data.getFloat(at + 8))) * 255.0F);
                int a = Math.round(Math.max(0.0F, Math.min(1.0F, data.getFloat(at + 12))) * 255.0F);
                return (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return 0;
    }

    /**
     * Sets the scissor rectangle for a draw, if it differs from the last one applied.
     *
     * <p><b>The Y is converted, not passed through.</b> {@code glScissor} takes its origin at the bottom-left and the
     * game computes the rectangle that way; Vulkan's scissor origin is the top-left. Handing GL's rectangle straight
     * over clips the mirror image of the intended area, which on the mod list means the wrong part of a scrolling
     * name is hidden - a mild-looking symptom for a fully wrong rectangle.</p>
     */
    private static void applyScissor(VkCommandBuffer commandBuffer, org.lwjgl.vulkan.VkRect2D.Buffer scissor,
                                     int[] applied, DrawState state, int viewportWidth, int viewportHeight) {
        int x = 0;
        int y = 0;
        int width = viewportWidth;
        int height = viewportHeight;
        if (state != null && state.scissorEnabled()) {
            int[] box = state.scissor();
            x = box[0];
            // GL measures from the bottom, Vulkan from the top.
            y = viewportHeight - box[1] - box[3];
            width = box[2];
            height = box[3];
        }
        if (applied[0] == x && applied[1] == y && applied[2] == width && applied[3] == height) {
            return;
        }
        applied[0] = x;
        applied[1] = y;
        applied[2] = width;
        applied[3] = height;

        scissor.get(0).offset().x(x).y(y);
        scissor.get(0).extent().width(width).height(height);
        org.lwjgl.vulkan.VK10.vkCmdSetScissor(commandBuffer, 0, scissor);
    }

    /**
     * Writes a draw's matrix with Vulkan's clip-space Y direction applied.
     *
     * <p>The game's matrices are built for GL, where NDC Y points up; Vulkan's points down. Feeding them through
     * unchanged mirrors the whole frame vertically, which is invisible on a symmetric test pattern and obvious on a
     * panorama: the ground ends up at the top and the sky face shows up overhead.</p>
     *
     * <p>The fix is the same matrix for every draw - premultiplying by {@code diag(1, -1, 1, 1)} - rather than a
     * per-path special case, because the flip belongs to the clip space, not to any one phase: the GUI's ortho and
     * the world's perspective are both GL-style and both need it. Negating row 1 in place is that premultiply, and
     * it costs four multiplies instead of a matrix multiply per draw.</p>
     */
    private static void writeMvp(java.nio.FloatBuffer target, float[] mvp) {
        target.put(mvp);
        // Column-major, so row 1 is at 1, 5, 9 and 13.
        for (int index = 1; index < 16; index += 4) {
            target.put(index, -target.get(index));
        }
        target.position(0);
    }

    /**
     * The pipeline state for a draw.
     *
     * <p>Blend, depth test, culling and the blend factors come from the draw because that is what the game's
     * {@code GlStateManager} calls had set when the draw was issued. Depth comparison is not in the key yet: vanilla
     * changes it (the GUI uses LEQUAL, the world LESS), which is a visible difference on coplanar geometry, so it is
     * the next entry rather than an assumption.</p>
     */
    private VulkanPipeline.PipelineState stateFor(PrimitiveShape shape, DrawState state) {
        return new VulkanPipeline.PipelineState(VulkanPipeline.VK10Topology.of(shape.topology()),
                state.depthTest(), state.depthWrite(), state.blend(), state.cull(),
                state.blendSourceRgb(), state.blendDestinationRgb(),
                state.blendSourceAlpha(), state.blendDestinationAlpha());
    }

    private VulkanPipeline pipelineFor(VulkanPipeline.PipelineState state) {
        return pipelines.computeIfAbsent(state,
                key -> VulkanPipeline.create(device, vertex, fragment, colorFormat, depthFormat,
                        descriptors.pipelineLayout(), descriptors.layout(), key));
    }

    private void releaseSlot(int slot) {
        if (pending[slot] != null) {
            pending[slot].close();
            pending[slot] = null;
        }
        if (pendingIndices[slot] != null) {
            pendingIndices[slot].close();
            pendingIndices[slot] = null;
        }
    }

    @Override
    public void close() {
        for (int slot = 0; slot < pending.length; slot++) {
            releaseSlot(slot);
        }
        for (VulkanPipeline pipeline : pipelines.values()) {
            pipeline.close();
        }
        pipelines.clear();
        vertex.close();
        fragment.close();
    }
}
