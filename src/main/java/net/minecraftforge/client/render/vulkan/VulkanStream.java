package net.minecraftforge.client.render.vulkan;

import net.minecraftforge.client.render.state.backend.Capability;
import net.minecraftforge.client.render.state.backend.FrameStates;
import net.minecraftforge.client.render.texture.TextureBackends;
import net.minecraftforge.client.render.texture.TextureImage;
import net.minecraftforge.client.render.vertex.DrawState;
import net.minecraftforge.client.render.vertex.FrameDraws;
import net.minecraftforge.client.render.vertex.PrimitiveShape;
import net.minecraftforge.client.render.vertex.VertexConverter;

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
    /** {@code GL_GREATER}, the alpha test comparison vanilla sets when it enables the test. */
    private static final int GL_GREATER = 516;

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
     * Where the captured terrain lives, or {@code null} before the backend has built it.
     *
     * <p>A terrain layer draw arrives as part of the frame's own sequence - vanilla issues each layer between the sky
     * and the entities - so the replay hands those operations to this object rather than drawing them itself. It owns
     * the packed buffers, the pipeline and the atlas binding; what the frame carries is only the camera and the layer.
     * </p>
     */
    private VulkanTerrainLayer terrain;
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

    private void resolve(int texture, VkCommandBuffer commandBuffer, int slot) {
        if (!resolved.containsKey(texture)) {
            long set = textures.descriptorSet(texture, commandBuffer, slot);
            resolved.put(texture, set);
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
        // This slot's fence has been waited on, so whatever it used last time is finished.
        releaseSlot(slot);
        if (ops.isEmpty()) {
            return;
        }

        // Convert everything first: the buffer's size is the total, and one allocation beats one per draw.
        byte[][] converted = new byte[draws.size()][];
        int totalBytes = 0;
        for (int i = 0; i < draws.size(); i++) {
            FrameDraws.Draw draw = draws.get(i);
            converted[i] = VertexConverter.convert(ByteBuffer.wrap(draw.vertices()),
                    draw.layout(), draw.vertexCount(), draw.state().colorArgb(), draw.state().lightmap());
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

        // A frame can have terrain and no streamed draws, and a zero-length buffer is not creatable - asking for one
        // takes the driver down rather than returning an error.
        if (!draws.isEmpty()) {
            pending[slot] = VulkanBuffer.create(device, context, all);
            if (!allIndices.isEmpty()) {
                ByteBuffer indexBytes = ByteBuffer.allocate(allIndices.size() * Integer.BYTES)
                        .order(java.nio.ByteOrder.nativeOrder());
                for (int index : allIndices) {
                    indexBytes.putInt(index);
                }
                pendingIndices[slot] = VulkanBuffer.create(device, context, indexBytes.array());
            }
        } else {
            pending[slot] = null;
            pendingIndices[slot] = null;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // longs(), not mallocLong().put(index, value).flip(): put(int, long) does not advance the position,
            // so flip() leaves the limit at zero and bindingCount becomes zero - which the validation layers caught
            // as "no buffer bound" on every draw rather than as an obvious error.
            if (pending[slot] != null) {
                vkCmdBindVertexBuffers(commandBuffer, 0, stack.longs(pending[slot].handle()), stack.longs(0L));
            }

            ByteBuffer constants = stack.malloc(VulkanPipeline.PUSH_CONSTANT_BYTES);
            long boundSet = 0L;
            // Scissor is dynamic state, so it is set only when a draw asks for a different rectangle. The starting
            // value is the whole viewport, which is what an unclipped frame uses.
            org.lwjgl.vulkan.VkViewport.Buffer viewport = org.lwjgl.vulkan.VkViewport.calloc(1, stack);
            // Sentinel that cannot match a real viewport, so the first draw always sets one.
            int[] appliedViewport = {Integer.MIN_VALUE, 0, 0, 0};
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
                    textures.copyFromFramebuffer(commandBuffer, copy, target, slot);
                    target.beginRendering(commandBuffer);
                    // beginRenderingInstance resets the hardware viewport and scissor to the full extent of the window.
                    // Invalidate the cache so the next draw restores its own viewport and scissor instead of falsely
                    // assuming the hardware state is still what it was before the copy.
                    appliedViewport[0] = Integer.MIN_VALUE;
                    appliedScissor[0] = Integer.MIN_VALUE;
                    continue;
                }
                if (op instanceof FrameDraws.TerrainLayer terrainOp) {
                    if (terrain != null) {
                        terrain.recordLayer(commandBuffer, terrainOp.mvp(), terrainOp.layer());
                    }
                    // The terrain draw binds its own vertex buffer and descriptor set, so neither can be assumed by
                    // the next streamed draw: rebind the stream's buffer and forget the bound set.
                    if (pending[slot] != null) {
                        vkCmdBindVertexBuffers(commandBuffer, 0, stack.longs(pending[slot].handle()), stack.longs(0L));
                    }
                    boundSet = 0L;
                    continue;
                }
                FrameDraws.Draw draw = (FrameDraws.Draw) op;
                indexOfOp++;
                if (textures.isFramebufferTarget(draw.state().texture()) && draw.vertexCount() == 12) {
                    // The main menu's seven blur passes are twelve-vertex quads that sample the copy and draw it back
                    // into the same corner of the framebuffer they were copied from. That feedback loop is a GL-era
                    // technique: GL's copy is a snapshot, so each pass blurs the picture one texel further and the
                    // result stays a picture. Here the frame is not one rendering instance - every copy closes it and
                    // reopens with LOAD - and the loop converges to the mean colour of the region instead. Measured
                    // over four runs: without these draws the background is the panorama at every frame, with them it
                    // is a flat colour by the third frame. They are skipped until the blur is a real post-process the
                    // backend owns, which is where a screen-space effect belongs anyway.
                    continue;
                }
                PrimitiveShape shape = PrimitiveShape.ofGlMode(draw.mode());
                // Each draw carries the matrix and enable bits it was issued with. Using the frame's current state
                // here instead would apply one matrix to every draw - which drew the whole menu with the panorama's
                // perspective matrix and left the GUI off-screen.
                VulkanPipeline pipeline = pipelineFor(stateFor(shape, draw.state()));
                vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
                java.nio.FloatBuffer push = constants.asFloatBuffer();
                writeMvp(push, draw.state().mvp());
                push.put(16, draw.state().alphaReference());
                // GL_GREATER is the only comparison vanilla uses; anything else is rejected rather than approximated.
                push.put(17, draw.state().alphaTest() ? 1.0F : 0.0F);
                // flipU in terrain shader (0.0F: sampled as given).
                push.put(18, 0.0F);
                if (draw.state().alphaTest() && draw.state().alphaComparison() != GL_GREATER) {
                    throw new UnsupportedOperationException("alpha test comparison "
                            + draw.state().alphaComparison() + " is not implemented; the shader discards on GL_GREATER"
                            + " (516) only, which is the one vanilla sets");
                }
                vkCmdPushConstants(commandBuffer, pipeline.layout(),
                        org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT
                                | org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT,
                        0, constants);
                applyViewport(commandBuffer, viewport, appliedViewport, draw.state(), viewportWidth, viewportHeight);
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
     * Sets the viewport for a draw, if it differs from the last one applied.
     *
     * <p>The Y is converted: {@code glViewport} counts from the bottom, Vulkan from the top. That conversion is what
     * makes the main menu's {@code viewport(0, 0, 256, 256)} land in the bottom-left corner where the blur expects to
     * find it, rather than in the top-left where a straight copy of the numbers would put it.</p>
     */
    private static void applyViewport(VkCommandBuffer commandBuffer, org.lwjgl.vulkan.VkViewport.Buffer viewport,
                                      int[] applied, DrawState state, int frameWidth, int frameHeight) {
        int x = 0;
        int y = 0;
        int width = frameWidth;
        int height = frameHeight;
        if (state != null && state.hasViewport()) {
            int[] box = state.viewport();
            x = box[0];
            y = frameHeight - box[1] - box[3];
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

        viewport.get(0).x((float) x).y((float) y).width((float) width).height((float) height)
                .minDepth(0.0f).maxDepth(1.0f);
        org.lwjgl.vulkan.VK10.vkCmdSetViewport(commandBuffer, 0, viewport);
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
        target.position(0);
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
        // EXPERIMENT: the main menu's final backdrop draw covers the screen with the copied panorama yet produces
        // nothing, while the twelve-vertex blur quads that put the same texture into the same corner do appear.
        // The two differ by viewport, measured correct, so the difference lies in one of these pieces of state.
        boolean[] mask = state.colorMask();
        int channels = (mask[0] ? org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_R_BIT : 0)
                | (mask[1] ? org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_G_BIT : 0)
                | (mask[2] ? org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_B_BIT : 0)
                | (mask[3] ? org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_A_BIT : 0);
        return new VulkanPipeline.PipelineState(VulkanPipeline.VK10Topology.of(shape.topology()),
                state.depthTest(), state.depthWrite(), state.blend(), state.cull(),
                state.blendSourceRgb(), state.blendDestinationRgb(), state.blendSourceAlpha(),
                state.blendDestinationAlpha(), channels);
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

    /** Wired by the backend once the terrain layer exists. */
    void setTerrain(VulkanTerrainLayer terrain) {
        this.terrain = terrain;
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
