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

import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
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

    private int[] offsets = new int[256];
    private int[] indexOffsets = new int[256];
    private long[] textureSets = new long[256];

    /**
     * Resizable dynamic buffers per frame in flight.
     *
     * <p>Allocated once and persistently mapped, growing dynamically if a frame needs more space.
     * Indexing by slot means a buffer is written only after the frame that previously used that slot
     * has signaled its fence.</p>
     */
    private final VulkanBuffer[] vertexBuffers;
    private final VulkanBuffer[] indexBuffers;
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

    /** The game's lightmap texture, or zero while it has not been created. */
    private int lightmapTexture;
    /**
     * Descriptor sets resolved for the frame being recorded, by texture handle.
     *
     * <p>Filled by {@link #prepareTextures} <i>before</i> the rendering instance opens, because resolving a texture
     * for the first time records a copy and a layout transition, and Vulkan forbids both inside a render pass
     * ({@code VUID-vkCmdCopyBufferToImage-renderpass}). The replay then only binds what is already there.</p>
     */
    private final Int2LongMap resolved = new Int2LongOpenHashMap();
    private final List<FrameDraws.Draw> draws = new ArrayList<>();
    private int drawsThisFrame;

    private VulkanPipeline.VK10Topology cachedTopology;
    private boolean cachedDepthTest;
    private boolean cachedDepthWrite;
    private int cachedDepthFunc;
    private boolean cachedBlend;
    private boolean cachedCull;
    private int cachedSrcRgb;
    private int cachedDstRgb;
    private int cachedSrcAlpha;
    private int cachedDstAlpha;
    private int cachedChannels;
    private VulkanPipeline cachedPipeline;

    VulkanStream(VkDevice device, VulkanContext context, int colorFormat, int depthFormat,
                 VulkanDescriptors descriptors, VulkanTextures textures) {
        this.device = device;
        this.context = context;
        this.colorFormat = colorFormat;
        this.depthFormat = depthFormat;
        this.vertexBuffers = new VulkanBuffer[VulkanSwapchain.FRAMES_IN_FLIGHT];
        this.indexBuffers = new VulkanBuffer[VulkanSwapchain.FRAMES_IN_FLIGHT];
        for (int i = 0; i < VulkanSwapchain.FRAMES_IN_FLIGHT; i++) {
            this.vertexBuffers[i] = VulkanBuffer.createDynamic(device, context,
                    VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, 1024 * 1024);
            this.indexBuffers[i] = VulkanBuffer.createDynamic(device, context,
                    VK_BUFFER_USAGE_INDEX_BUFFER_BIT, 512 * 1024);
        }
        this.vertex = VulkanShader.load(device, "/assets/outlands/shaders/terrain.vert.spv");
        this.fragment = VulkanShader.load(device, "/assets/outlands/shaders/terrain.frag.spv");
        this.descriptors = descriptors;
        this.textures = textures;
        this.resolved.defaultReturnValue(0L);
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
        textures.ensureWhiteUploaded(commandBuffer, slot);
        if (lightmapTexture != 0) {
            resolve(lightmapTexture, commandBuffer, slot);
        }
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
        updateLightmapDescriptor(commandBuffer, slot);
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
        long set = resolved.get(texture);
        return set != 0L ? set : textures.whiteDescriptorSet();
    }

    /**
     * Records every draw into {@code commandBuffer}.
     *
     * <p>Called inside the rendering instance the frame already opened, so the colour and depth attachments are
     * whatever that instance targets.</p>
     */
    void replay(VkCommandBuffer commandBuffer, List<FrameDraws.Operation> ops, int slot, int viewportWidth,
                int viewportHeight, FrameRenderTarget target) {
        draws.clear();
        for (int i = 0; i < ops.size(); i++) {
            if (ops.get(i) instanceof FrameDraws.Draw d) {
                draws.add(d);
            }
        }
        drawsThisFrame = draws.size();
        if (ops.isEmpty()) {
            return;
        }

        int totalVertices = 0;
        int totalIndices = 0;
        for (int i = 0; i < draws.size(); i++) {
            FrameDraws.Draw draw = draws.get(i);
            totalVertices += draw.vertexCount();
            PrimitiveShape shape = PrimitiveShape.ofGlMode(draw.mode());
            if (shape.needsIndexing()) {
                totalIndices += shape.indexCount(draw.vertexCount());
            }
        }

        int totalVertexBytes = totalVertices * VertexConverter.STRIDE;
        int totalIndexBytes = totalIndices * Integer.BYTES;

        if (totalVertexBytes > 0) {
            vertexBuffers[slot].ensureCapacity(totalVertexBytes);
        }
        if (totalIndexBytes > 0) {
            indexBuffers[slot].ensureCapacity(totalIndexBytes);
        }

        ByteBuffer targetVbo = totalVertexBytes > 0
                ? org.lwjgl.system.MemoryUtil.memByteBuffer(vertexBuffers[slot].mappedAddress(), totalVertexBytes)
                : null;
        java.nio.IntBuffer targetIbo = totalIndexBytes > 0
                ? org.lwjgl.system.MemoryUtil.memIntBuffer(indexBuffers[slot].mappedAddress(), totalIndices)
                : null;

        int currentVertexOffset = 0;
        int currentIndexOffset = 0;
        int nDraws = draws.size();
        if (offsets.length < nDraws) {
            int newCap = Math.max(nDraws, offsets.length * 2);
            offsets = new int[newCap];
            indexOffsets = new int[newCap];
            textureSets = new long[newCap];
        }

        for (int i = 0; i < draws.size(); i++) {
            FrameDraws.Draw draw = draws.get(i);
            offsets[i] = currentVertexOffset;
            DrawState ds = draw.state();
            VertexConverter.convert(ByteBuffer.wrap(draw.vertices()),
                    draw.layout(), draw.vertexCount(), ds.colorArgb(), ds.lightmap(),
                    ds.lightingEnabled(), ds.normalMatrix(), ds.light0(), ds.light1(),
                    ds.textureMatrix(),
                    targetVbo, currentVertexOffset);
            currentVertexOffset += draw.vertexCount() * VertexConverter.STRIDE;

            PrimitiveShape shape = PrimitiveShape.ofGlMode(draw.mode());
            if (shape.needsIndexing()) {
                indexOffsets[i] = currentIndexOffset;
                shape.writeIndices(draw.vertexCount(), targetIbo);
                currentIndexOffset += shape.indexCount(draw.vertexCount());
            }
        }

        for (int i = 0; i < draws.size(); i++) {
            DrawState state = draws.get(i).state();
            // A draw with texturing disabled samples nothing: GL replaces the sample with white and takes the colour
            // from the vertices alone. Binding the white stand-in is that, expressed as data.
            textureSets[i] = state.samplesTexture() ? descriptorSetOf(state.texture())
                    : textures.whiteDescriptorSet();
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (totalVertexBytes > 0) {
                vkCmdBindVertexBuffers(commandBuffer, 0, stack.longs(vertexBuffers[slot].handle()), stack.longs(0L));
            }

            ByteBuffer constants = stack.malloc(VulkanPipeline.PUSH_CONSTANT_BYTES);
            java.nio.FloatBuffer push = constants.asFloatBuffer();
            long boundSet = 0L;
            long boundPipeline = 0L;
            boolean indexBufferBound = false;
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
                        terrain.recordLayer(commandBuffer, slot, terrainOp.mvp(), terrainOp.layer());
                    }
                    // The terrain draw binds its own vertex buffer and descriptor set, so neither can be assumed by
                    // the next streamed draw: rebind the stream's buffer and forget the bound set.
                    if (totalVertexBytes > 0) {
                        vkCmdBindVertexBuffers(commandBuffer, 0, stack.longs(vertexBuffers[slot].handle()), stack.longs(0L));
                    }
                    boundSet = 0L;
                    boundPipeline = 0L;
                    indexBufferBound = false;
                    continue;
                }
                if (op instanceof FrameDraws.Clear clearOp) {
                    int mask = clearOp.mask();
                    boolean clearColor = (mask & 0x4000) != 0;
                    boolean clearDepth = (mask & 0x100) != 0;
                    int count = (clearColor ? 1 : 0) + (clearDepth ? 1 : 0);
                    if (count > 0) {
                        org.lwjgl.vulkan.VkClearAttachment.Buffer clearAttachments =
                                org.lwjgl.vulkan.VkClearAttachment.calloc(count, stack);
                        int atIdx = 0;
                        if (clearColor) {
                            float[] c = clearOp.color();
                            clearAttachments.get(atIdx)
                                    .aspectMask(org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                                    .colorAttachment(0);
                            clearAttachments.get(atIdx).clearValue().color()
                                    .float32(0, c[0]).float32(1, c[1]).float32(2, c[2]).float32(3, 1.0F);
                            atIdx++;
                        }
                        if (clearDepth) {
                            clearAttachments.get(atIdx)
                                    .aspectMask(org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_DEPTH_BIT);
                            clearAttachments.get(atIdx).clearValue().depthStencil()
                                    .depth((float) clearOp.depth()).stencil(0);
                            atIdx++;
                        }
                        int x = 0;
                        int y = 0;
                        int width = viewportWidth;
                        int height = viewportHeight;
                        if (clearOp.scissorEnabled()) {
                            int[] box = clearOp.scissorBox();
                            x = Math.max(0, box[0]);
                            y = Math.max(0, viewportHeight - box[1] - box[3]);
                            width = Math.min(box[2], viewportWidth - x);
                            height = Math.min(box[3], viewportHeight - y);
                        }
                        if (width > 0 && height > 0) {
                            org.lwjgl.vulkan.VkClearRect.Buffer clearRects =
                                    org.lwjgl.vulkan.VkClearRect.calloc(1, stack);
                            clearRects.get(0).rect().offset().set(x, y);
                            clearRects.get(0).rect().extent().set(width, height);
                            clearRects.get(0).baseArrayLayer(0);
                            clearRects.get(0).layerCount(1);
                            org.lwjgl.vulkan.VK10.vkCmdClearAttachments(commandBuffer, clearAttachments, clearRects);
                        }
                    }
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
                VulkanPipeline.VK10Topology topo = VulkanPipeline.VK10Topology.of(shape.topology());
                DrawState ds = draw.state();
                boolean[] mask = ds.colorMask();
                int channels = (mask[0] ? org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_R_BIT : 0)
                        | (mask[1] ? org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_G_BIT : 0)
                        | (mask[2] ? org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_B_BIT : 0)
                        | (mask[3] ? org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_A_BIT : 0);

                VulkanPipeline pipeline;
                if (cachedPipeline != null && topo == cachedTopology
                        && ds.depthTest() == cachedDepthTest && ds.depthWrite() == cachedDepthWrite
                        && ds.depthFunc() == cachedDepthFunc && ds.blend() == cachedBlend && ds.cull() == cachedCull
                        && ds.blendSourceRgb() == cachedSrcRgb && ds.blendDestinationRgb() == cachedDstRgb
                        && ds.blendSourceAlpha() == cachedSrcAlpha && ds.blendDestinationAlpha() == cachedDstAlpha
                        && channels == cachedChannels) {
                    pipeline = cachedPipeline;
                } else {
                    VulkanPipeline.PipelineState ps = new VulkanPipeline.PipelineState(topo,
                            ds.depthTest(), ds.depthWrite(), ds.depthFunc(), ds.blend(), ds.cull(),
                            ds.blendSourceRgb(), ds.blendDestinationRgb(), ds.blendSourceAlpha(),
                            ds.blendDestinationAlpha(), channels);
                    pipeline = pipelineFor(ps);
                    cachedTopology = topo;
                    cachedDepthTest = ds.depthTest();
                    cachedDepthWrite = ds.depthWrite();
                    cachedDepthFunc = ds.depthFunc();
                    cachedBlend = ds.blend();
                    cachedCull = ds.cull();
                    cachedSrcRgb = ds.blendSourceRgb();
                    cachedDstRgb = ds.blendDestinationRgb();
                    cachedSrcAlpha = ds.blendSourceAlpha();
                    cachedDstAlpha = ds.blendDestinationAlpha();
                    cachedChannels = channels;
                    cachedPipeline = pipeline;
                }
                if (pipeline.handle() != boundPipeline) {
                    vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
                    boundPipeline = pipeline.handle();
                }
                push.position(0);
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
                    if (!indexBufferBound) {
                        vkCmdBindIndexBuffer(commandBuffer, indexBuffers[slot].handle(), 0L,
                                org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT32);
                        indexBufferBound = true;
                    }
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
        // Column-major: row 1 is Y (negated for Vulkan NDC) and row 2 is Z (remapped to [0, w]).
        for (int col = 0; col < 16; col += 4) {
            target.put(col + 1, -target.get(col + 1));
            target.put(col + 2, 0.5F * (target.get(col + 2) + target.get(col + 3)));
        }
        target.position(0);
    }



    private VulkanPipeline pipelineFor(VulkanPipeline.PipelineState state) {
        return pipelines.computeIfAbsent(state,
                key -> VulkanPipeline.create(device, vertex, fragment, colorFormat, depthFormat,
                        descriptors.pipelineLayout(), descriptors.layout(), key));
    }

    /** The lightmap image the shared set 1 currently points at per slot. */
    private final long[] lightmapImage = new long[VulkanSwapchain.FRAMES_IN_FLIGHT];
    private final long[] lightmapSampler = new long[VulkanSwapchain.FRAMES_IN_FLIGHT];

    /**
     * Updates descriptor set 1 to point at the game's lightmap for this slot.
     *
     * <p>Called before the rendering instance opens so any staging upload and layout transition happens outside
     * the render pass.</p>
     */
    private void updateLightmapDescriptor(VkCommandBuffer commandBuffer, int slot) {
        long[] lightmap = resolveLightmap(commandBuffer, slot);
        long view = lightmap != null ? lightmap[0] : textures.whiteImageView();
        long sampler = lightmap != null ? lightmap[1] : textures.whiteSampler();
        if (view != lightmapImage[slot] || sampler != lightmapSampler[slot]) {
            descriptors.writeLightmap(slot, view, sampler);
            lightmapImage[slot] = view;
            lightmapSampler[slot] = sampler;
        }
    }

    /**
     * Points descriptor set 1 at the game's lightmap and binds it.
     */
    void bindLightmap(VkCommandBuffer commandBuffer, int slot) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, descriptors.pipelineLayout(), 1,
                    stack.longs(descriptors.lightmapSet(slot)), null);
        }
    }

    /** Called by the backend when the game reports which texture its lightmap lives in. */
    void setLightmapTextureId(int texture) {
        setLightmapTexture(texture);
    }

    /** Told by the backend each frame which texture the game's lightmap lives in. */
    void setLightmapTexture(int texture) {
        this.lightmapTexture = texture;
    }

    /** The image view and sampler behind the lightmap, or {@code null} when it is not resolvable yet. */
    long[] resolveLightmap(VkCommandBuffer commandBuffer, int slot) {
        if (lightmapTexture == 0) {
            return null;
        }
        return textures.resolveForSharedSet(lightmapTexture, commandBuffer, slot);
    }

    /** Wired by the backend once the terrain layer exists. */
    void setTerrain(VulkanTerrainLayer terrain) {
        this.terrain = terrain;
    }

    void prepareFrame(int slot) {
        if (terrain != null) {
            terrain.prepareFrame(slot);
        }
    }

    @Override
    public void close() {
        for (int slot = 0; slot < VulkanSwapchain.FRAMES_IN_FLIGHT; slot++) {
            if (vertexBuffers[slot] != null) {
                vertexBuffers[slot].close();
            }
            if (indexBuffers[slot] != null) {
                indexBuffers[slot].close();
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
