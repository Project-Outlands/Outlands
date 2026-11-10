package net.minecraftforge.client.render.vulkan;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraftforge.client.render.ChunkMeshCapture;
import net.minecraftforge.client.render.graph.RenderGraphContext;
import net.minecraftforge.client.render.graph.RenderGraphNode;
import net.minecraftforge.client.render.graph.RenderPhases;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
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
import static org.lwjgl.vulkan.VK10.vkCmdDrawIndexedIndirect;
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

    static final int MAX_INDIRECT_CAPACITY = 65_536;
    private final VulkanBuffer[] indirectBuffers;
    private final VulkanBuffer[] chunkOffsetBuffers;
    private int chunkCursor;
    private int indirectCursor;
    private IntArrayList[] slabBuckets;

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
    private final List<TerrainSlice> translucentSortedList = new ArrayList<>();
    private double cameraX, cameraY, cameraZ;
    private final java.util.Comparator<TerrainSlice> translucentComparator = (a, b) -> {
        double dxA = a.originX() + 8.0 - cameraX;
        double dyA = a.originY() + 8.0 - cameraY;
        double dzA = a.originZ() + 8.0 - cameraZ;
        double distA = dxA * dxA + dyA * dyA + dzA * dzA;

        double dxB = b.originX() + 8.0 - cameraX;
        double dyB = b.originY() + 8.0 - cameraY;
        double dzB = b.originZ() + 8.0 - cameraZ;
        double distB = dxB * dxB + dyB * dyB + dzB * dzB;

        return Double.compare(distB, distA);
    };

    private VulkanTerrainLayer(VkDevice device, VulkanContext context, VulkanPipeline pipeline,
                               VulkanPipeline translucentPipeline,
                               VulkanDescriptors descriptors, VulkanTextures textures,
                               TerrainArenaPool arenaPool,
                               VulkanBuffer[] indirectBuffers,
                               VulkanBuffer[] chunkOffsetBuffers) {
        this.device = device;
        this.context = context;
        this.pipeline = pipeline;
        this.translucentPipeline = translucentPipeline;
        this.descriptors = descriptors;
        this.textures = textures;
        this.arenaPool = arenaPool;
        this.indirectBuffers = indirectBuffers;
        this.chunkOffsetBuffers = chunkOffsetBuffers;
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

        VulkanBuffer[] indirectBuffers = new VulkanBuffer[VulkanSwapchain.FRAMES_IN_FLIGHT];
        VulkanBuffer[] chunkOffsetBuffers = new VulkanBuffer[VulkanSwapchain.FRAMES_IN_FLIGHT];
        try {
            for (int slot = 0; slot < VulkanSwapchain.FRAMES_IN_FLIGHT; slot++) {
                indirectBuffers[slot] = VulkanBuffer.createIndirect(device, context, (long) MAX_INDIRECT_CAPACITY * 20L);
                chunkOffsetBuffers[slot] = VulkanBuffer.createChunkData(device, context, (long) MAX_INDIRECT_CAPACITY * 16L);
            }
        } catch (RuntimeException e) {
            for (int slot = 0; slot < VulkanSwapchain.FRAMES_IN_FLIGHT; slot++) {
                if (indirectBuffers[slot] != null) indirectBuffers[slot].close();
                if (chunkOffsetBuffers[slot] != null) chunkOffsetBuffers[slot].close();
            }
            arenaPool.close();
            pipeline.close();
            translucentPipeline.close();
            throw e;
        }

        return new VulkanTerrainLayer(device, context, pipeline, translucentPipeline, descriptors, textures, arenaPool,
                indirectBuffers, chunkOffsetBuffers);
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

    private void ensureSlabBuckets(int count) {
        if (slabBuckets == null || slabBuckets.length < count) {
            IntArrayList[] newBuckets = new IntArrayList[count];
            if (slabBuckets != null) {
                System.arraycopy(slabBuckets, 0, newBuckets, 0, slabBuckets.length);
            }
            for (int i = slabBuckets == null ? 0 : slabBuckets.length; i < count; i++) {
                newBuckets[i] = new IntArrayList();
            }
            slabBuckets = newBuckets;
        }
    }

    /**
     * Prepares terrain for the frame in flight. Called right after the fence for {@code slot} has signaled.
     *
     * <p>Frees allocations that were retired when this slot was last rendered, and processes any incremental
     * chunk mesh updates and removals from worker threads.</p>
     */
    void prepareFrame(int slot) {
        chunkCursor = 0;
        indirectCursor = 0;
        drawCount = 0;
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
        recordLayer(commandBuffer, slot, cameraMatrix, layer, 0.0, 0.0, 0.0);
    }

    /**
     * Records one render layer's chunks with camera viewpoint coordinates.
     */
    void recordLayer(VkCommandBuffer commandBuffer, int slot, float[] cameraMatrix, int layer,
                     double viewX, double viewY, double viewZ) {
        if (arenaPool == null || layer < 0 || layer >= layerSlices.length) {
            return;
        }
        List<TerrainSlice> list = layerSlices[layer];
        if (list.isEmpty()) {
            return;
        }
        int layerSlicesCount = list.size();
        int drawn = 0;
        int culled = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            boolean isTranslucent = layer == net.minecraft.util.BlockRenderLayer.TRANSLUCENT.ordinal();
            VulkanPipeline activePipeline = isTranslucent ? translucentPipeline : pipeline;
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

            chunkMatrix.set(cameraScratch);
            writeConstants(constants, chunkMatrix, chunkOffsetBuffers[slot].deviceAddress(), alphaTest);
            vkCmdPushConstants(commandBuffer, activePipeline.layout(),
                    VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, constants);

            long indirectMapped = indirectBuffers[slot].mappedAddress();
            long chunkOffsetMapped = chunkOffsetBuffers[slot].mappedAddress();
            int slabCount = arenaPool.slabCount();

            List<TerrainSlice> renderSlices;
            int renderSlicesCount;

            if (isTranslucent) {
                this.cameraX = viewX;
                this.cameraY = viewY;
                this.cameraZ = viewZ;
                translucentSortedList.clear();
                for (int i = 0; i < layerSlicesCount; i++) {
                    TerrainSlice slice = list.get(i);
                    if (!isBoxInFrustum(slice.originX() - 1.0f, slice.originY() - 1.0f, slice.originZ() - 1.0f,
                            slice.originX() + 17.0f, slice.originY() + 17.0f, slice.originZ() + 17.0f)) {
                        culled++;
                        continue;
                    }
                    translucentSortedList.add(slice);
                }
                translucentSortedList.sort(translucentComparator);
                renderSlices = translucentSortedList;
                renderSlicesCount = translucentSortedList.size();
            } else {
                renderSlices = list;
                renderSlicesCount = layerSlicesCount;
            }

            if (slabCount <= 1) {
                // Fast path: All slices in slab 0 (typical case)
                int startCmdIndex = indirectCursor;
                for (int i = 0; i < renderSlicesCount; i++) {
                    TerrainSlice slice = renderSlices.get(i);
                    if (!isTranslucent) {
                        // Frustum cull chunks outside the view cone: [origin - 1, origin + 17] covers the 16x16x16 chunk
                        // plus a 1-block margin for protruding geometry (torches, ladders, fences).
                        if (!isBoxInFrustum(slice.originX() - 1.0f, slice.originY() - 1.0f, slice.originZ() - 1.0f,
                                slice.originX() + 17.0f, slice.originY() + 17.0f, slice.originZ() + 17.0f)) {
                            culled++;
                            continue;
                        }
                    }

                    if (indirectCursor >= MAX_INDIRECT_CAPACITY) {
                        LOGGER.warn("Indirect draw buffer capacity ({}) reached in layer {}; skipping remaining slices",
                                MAX_INDIRECT_CAPACITY, layer);
                        break;
                    }

                    int chunkId = chunkCursor++;
                    long offsetAddr = chunkOffsetMapped + ((long) chunkId << 4);
                    MemoryUtil.memPutFloat(offsetAddr, (float) slice.originX());
                    MemoryUtil.memPutFloat(offsetAddr + 4, (float) slice.originY());
                    MemoryUtil.memPutFloat(offsetAddr + 8, (float) slice.originZ());
                    MemoryUtil.memPutFloat(offsetAddr + 12, 0.0f);

                    int cmdIndex = indirectCursor++;
                    long cmdAddr = indirectMapped + cmdIndex * 20L;
                    MemoryUtil.memPutInt(cmdAddr, slice.indexCount());
                    MemoryUtil.memPutInt(cmdAddr + 4, 1);
                    MemoryUtil.memPutInt(cmdAddr + 8, 0);
                    MemoryUtil.memPutInt(cmdAddr + 12, slice.vertexOffset());
                    MemoryUtil.memPutInt(cmdAddr + 16, chunkId);

                    drawn++;
                }

                if (drawn > 0) {
                    vkCmdBindVertexBuffers(commandBuffer, 0,
                            stack.longs(arenaPool.slab(0).buffer().handle()),
                            stack.mallocLong(1).put(0, 0L));
                    vkCmdDrawIndexedIndirect(commandBuffer, indirectBuffers[slot].handle(),
                            startCmdIndex * 20L, drawn, 20);
                }
            } else {
                // Multi-slab path: bucket visible slices by slabIndex
                ensureSlabBuckets(slabCount);
                for (int i = 0; i < renderSlicesCount; i++) {
                    TerrainSlice slice = renderSlices.get(i);
                    if (!isTranslucent) {
                        if (!isBoxInFrustum(slice.originX() - 1.0f, slice.originY() - 1.0f, slice.originZ() - 1.0f,
                                slice.originX() + 17.0f, slice.originY() + 17.0f, slice.originZ() + 17.0f)) {
                            culled++;
                            continue;
                        }
                    }
                    slabBuckets[slice.slabIndex()].add(i);
                }

                for (int s = 0; s < slabCount; s++) {
                    IntArrayList bucket = slabBuckets[s];
                    if (bucket.isEmpty()) {
                        continue;
                    }
                    int startCmdIndex = indirectCursor;
                    int bucketSize = bucket.size();
                    int slabDrawn = 0;
                    for (int k = 0; k < bucketSize; k++) {
                        if (indirectCursor >= MAX_INDIRECT_CAPACITY) {
                            LOGGER.warn("Indirect draw buffer capacity ({}) reached in layer {}; skipping remaining slices",
                                    MAX_INDIRECT_CAPACITY, layer);
                            break;
                        }
                        TerrainSlice slice = renderSlices.get(bucket.getInt(k));

                        int chunkId = chunkCursor++;
                        long offsetAddr = chunkOffsetMapped + ((long) chunkId << 4);
                        MemoryUtil.memPutFloat(offsetAddr, (float) slice.originX());
                        MemoryUtil.memPutFloat(offsetAddr + 4, (float) slice.originY());
                        MemoryUtil.memPutFloat(offsetAddr + 8, (float) slice.originZ());
                        MemoryUtil.memPutFloat(offsetAddr + 12, 0.0f);

                        int cmdIndex = indirectCursor++;
                        long cmdAddr = indirectMapped + cmdIndex * 20L;
                        MemoryUtil.memPutInt(cmdAddr, slice.indexCount());
                        MemoryUtil.memPutInt(cmdAddr + 4, 1);
                        MemoryUtil.memPutInt(cmdAddr + 8, 0);
                        MemoryUtil.memPutInt(cmdAddr + 12, slice.vertexOffset());
                        MemoryUtil.memPutInt(cmdAddr + 16, chunkId);
                        slabDrawn++;
                    }
                    bucket.clear();

                    if (slabDrawn > 0) {
                        vkCmdBindVertexBuffers(commandBuffer, 0,
                                stack.longs(arenaPool.slab(s).buffer().handle()),
                                stack.mallocLong(1).put(0, 0L));
                        vkCmdDrawIndexedIndirect(commandBuffer, indirectBuffers[slot].handle(),
                                startCmdIndex * 20L, slabDrawn, 20);
                        drawn += slabDrawn;
                    }
                    if (indirectCursor >= MAX_INDIRECT_CAPACITY) {
                        for (int remaining = s + 1; remaining < slabCount; remaining++) {
                            slabBuckets[remaining].clear();
                        }
                        break;
                    }
                }
            }

            if (++logFrameCounter % 300 == 1 && layerSlicesCount > 0) {
                LOGGER.info("terrain layer {}: {} of {} slices drawn via MDI (frustum culled: {})",
                        layer, drawn, layerSlicesCount, culled);
            }
        }
        drawCount += drawn;
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

    private static void writeConstants(ByteBuffer constants, Matrix4f matrix, long chunkDataAddress, boolean alphaTest) {
        constants.clear();
        matrix.get(constants);
        FloatBuffer floats = constants.asFloatBuffer();
        for (int col = 0; col < 16; col += 4) {
            floats.put(col + 1, -floats.get(col + 1));
            floats.put(col + 2, 0.5F * (floats.get(col + 2) + floats.get(col + 3)));
        }
        constants.putLong(64, chunkDataAddress);
        constants.putFloat(72, 0.1F);
        constants.putFloat(76, alphaTest ? 1.0F : 0.0F);
        constants.putFloat(80, 0.0F);
        constants.position(0);
        constants.limit(88);
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
        for (int slot = 0; slot < VulkanSwapchain.FRAMES_IN_FLIGHT; slot++) {
            if (indirectBuffers[slot] != null) {
                indirectBuffers[slot].close();
            }
            if (chunkOffsetBuffers[slot] != null) {
                chunkOffsetBuffers[slot].close();
            }
        }
        if (arenaPool != null) {
            arenaPool.close();
        }
        pipeline.close();
        translucentPipeline.close();
    }
}
