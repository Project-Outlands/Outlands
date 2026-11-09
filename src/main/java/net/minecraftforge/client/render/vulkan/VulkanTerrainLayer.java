package net.minecraftforge.client.render.vulkan;

import net.minecraftforge.client.render.ChunkMeshCapture;
import net.minecraftforge.client.render.graph.RenderGraphContext;
import net.minecraftforge.client.render.graph.RenderGraphNode;
import net.minecraftforge.client.render.graph.RenderPhases;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT32;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_GRAPHICS;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdBindDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCmdBindIndexBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdBindPipeline;
import static org.lwjgl.vulkan.VK10.vkCmdBindVertexBuffers;
import static org.lwjgl.vulkan.VK10.vkCmdDrawIndexed;
import static org.lwjgl.vulkan.VK10.vkCmdPushConstants;

/**
 * The {@code terrain.layer} phase.
 *
 * <p><b>Arena Sub-allocation:</b> Chunks are sub-allocated from a 128MB vertex arena pool. A single static shared
 * quad index buffer is bound once per layer, and draw calls supply the chunk's base vertex offset directly.</p>
 *
 * <p><b>Incremental updates:</b> New and modified chunk meshes are streamed directly to allocated slots in mapped
 * VRAM without full-terrain rebuilds or GPU device idle stalls.</p>
 */
final class VulkanTerrainLayer implements RenderGraphNode, AutoCloseable {

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("Outlands|render");
    private static int logFrameCounter = 0;

    private final VkDevice device;
    private final VulkanContext context;
    private final VulkanPipeline pipeline;
    private final VulkanPipeline translucentPipeline;
    private final VulkanDescriptors descriptors;
    private final VulkanTextures textures;
    private final TerrainArenaPool arenaPool;

    private final Map<ChunkMeshCapture.Key, TerrainSlice> activeSlices = new HashMap<>();
    @SuppressWarnings("unchecked")
    private final List<TerrainSlice>[] layerSlices = new List[net.minecraft.util.BlockRenderLayer.values().length];

    /** Where the current buffers came from: null while no chunks are loaded. */
    private String source;

    /** Draws the last frame issued. */
    private int drawCount;
    /** Scratch for the per-chunk matrix. */
    private final Matrix4f chunkMatrix = new Matrix4f();
    /** Scratch for the camera the frame hands over. */
    private final java.nio.FloatBuffer cameraScratch = java.nio.ByteBuffer.allocateDirect(64)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
    /** Scratch for the 6 frustum planes (6 planes * 4 components = 24 floats). */
    private final float[] frustumPlanes = new float[24];

    private VulkanTerrainLayer(VkDevice device, VulkanContext context, VulkanPipeline pipeline,
                               VulkanPipeline translucentPipeline,
                               VulkanDescriptors descriptors, VulkanTextures textures,
                               TerrainArenaPool arenaPool) {
        this.device = device;
        this.context = context;
        this.pipeline = pipeline;
        this.translucentPipeline = translucentPipeline;
        this.descriptors = descriptors;
        this.textures = textures;
        this.arenaPool = arenaPool;
        for (int i = 0; i < layerSlices.length; i++) {
            layerSlices[i] = new ArrayList<>();
        }
        this.source = "none";
    }

    /** How many indexed draws the last frame issued. */
    int drawCount() {
        return drawCount;
    }

    /** What the current mesh came from, for diagnostics. */
    String source() {
        return source;
    }

    TerrainArenaPool arenaPool() {
        return arenaPool;
    }

    int activeSliceCount() {
        return activeSlices.size();
    }

    static VulkanTerrainLayer create(VulkanContext context, int colorFormat, int depthFormat,
                                     VulkanDescriptors descriptors, VulkanTextures textures) {
        VkDevice device = context.device();
        VulkanShader vertex = VulkanShader.load(device, "/assets/outlands/shaders/terrain.vert.spv");
        VulkanShader fragment = VulkanShader.load(device, "/assets/outlands/shaders/terrain.frag.spv");
        VulkanPipeline pipeline;
        VulkanPipeline translucentPipeline;
        try {
            pipeline = VulkanPipeline.create(device, vertex, fragment, colorFormat, depthFormat,
                    descriptors.pipelineLayout(), descriptors.layout(), VulkanPipeline.PipelineState.terrain());
            translucentPipeline = VulkanPipeline.create(device, vertex, fragment, colorFormat, depthFormat,
                    descriptors.pipelineLayout(), descriptors.layout(), VulkanPipeline.PipelineState.translucentTerrain());
        } finally {
            vertex.close();
            fragment.close();
        }

        TerrainArenaPool arenaPool;
        try {
            arenaPool = new TerrainArenaPool(device, context, VulkanSwapchain.FRAMES_IN_FLIGHT);
        } catch (RuntimeException e) {
            pipeline.close();
            translucentPipeline.close();
            throw e;
        }

        return new VulkanTerrainLayer(device, context, pipeline, translucentPipeline, descriptors, textures, arenaPool);
    }

    /** The descriptor set for the atlas, resolved by the frame before the rendering instance opened. */
    private long resolvedTexture;

    /** Records which set to bind. Called once per frame, before recording starts drawing. */
    @Override
    public void bindTextures(long descriptorSet) {
        this.resolvedTexture = descriptorSet;
    }

    @Override
    public int requestedTexture() {
        return atlasTexture();
    }

    private static int atlasTexture() {
        net.minecraft.client.renderer.texture.TextureMap map =
                net.minecraft.client.Minecraft.getMinecraft().getTextureMapBlocks();
        return map == null ? 0 : map.getGlTextureId();
    }

    @Override
    public String label() {
        return RenderPhases.TERRAIN_LAYER;
    }

    @Override
    public void run(RenderGraphContext context) {
        drawCount = 0;
    }

    private void addSlice(TerrainSlice slice) {
        List<TerrainSlice> list = layerSlices[slice.layer()];
        slice.setLayerListIndex(list.size());
        list.add(slice);
        activeSlices.put(new ChunkMeshCapture.Key(slice.originX(), slice.originY(), slice.originZ(),
                net.minecraft.util.BlockRenderLayer.values()[slice.layer()]), slice);
    }

    private void removeSlice(TerrainSlice slice) {
        List<TerrainSlice> list = layerSlices[slice.layer()];
        int idx = slice.layerListIndex();
        int lastIdx = list.size() - 1;
        if (idx >= 0 && idx <= lastIdx) {
            if (idx != lastIdx) {
                TerrainSlice last = list.get(lastIdx);
                list.set(idx, last);
                last.setLayerListIndex(idx);
            }
            list.remove(lastIdx);
        } else {
            list.remove(slice);
        }
        slice.setLayerListIndex(-1);
    }

    /**
     * Prepares terrain for the frame in flight. Called right after the fence for {@code slot} has signaled.
     *
     * <p>Frees allocations that were retired when this slot was last rendered, and processes any incremental
     * chunk mesh updates and removals from worker threads.</p>
     */
    void prepareFrame(int slot) {
        if (arenaPool == null) {
            return;
        }
        arenaPool.releaseSlot(slot);

        if (!ChunkMeshCapture.hasPendingEvents()) {
            return;
        }

        List<ChunkMeshCapture.CaptureEvent> events = ChunkMeshCapture.drainEvents();
        for (ChunkMeshCapture.CaptureEvent event : events) {
            switch (event.type()) {
                case CLEAR -> {
                    for (TerrainSlice slice : activeSlices.values()) {
                        arenaPool.retire(slice.allocation(), slot);
                    }
                    activeSlices.clear();
                    for (List<TerrainSlice> list : layerSlices) {
                        list.clear();
                    }
                }
                case REMOVE -> {
                    TerrainSlice old = activeSlices.remove(event.key());
                    if (old != null) {
                        removeSlice(old);
                        arenaPool.retire(old.allocation(), slot);
                    }
                }
                case UPDATE -> {
                    TerrainSlice old = activeSlices.remove(event.key());
                    if (old != null) {
                        removeSlice(old);
                        arenaPool.retire(old.allocation(), slot);
                    }
                    ChunkMeshCapture.Captured cap = event.capture();
                    if (cap != null && cap.quadCount() > 0) {
                        int byteSize = cap.quadCount() * TerrainVertexFormat.VERTICES_PER_QUAD * TerrainVertexFormat.STRIDE;
                        TerrainArenaPool.Allocation alloc = arenaPool.allocate(byteSize);
                        arenaPool.upload(alloc, cap.vertices(), byteSize);
                        TerrainSlice slice = new TerrainSlice(
                                alloc.slabIndex(),
                                alloc.offset(),
                                alloc.offset() / TerrainVertexFormat.STRIDE,
                                cap.quadCount(),
                                cap.quadCount() * 6,
                                cap.chunkX(), cap.chunkY(), cap.chunkZ(),
                                cap.layer().ordinal(),
                                alloc
                        );
                        addSlice(slice);
                    }
                }
            }
        }
        source = activeSlices.size() + " active chunk layers across " + arenaPool.slabCount() + " slabs";
    }

    /**
     * Records one render layer's chunks, at the point in the frame where the game asked for that layer.
     */
    void recordLayer(VkCommandBuffer commandBuffer, int slot, float[] cameraMatrix, int layer) {
        if (arenaPool == null || layer < 0 || layer >= layerSlices.length) {
            return;
        }
        List<TerrainSlice> list = layerSlices[layer];
        if (list.isEmpty()) {
            return;
        }
        int drawn = 0;
        int layerSlicesCount = list.size();
        int culled = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanPipeline activePipeline = layer == net.minecraft.util.BlockRenderLayer.TRANSLUCENT.ordinal()
                    ? translucentPipeline : pipeline;
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, activePipeline.handle());
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, activePipeline.layout(), 0,
                    stack.longs(resolvedTexture, descriptors.lightmapSet(slot)), null);

            // Static shared index buffer: bound ONCE for the entire layer!
            vkCmdBindIndexBuffer(commandBuffer, arenaPool.indexBuffer().handle(), 0L, VK_INDEX_TYPE_UINT32);

            cameraScratch.clear();
            cameraScratch.put(cameraMatrix);
            cameraScratch.position(0);

            ByteBuffer constants = stack.malloc(VulkanPipeline.PUSH_CONSTANT_BYTES);
            constants.order(java.nio.ByteOrder.nativeOrder());

            boolean alphaTest = layer == net.minecraft.util.BlockRenderLayer.CUTOUT_MIPPED.ordinal()
                    || layer == net.minecraft.util.BlockRenderLayer.CUTOUT.ordinal();

            extractFrustumPlanes(cameraMatrix);

            int currentSlab = -1;
            for (int i = 0; i < list.size(); i++) {
                TerrainSlice slice = list.get(i);
                // Frustum cull chunks outside the view cone: [origin - 1, origin + 17] covers the 16x16x16 chunk
                // plus a 1-block margin for protruding geometry (torches, ladders, fences).
                if (!isBoxInFrustum(slice.originX() - 1.0f, slice.originY() - 1.0f, slice.originZ() - 1.0f,
                        slice.originX() + 17.0f, slice.originY() + 17.0f, slice.originZ() + 17.0f)) {
                    culled++;
                    continue;
                }

                // Rebind vertex buffer only if the slice belongs to a different slab
                if (slice.slabIndex() != currentSlab) {
                    currentSlab = slice.slabIndex();
                    vkCmdBindVertexBuffers(commandBuffer, 0,
                            stack.longs(arenaPool.slab(currentSlab).buffer().handle()),
                            stack.mallocLong(1).put(0, 0L));
                }

                chunkMatrix.set(cameraScratch).translate(slice.originX(), slice.originY(), slice.originZ());
                writeConstants(constants, chunkMatrix, alphaTest);
                vkCmdPushConstants(commandBuffer, activePipeline.layout(),
                        VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, constants);
                vkCmdDrawIndexed(commandBuffer, slice.indexCount(), 1, 0, slice.vertexOffset(), 0);
                drawn++;
            }
            if (++logFrameCounter % 300 == 1 && layerSlicesCount > 0) {
                LOGGER.info("terrain layer {}: {} of {} slices drawn (frustum culled: {})", layer, drawn, layerSlicesCount, culled);
            }
        }
        drawCount = drawn;
    }

    /**
     * Extracts the 6 frustum planes (A, B, C, D) from the column-major GL camera matrix (projection * modelview).
     * For any point inside the frustum, A*x + B*y + C*z + D >= 0.
     */
    static void extractFrustumPlanes(float[] m, float[] planes) {
        // Left plane: w + x >= 0 (row 3 + row 0)
        planes[0]  = m[3]  + m[0];
        planes[1]  = m[7]  + m[4];
        planes[2]  = m[11] + m[8];
        planes[3]  = m[15] + m[12];

        // Right plane: w - x >= 0 (row 3 - row 0)
        planes[4]  = m[3]  - m[0];
        planes[5]  = m[7]  - m[4];
        planes[6]  = m[11] - m[8];
        planes[7]  = m[15] - m[12];

        // Bottom plane: w + y >= 0 (row 3 + row 1)
        planes[8]  = m[3]  + m[1];
        planes[9]  = m[7]  + m[5];
        planes[10] = m[11] + m[9];
        planes[11] = m[15] + m[13];

        // Top plane: w - y >= 0 (row 3 - row 1)
        planes[12] = m[3]  - m[1];
        planes[13] = m[7]  - m[5];
        planes[14] = m[11] - m[9];
        planes[15] = m[15] - m[13];

        // Near plane: w + z >= 0 (row 3 + row 2)
        planes[16] = m[3]  + m[2];
        planes[17] = m[7]  + m[6];
        planes[18] = m[11] + m[10];
        planes[19] = m[15] + m[14];

        // Far plane: w - z >= 0 (row 3 - row 2)
        planes[20] = m[3]  - m[2];
        planes[21] = m[7]  - m[6];
        planes[22] = m[11] - m[10];
        planes[23] = m[15] - m[14];
    }

    private void extractFrustumPlanes(float[] m) {
        extractFrustumPlanes(m, frustumPlanes);
    }

    static boolean isBoxInFrustum(float[] planes, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        for (int p = 0; p < 24; p += 4) {
            float a = planes[p];
            float b = planes[p + 1];
            float c = planes[p + 2];
            float d = planes[p + 3];

            float px = a > 0.0f ? maxX : minX;
            float py = b > 0.0f ? maxY : minY;
            float pz = c > 0.0f ? maxZ : minZ;

            if (a * px + b * py + c * pz + d < 0.0f) {
                return false;
            }
        }
        return true;
    }

    private boolean isBoxInFrustum(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return isBoxInFrustum(frustumPlanes, minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static void writeConstants(ByteBuffer constants, Matrix4f matrix, boolean alphaTest) {
        constants.clear();
        matrix.get(constants);
        java.nio.FloatBuffer floats = constants.asFloatBuffer();
        for (int col = 0; col < 16; col += 4) {
            floats.put(col + 1, -floats.get(col + 1));
            floats.put(col + 2, 0.5F * (floats.get(col + 2) + floats.get(col + 3)));
        }
        floats.put(16, 0.1F);
        floats.put(17, alphaTest ? 1.0F : 0.0F);
        floats.put(18, 0.0F);
        constants.position(0);
    }

    /** Legacy adapter for tests or manual captures. */
    void useCaptured(java.util.List<ChunkMeshCapture.Captured> captured) {
        for (ChunkMeshCapture.Captured chunk : captured) {
            ChunkMeshCapture.Key key = new ChunkMeshCapture.Key(chunk.chunkX(), chunk.chunkY(), chunk.chunkZ(), chunk.layer());
            TerrainSlice old = activeSlices.remove(key);
            if (old != null) {
                removeSlice(old);
                arenaPool.retire(old.allocation(), 0);
            }
            if (chunk.quadCount() > 0) {
                int byteSize = chunk.quadCount() * TerrainVertexFormat.VERTICES_PER_QUAD * TerrainVertexFormat.STRIDE;
                TerrainArenaPool.Allocation alloc = arenaPool.allocate(byteSize);
                arenaPool.upload(alloc, chunk.vertices(), byteSize);
                TerrainSlice slice = new TerrainSlice(
                        alloc.slabIndex(),
                        alloc.offset(),
                        alloc.offset() / TerrainVertexFormat.STRIDE,
                        chunk.quadCount(),
                        chunk.quadCount() * 6,
                        chunk.chunkX(), chunk.chunkY(), chunk.chunkZ(),
                        chunk.layer().ordinal(),
                        alloc
                );
                addSlice(slice);
            }
        }
        arenaPool.releaseSlot(0);
        source = activeSlices.size() + " chunk layers";
    }

    @Override
    public void close() {
        if (arenaPool != null) {
            arenaPool.close();
        }
        pipeline.close();
        translucentPipeline.close();
    }
}
