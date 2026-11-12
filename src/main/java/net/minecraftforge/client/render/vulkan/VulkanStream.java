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
    /** The render targets a recorded switch can name, or {@code null} before the backend installs them. */
    private VulkanRenderTargets renderTargets;
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
     * The programs the game linked, or {@code null} before the backend has built them.
     *
     * <p>A draw that carries a program is drawn with it rather than with the stream's own shader: the program is the
     * game's, its uniforms are the game's, and the only thing the two paths share is the vertex data.</p>
     */
    private VulkanPrograms programs;
    /**
     * The highest vertex attribute location the normalised stream provides.
     *
     * <p>Position, colour, UV and lightmap, in that order - the four attributes of
     * {@code DefaultVertexFormats.BLOCK}, which {@link VertexConverter} normalises every input format into. A program
     * that declares an input above this has nothing to feed it.</p>
     */
    static final int MAX_ATTRIBUTE_LOCATION = 3;
    /** Which index buffer is bound, kept across draws so it is rebound only when it changes. */
    private long boundIndexBuffer;
    /** Programs reported once, so the log names each one the first time it is drawn. */
    /**
     * Whether a program draw has taken over the descriptor sets since the stream last bound its own.
     *
     * <p>The stream binds set 0 per draw and set 1 (the lightmap) once per frame, which is enough while the frame
     * contains only stream draws. A program draw binds sets of <i>its</i> layout, and those occupy the same set indexes
     * - so the lightmap binding is gone, and every stream draw after it samples whatever the program left there.
     * Measured as particles turning pure black: their colour is the texture times the lightmap, and the lightmap was
     * no longer a lightmap. The validation layers name it
     * {@code VUID-vkCmdDrawIndexed-None-08600}.</p>
     */
    private boolean streamSetsStale;
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
    private boolean cachedFlipsWinding;
    private VulkanPipeline cachedPipeline;

    private ByteBuffer hostVboStaging;
    private java.nio.IntBuffer hostIboStaging;
    private final float[] lastPushedMvp = new float[16];
    private float lastPushedAlphaRef = -1.0F;
    private float lastPushedAlphaTest = -1.0F;
    private int lastPushedFogMode = -1;
    private float lastPushedFogStart = -1.0F;
    private float lastPushedFogEnd = -1.0F;
    private float lastPushedFogDensity = -1.0F;
    private final float[] lastPushedFogColor = new float[4];
    private final float[] lastPushedOverlayColor = new float[4];
    /** The overlay mode last pushed, as 0.0 or 1.0, so a change of mode re-pushes. */
    private float lastPushedOverlayMode = -1.0F;
    private long lastPushedLayout = 0L;

    static final int STATIC_QUAD_CAPACITY = 65_536;
    static final int STATIC_QUAD_INDEX_COUNT = STATIC_QUAD_CAPACITY * 6;
    private final VulkanBuffer staticQuadIndexBuffer;

    VulkanStream(VkDevice device, VulkanContext context, int colorFormat, int depthFormat,
                 VulkanDescriptors descriptors, VulkanTextures textures) {
        this.device = device;
        this.context = context;
        this.colorFormat = colorFormat;
        this.depthFormat = depthFormat;
        this.staticQuadIndexBuffer = createStaticQuadIndexBuffer(device, context);
        this.vertexBuffers = new VulkanBuffer[VulkanSwapchain.FRAMES_IN_FLIGHT];
        this.indexBuffers = new VulkanBuffer[VulkanSwapchain.FRAMES_IN_FLIGHT];
        for (int i = 0; i < VulkanSwapchain.FRAMES_IN_FLIGHT; i++) {
            this.vertexBuffers[i] = VulkanBuffer.createDynamic(device, context,
                    VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, 1024 * 1024);
            this.indexBuffers[i] = VulkanBuffer.createDynamic(device, context,
                    VK_BUFFER_USAGE_INDEX_BUFFER_BIT, 512 * 1024);
        }
        this.vertex = VulkanShader.load(device, "/assets/outlands/shaders/stream.vert.spv");
        this.fragment = VulkanShader.load(device, "/assets/outlands/shaders/stream.frag.spv");
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
        int opsSize = ops.size();
        for (int i = 0; i < opsSize; i++) {
            FrameDraws.Operation op = ops.get(i);
            if (op instanceof FrameDraws.Draw draw && draw.state().program() != null) {
                // A program's samplers are its own bindings, and their textures have to be uploaded before the
                // rendering instance opens for the same reason every other texture does.
                for (int texture : draw.state().programSamplers()) {
                    resolve(texture, commandBuffer, slot);
                }
            } else if (op instanceof FrameDraws.Draw draw && draw.state().samplesTexture()) {
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
        if (renderTargets != null) {
            // A target that will be sampled this frame is moved to the sampled layout here, before the rendering
            // instance opens. Every in-frame transition belongs to a recorded TargetBind, and those cover a target
            // being left - but a draw can sample a target that nothing bound this frame (the outline chain's first
            // pass reads the framebuffer the entities were drawn into, which on the frame it is first used has never
            // been left by a switch). Without this, that draw sampled an image still in UNDEFINED layout, which is
            // what the validation layers reported as VUID-vkCmdDraw-None-09600.
            VulkanRenderTarget sampled = renderTargets.byTextureId(texture);
            if (sampled != null) {
                sampled.prepareForSampling(commandBuffer);
            }
        }
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
    /** Told who owns the render targets, so a recorded switch can find its attachments. */
    void setRenderTargets(VulkanRenderTargets targets) {
        this.renderTargets = targets;
    }

    /** Told who owns the programs the game links, so a draw with one can be recorded with it. */
    void setPrograms(VulkanPrograms programs) {
        this.programs = programs;
    }

    void replay(VkCommandBuffer commandBuffer, List<FrameDraws.Operation> ops, int slot, int viewportWidth,
                int viewportHeight, FrameRenderTarget windowTarget) {
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
            if (shape.needsIndexing() && !isStaticQuad(shape, draw.vertexCount())) {
                totalIndices += shape.indexCount(draw.vertexCount());
            }
        }

        int totalVertexBytes = totalVertices * VertexConverter.STRIDE;
        int totalIndexBytes = totalIndices * Integer.BYTES;

        if (totalVertexBytes > 0) {
            vertexBuffers[slot].ensureCapacity(totalVertexBytes);
            if (hostVboStaging == null || hostVboStaging.capacity() < totalVertexBytes) {
                if (hostVboStaging != null) {
                    org.lwjgl.system.MemoryUtil.memFree(hostVboStaging);
                }
                int newCap = Math.max(totalVertexBytes, 1024 * 1024);
                hostVboStaging = org.lwjgl.system.MemoryUtil.memAlloc(newCap).order(java.nio.ByteOrder.nativeOrder());
            }
            hostVboStaging.position(0);
            hostVboStaging.limit(totalVertexBytes);
        }
        if (totalIndexBytes > 0) {
            indexBuffers[slot].ensureCapacity(totalIndexBytes);
            if (hostIboStaging == null || hostIboStaging.capacity() < totalIndices) {
                if (hostIboStaging != null) {
                    org.lwjgl.system.MemoryUtil.memFree(hostIboStaging);
                }
                int newCap = Math.max(totalIndices, 256 * 1024);
                hostIboStaging = org.lwjgl.system.MemoryUtil.memAllocInt(newCap);
            }
            hostIboStaging.position(0);
            hostIboStaging.limit(totalIndices);
        }

        ByteBuffer targetVbo = hostVboStaging;
        java.nio.IntBuffer targetIbo = hostIboStaging;

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
            VertexConverter.convert(draw.vertices(),
                    draw.layout(), draw.vertexCount(), ds.colorArgb(), ds.lightmap(),
                    ds.lightingEnabled(), ds.normalMatrix(), ds.light0(), ds.light1(),
                    ds.textureMatrix(),
                    targetVbo, currentVertexOffset);
            currentVertexOffset += draw.vertexCount() * VertexConverter.STRIDE;

            PrimitiveShape shape = PrimitiveShape.ofGlMode(draw.mode());
            if (shape.needsIndexing()) {
                if (isStaticQuad(shape, draw.vertexCount())) {
                    indexOffsets[i] = 0;
                } else {
                    indexOffsets[i] = currentIndexOffset;
                    shape.writeIndices(draw.vertexCount(), targetIbo);
                    currentIndexOffset += shape.indexCount(draw.vertexCount());
                }
            }
        }

        if (totalVertexBytes > 0) {
            hostVboStaging.position(0);
            org.lwjgl.system.MemoryUtil.memCopy(
                    org.lwjgl.system.MemoryUtil.memAddress0(hostVboStaging),
                    vertexBuffers[slot].mappedAddress(),
                    totalVertexBytes);
        }
        if (totalIndexBytes > 0) {
            hostIboStaging.position(0);
            org.lwjgl.system.MemoryUtil.memCopy(
                    org.lwjgl.system.MemoryUtil.memAddress0(hostIboStaging),
                    indexBuffers[slot].mappedAddress(),
                    totalIndexBytes);
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
            boundIndexBuffer = 0L;
            // Scissor is dynamic state, so it is set only when a draw asks for a different rectangle. The starting
            // value is the whole viewport, which is what an unclipped frame uses.
            org.lwjgl.vulkan.VkViewport.Buffer viewport = org.lwjgl.vulkan.VkViewport.calloc(1, stack);
            // Sentinel that cannot match a real viewport, so the first draw always sets one.
            int[] appliedViewport = {Integer.MIN_VALUE, 0, 0, 0};
            org.lwjgl.vulkan.VkRect2D.Buffer scissor = org.lwjgl.vulkan.VkRect2D.calloc(1, stack);
            // Sentinel that cannot match a real rectangle, so the first draw always sets one.
            int[] appliedScissor = {Integer.MIN_VALUE, 0, 0, 0};

            int indexOfOp = -1;
            lastPushedLayout = 0L;
            // What the instance is currently open over. A frame starts on the window's image and every switch moves it.
            FrameRenderTarget current = windowTarget;
            for (FrameDraws.Operation op : ops) {
                if (op instanceof FrameDraws.TargetBind bind) {
                    // A switch to the target already being drawn into is what GL would call a no-op; it is recorded
                    // anyway because the game cannot know what the recorder is doing. Ending the instance and opening
                    // it again with LOAD would cost a full attachment load for nothing.
                    FrameRenderTarget next = bind.target() == 0 ? windowTarget : resolveTarget(bind);
                    if (next == current) {
                        continue;
                    }
                    vkCmdEndRendering(commandBuffer);
                    // The target being left has finished its pass: this is the only point where its contents are
                    // complete and it is still in the attachment layout, so it moves to the sampled layout here.
                    current.prepareForSampling(commandBuffer);
                    next.prepareForRendering(commandBuffer);
                    next.beginRendering(commandBuffer);
                    current = next;
                    // A new instance starts with the hardware viewport, scissor, pipeline and descriptor sets
                    // undefined, so every cached value is dropped rather than assumed.
                    appliedViewport[0] = Integer.MIN_VALUE;
                    appliedScissor[0] = Integer.MIN_VALUE;
                    boundSet = 0L;
                    boundPipeline = 0L;
                    boundIndexBuffer = 0L;
                    lastPushedLayout = 0L;
                    continue;
                }
                if (op instanceof FrameDraws.FramebufferCopy copy) {
                    // The copy reads the framebuffer this instance is painting, so the instance is closed around it
                    // and reopened with LOAD afterwards. Splitting is the only way to keep both the source content
                    // and the ordering vanilla asked for; Vulkan forbids the copy itself inside a render pass.
                    vkCmdEndRendering(commandBuffer);
                    textures.copyFromFramebuffer(commandBuffer, copy, current, slot);
                    current.beginRendering(commandBuffer);
                    // beginRenderingInstance resets the hardware viewport and scissor to the full extent of the window.
                    // Invalidate the cache so the next draw restores its own viewport and scissor instead of falsely
                    // assuming the hardware state is still what it was before the copy.
                    appliedViewport[0] = Integer.MIN_VALUE;
                    appliedScissor[0] = Integer.MIN_VALUE;
                    lastPushedLayout = 0L;
                    continue;
                }
                if (op instanceof FrameDraws.TerrainLayer terrainOp) {
                    if (terrain != null) {
                        terrain.recordLayer(commandBuffer, slot, terrainOp.mvp(), terrainOp.layer(),
                                terrainOp.viewX(), terrainOp.viewY(), terrainOp.viewZ(),
                                terrainOp.fogMode(), terrainOp.fogStart(), terrainOp.fogEnd(),
                                terrainOp.fogDensity(), terrainOp.fogColor(), terrainOp.visibleChunks());
                    }
                    // The terrain draw binds its own vertex buffer and descriptor set, so neither can be assumed by
                    // the next streamed draw: rebind the stream's buffer and forget the bound set.
                    if (totalVertexBytes > 0) {
                        vkCmdBindVertexBuffers(commandBuffer, 0, stack.longs(vertexBuffers[slot].handle()), stack.longs(0L));
                    }
                    boundSet = 0L;
                    boundPipeline = 0L;
                    boundIndexBuffer = 0L;
                    lastPushedLayout = 0L;
                    continue;
                }
                if (op instanceof FrameDraws.Clear clearOp) {
                    lastPushedLayout = 0L;
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
                                    // The alpha is the game's, not an opaque default. A target cleared to
                                    // (0,0,0,0) and then sampled is the normal case: the entity outline's chain
                                    // starts from a transparent target and blurs its alpha into a shape. Forcing 1
                                    // made every cleared target opaque, so the chain's output was an opaque black
                                    // frame and the composite painted the world out - measured, in a world with a
                                    // glowing entity on screen.
                                    .float32(0, c[0]).float32(1, c[1]).float32(2, c[2]).float32(3, c[3]);
                            atIdx++;
                        }
                        if (clearDepth) {
                            clearAttachments.get(atIdx)
                                    .aspectMask(org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_DEPTH_BIT);
                            clearAttachments.get(atIdx).clearValue().depthStencil()
                                    .depth((float) clearOp.depth()).stencil(0);
                            atIdx++;
                        }
                        // The rectangle is in the coordinates of whatever target is bound *now*, which after a
                        // switch is that target's extent - not the window's. Using the frame's extent drew clears
                        // outside the instance and the validation layers rejected them.
                        int targetWidth = current.width();
                        int targetHeight = current.height();
                        int x = 0;
                        int y = 0;
                        int width = targetWidth;
                        int height = targetHeight;
                        if (clearOp.scissorEnabled()) {
                            int[] box = clearOp.scissorBox();
                            x = Math.max(0, box[0]);
                            y = Math.max(0, targetHeight - box[1] - box[3]);
                            width = Math.min(box[2], targetWidth - x);
                            height = Math.min(box[3], targetHeight - y);
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

                // Read once per draw: an offscreen target inverts the viewport, hence the winding, hence the front face.
                boolean targetFlipsWinding = current.flipsVertically();
                // Both rectangles are in the *bound target's* coordinates: GL's viewport and scissor count their y
                // from the bottom of whichever framebuffer is current, so the flip has to use that target's height.
                // Using the window's was a real bug: a 256-high viewport inside a 480-high frame was placed at
                // y = 480 - 256 = 224 in a 256-tall target, leaving one eighth of it visible - a 96x96 quad came out
                // 96x32, which is exactly what the composite self-test showed.
                int targetWidth = current.width();
                int targetHeight = current.height();
                applyScissor(commandBuffer, scissor, appliedScissor, draw.state(), targetWidth, targetHeight);

                // The two paths want opposite viewport flips, and this is the subtlest thing in the whole renderer.
                //
                // A recorded draw arrives with GL-oriented clip space and the stream path pre-inverts it (the mvp's
                // second row is negated), so what it needs is one more inversion to land display-correct in the
                // window - and both inside a target, where the flip is what makes the target's storage GL's.
                //
                // A *program* draw has no such pre-inversion: its own matrix produced GL clip space, so it needs
                // exactly the opposite. Into the window that is the flip (clip +1 is the top of the picture, and only
                // a negative viewport puts it in row 0); into a target the viewport must stay unflipped, because the
                // target is already stored bottom-up and clip +1 has to reach its last row.
                boolean programFlips = !current.flipsVertically();
                // The winding is a *second* question, and it is the one that decides whether the quad survives culling.
                // The stream path mirrors clip y with its matrix and the target's viewport mirrors it again, so its
                // pipeline says "clockwise" exactly when the target's viewport is flipped. A program's own matrix
                // does no mirroring, which leaves the viewport as the only one - so the count that front face cares
                // about is the *target's* flip, not the flip applied here. Passing this draw's own flip instead culled
                // the blit experiment's quad away entirely: an odd number of mirrors is counter-clockwise, and the
                // pipeline was told to expect clockwise.
                boolean programWinding = current.flipsVertically();

                if (ds.program() != null) {
                    // A program of the game's own: its pipeline, its block, its samplers. Nothing the stream has
                    // cached applies to it, and everything it binds has to be rebound for the next streamed draw.
                    if (programs == null) {
                        throw new IllegalStateException("a draw carries program " + ds.program().id()
                                + " but no program cache is installed");
                    }
                    applyViewport(commandBuffer, viewport, appliedViewport, draw.state(), targetWidth, targetHeight,
                            programFlips);
                    programs.record(commandBuffer, slot, ds, topo, programWinding);
                    issueDraw(commandBuffer, draw, shape, offsets[indexOfOp], indexOffsets[indexOfOp], slot);
                    boundPipeline = 0L;
                    boundSet = 0L;
                    lastPushedLayout = 0L;
                    continue;
                }

                applyViewport(commandBuffer, viewport, appliedViewport, draw.state(), targetWidth, targetHeight,
                        targetFlipsWinding);
                VulkanPipeline pipeline;
                if (cachedPipeline != null && topo == cachedTopology && targetFlipsWinding == cachedFlipsWinding
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
                            ds.blendDestinationAlpha(), channels, targetFlipsWinding);
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
                    cachedFlipsWinding = targetFlipsWinding;
                    cachedPipeline = pipeline;
                }
                if (pipeline.handle() != boundPipeline) {
                    vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
                    boundPipeline = pipeline.handle();
                }
                float[] curMvp = draw.state().mvp();
                float curAlphaRef = draw.state().alphaReference();
                float curAlphaTest = draw.state().alphaTest() ? 1.0F : 0.0F;
                long curLayout = pipeline.layout();
                if (draw.state().alphaTest() && draw.state().alphaComparison() != GL_GREATER) {
                    throw new UnsupportedOperationException("alpha test comparison "
                            + draw.state().alphaComparison() + " is not implemented; the shader discards on GL_GREATER"
                            + " (516) only, which is the one vanilla sets");
                }
                int curFogMode = ds.fogMode();
                float curFogStart = ds.fogStart();
                float curFogEnd = ds.fogEnd();
                float curFogDensity = ds.fogDensity();
                float[] curFogColor = ds.fogColor();
                float[] curOverlayColor = ds.overlayColor();
                // The mode travels as it is: it says *what* the texture environment replaced (1 a light, 2 the
                // texture), and collapsing it to "is it outline mode" sent the second case down the normal path - a
                // silhouette in the entity's own texture colour, which is exactly what it was meant to avoid.
                float curOverlayMode = (float) ds.overlayMode();

                boolean needPush = curLayout != lastPushedLayout
                        || curAlphaRef != lastPushedAlphaRef
                        || curAlphaTest != lastPushedAlphaTest
                        || curFogMode != lastPushedFogMode
                        || (curFogMode > 0 && (curFogStart != lastPushedFogStart
                                || curFogEnd != lastPushedFogEnd
                                || curFogDensity != lastPushedFogDensity
                                || curFogColor[0] != lastPushedFogColor[0]
                                || curFogColor[1] != lastPushedFogColor[1]
                                || curFogColor[2] != lastPushedFogColor[2]
                                || curFogColor[3] != lastPushedFogColor[3]))
                        || curOverlayMode != lastPushedOverlayMode
                        || curOverlayColor[3] != lastPushedOverlayColor[3]
                        // In outline mode the constant's alpha is zero and its colour is the whole point, so the
                        // colour has to be compared whenever either mode is set, not only when there is alpha.
                        || ((curOverlayMode != 0.0F || curOverlayColor[3] > 0.0f)
                                && (curOverlayColor[0] != lastPushedOverlayColor[0]
                                        || curOverlayColor[1] != lastPushedOverlayColor[1]
                                        || curOverlayColor[2] != lastPushedOverlayColor[2]))
                        || !java.util.Arrays.equals(curMvp, lastPushedMvp);
                if (needPush) {
                    push.position(0);
                    writeMvp(push, curMvp);
                    push.put(16, curFogColor[0]);
                    push.put(17, curFogColor[1]);
                    push.put(18, curFogColor[2]);
                    push.put(19, curFogColor[3]);
                    push.put(20, curOverlayColor[0]);
                    push.put(21, curOverlayColor[1]);
                    push.put(22, curOverlayColor[2]);
                    push.put(23, curOverlayColor[3]);
                    push.put(24, curAlphaRef);
                    push.put(25, curAlphaTest);
                    push.put(26, 0.0F); // flipU
                    constants.putInt(108, curFogMode);
                    push.put(28, curFogStart);
                    push.put(29, curFogEnd);
                    push.put(30, curFogDensity);
                    // The one spare float carries the overlay mode: 1 when a light unit was replaced, 2 when the
                    // texture was, 0 for the hurt-flash mix.
                    push.put(31, curOverlayMode);
                    constants.position(0);
                    constants.limit(128);
                    vkCmdPushConstants(commandBuffer, curLayout,
                            org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT
                                    | org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT,
                            0, constants);
                    System.arraycopy(curMvp, 0, lastPushedMvp, 0, 16);
                    lastPushedAlphaRef = curAlphaRef;
                    lastPushedAlphaTest = curAlphaTest;
                    lastPushedFogMode = curFogMode;
                    lastPushedFogStart = curFogStart;
                    lastPushedFogEnd = curFogEnd;
                    lastPushedFogDensity = curFogDensity;
                    System.arraycopy(curFogColor, 0, lastPushedFogColor, 0, 4);
                    System.arraycopy(curOverlayColor, 0, lastPushedOverlayColor, 0, 4);
                    lastPushedOverlayMode = curOverlayMode;
                    lastPushedLayout = curLayout;
                }
                if (textureSets[indexOfOp] != boundSet || streamSetsStale) {
                    // Both of the stream's sets, together: set 1 is the lightmap and a program draw may have replaced
                    // it since this was last bound.
                    vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout(), 0,
                            stack.longs(textureSets[indexOfOp], descriptors.lightmapSet(slot)), null);
                    boundSet = textureSets[indexOfOp];
                    streamSetsStale = false;
                }
                issueDraw(commandBuffer, draw, shape, offsets[indexOfOp], indexOffsets[indexOfOp], slot);
            }
        }
    }

    /**
     * Binds the index buffer if the draw needs one and issues it, at the offset its vertices were packed to.
     *
     * <p>Shared by the two paths on purpose: a program draw differs in its pipeline and descriptors, not in how a
     * triangle fan becomes indices or where its vertices live. Duplicating this would let the two drift apart in the
     * one place where a mistake is a misaligned mesh rather than an error.</p>
     */
    private void issueDraw(VkCommandBuffer commandBuffer, FrameDraws.Draw draw, PrimitiveShape shape,
                           int vertexOffset, int indexOffset, int slot) {
        if (shape.needsIndexing()) {
            boolean staticQuad = isStaticQuad(shape, draw.vertexCount());
            long neededIndexBuffer = staticQuad ? staticQuadIndexBuffer.handle() : indexBuffers[slot].handle();
            if (boundIndexBuffer != neededIndexBuffer) {
                vkCmdBindIndexBuffer(commandBuffer, neededIndexBuffer, 0L,
                        org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT32);
                boundIndexBuffer = neededIndexBuffer;
            }
            vkCmdDrawIndexed(commandBuffer, shape.indexCount(draw.vertexCount()), 1, indexOffset,
                    vertexOffset / VertexConverter.STRIDE, 0);
        } else {
            vkCmdDraw(commandBuffer, draw.vertexCount(), 1, vertexOffset / VertexConverter.STRIDE, 0);
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
                                      int[] applied, DrawState state, int frameWidth, int frameHeight,
                                      boolean flipVertically) {
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
        if (flipVertically) {
            // A negative height is Vulkan's own way to invert y (core since 1.1), and it is the closest thing to GL's
            // bottom-up target: the same draw lands in the rows a GL driver would put it in.
            y = y + height;
            height = -height;
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



    /**
     * The target a recorded switch names.
     *
     * <p>A missing target is a released one - a framebuffer deleted while a draw list still referred to it - and
     * continuing would draw into freed memory, so it is an exception that names the handle.</p>
     */
    private FrameRenderTarget resolveTarget(FrameDraws.TargetBind bind) {
        FrameRenderTarget target = renderTargets == null ? null : renderTargets.byTextureId(bind.target());
        if (target == null) {
            throw new IllegalStateException("the frame asked to draw into render target " + bind.target() + " ("
                    + bind.width() + "x" + bind.height() + "), which does not exist any more. A framebuffer was"
                    + " deleted while a draw list still referred to it.");
        }
        return target;
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
        if (programs != null) {
            programs.prepareFrame(slot);
        }
    }

    private static VulkanBuffer createStaticQuadIndexBuffer(VkDevice device, VulkanContext context) {
        VulkanBuffer buf = VulkanBuffer.createDynamic(device, context,
                org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT,
                (long) STATIC_QUAD_INDEX_COUNT * Integer.BYTES);
        java.nio.IntBuffer ibo = org.lwjgl.system.MemoryUtil.memIntBuffer(
                buf.mappedAddress(), STATIC_QUAD_INDEX_COUNT);
        for (int q = 0; q < STATIC_QUAD_CAPACITY; q++) {
            int base = q * 4;
            ibo.put(base);
            ibo.put(base + 1);
            ibo.put(base + 2);
            ibo.put(base);
            ibo.put(base + 2);
            ibo.put(base + 3);
        }
        return buf;
    }

    static boolean isStaticQuad(PrimitiveShape shape, int vertexCount) {
        return shape.glMode() == 7 && (vertexCount / 4) <= STATIC_QUAD_CAPACITY;
    }

    @Override
    public void close() {
        if (hostVboStaging != null) {
            org.lwjgl.system.MemoryUtil.memFree(hostVboStaging);
            hostVboStaging = null;
        }
        if (hostIboStaging != null) {
            org.lwjgl.system.MemoryUtil.memFree(hostIboStaging);
            hostIboStaging = null;
        }
        if (staticQuadIndexBuffer != null) {
            staticQuadIndexBuffer.close();
        }
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
