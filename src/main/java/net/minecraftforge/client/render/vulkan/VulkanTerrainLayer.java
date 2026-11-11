package net.minecraftforge.client.render.vulkan;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.util.math.BlockPos;
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

    static final int NUM_LAYERS = net.minecraft.util.BlockRenderLayer.values().length;
    static final int MAX_INDIRECT_CAPACITY = 65_536;
    static final int LAYER_CAPACITY = MAX_INDIRECT_CAPACITY / NUM_LAYERS; // 16,384 per layer

    private final VulkanBuffer[] indirectBuffers;
    private final VulkanBuffer[] chunkOffsetBuffers;
    private IntArrayList[] slabBuckets;

    // Temporal cache management
    private final long[] geometryVersion = new long[NUM_LAYERS];

    // Tier 1: Per-slot GPU buffer cache (direct dispatch without memory touching)
    private final boolean[][] slotCacheValid = new boolean[VulkanSwapchain.FRAMES_IN_FLIGHT][NUM_LAYERS];
    private final long[][] slotGeometryVersion = new long[VulkanSwapchain.FRAMES_IN_FLIGHT][NUM_LAYERS];
    private final long[][] slotVisibilityHash = new long[VulkanSwapchain.FRAMES_IN_FLIGHT][NUM_LAYERS];
    private final int[][] slotDrawnTotal = new int[VulkanSwapchain.FRAMES_IN_FLIGHT][NUM_LAYERS];
    private final Batches[][] slotBatches = new Batches[VulkanSwapchain.FRAMES_IN_FLIGHT][NUM_LAYERS];

    // Tier 2: Shared host staging buffer cache (fast memcpy across slots without map lookups or formatting)
    private final boolean[] stagingCacheValid = new boolean[NUM_LAYERS];
    private final long[] stagingGeometryVersion = new long[NUM_LAYERS];
    private final long[] stagingVisibilityHash = new long[NUM_LAYERS];
    private final int[] stagingDrawnTotal = new int[NUM_LAYERS];
    private final Batches[] stagingBatches = new Batches[NUM_LAYERS];

    /**
     * One layer's GPU draw batches: each entry is one {@code vkCmdDrawIndexedIndirect} over one arena slab.
     *
     * <p>Grown on demand rather than sized to a constant. The previous fixed ceiling was 32, and a layer that needed
     * more silently kept the first 32 batches - the draws for the remaining slabs were issued while the frame was
     * built and then forgotten, so they disappeared on the next cache hit. A fragmented arena is exactly when this
     * happens, which made it look like whole chunks flickering away.</p>
     */
    private static final class Batches {
        private final IntArrayList slab = new IntArrayList();
        private final IntArrayList firstCommand = new IntArrayList();
        private final IntArrayList commandCount = new IntArrayList();

        void clear() {
            slab.clear();
            firstCommand.clear();
            commandCount.clear();
        }

        void add(int slabIndex, int first, int count) {
            slab.add(slabIndex);
            firstCommand.add(first);
            commandCount.add(count);
        }

        int size() {
            return slab.size();
        }

        int slab(int i) {
            return slab.getInt(i);
        }

        int firstCommand(int i) {
            return firstCommand.getInt(i);
        }

        int commandCount(int i) {
            return commandCount.getInt(i);
        }

        void copyFrom(Batches other) {
            clear();
            slab.addAll(other.slab);
            firstCommand.addAll(other.firstCommand);
            commandCount.addAll(other.commandCount);
        }
    }

    private final Map<ChunkMeshCapture.Key, TerrainSlice> activeSlices = new HashMap<>();
    @SuppressWarnings("unchecked")
    private final List<TerrainSlice>[] layerSlices = new List[net.minecraft.util.BlockRenderLayer.values().length];
    @SuppressWarnings("unchecked")
    private final Long2ObjectOpenHashMap<TerrainSlice>[] layerSliceMaps =
            new Long2ObjectOpenHashMap[net.minecraft.util.BlockRenderLayer.values().length];
    private final ByteBuffer hostChunkOffsetStaging;
    private final ByteBuffer hostIndirectStaging;
    private final long hostOffsetStagingAddr;
    private final long hostIndirectStagingAddr;

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
            layerSliceMaps[i] = new Long2ObjectOpenHashMap<>();
            stagingBatches[i] = new Batches();
            for (int s = 0; s < VulkanSwapchain.FRAMES_IN_FLIGHT; s++) {
                slotBatches[s][i] = new Batches();
            }
        }
        this.hostChunkOffsetStaging = ByteBuffer.allocateDirect(MAX_INDIRECT_CAPACITY * 16)
                .order(java.nio.ByteOrder.nativeOrder());
        this.hostIndirectStaging = ByteBuffer.allocateDirect(MAX_INDIRECT_CAPACITY * 20)
                .order(java.nio.ByteOrder.nativeOrder());
        this.hostOffsetStagingAddr = MemoryUtil.memAddress(hostChunkOffsetStaging);
        this.hostIndirectStagingAddr = MemoryUtil.memAddress(hostIndirectStaging);
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
            pipeline = VulkanPipeline.createTerrain(device, vertex, fragment, colorFormat, depthFormat,
                    descriptors.pipelineLayout(), descriptors.layout(), VulkanPipeline.PipelineState.terrain());
            translucentPipeline = VulkanPipeline.createTerrain(device, vertex, fragment, colorFormat, depthFormat,
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

    static long chunkPosKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (long) (z & 0x3FFFFFF);
    }

    private void invalidateLayerCache(int layer) {
        if (layer >= 0 && layer < NUM_LAYERS) {
            geometryVersion[layer]++;
            invalidateCacheEntries(layer);
        }
    }

    private void invalidateCacheEntries(int layer) {
        stagingCacheValid[layer] = false;
        stagingBatches[layer].clear();
        for (int s = 0; s < VulkanSwapchain.FRAMES_IN_FLIGHT; s++) {
            slotCacheValid[s][layer] = false;
            slotBatches[s][layer].clear();
        }
    }

    private void invalidateAllCaches() {
        for (int l = 0; l < NUM_LAYERS; l++) {
            geometryVersion[l]++;
            invalidateCacheEntries(l);
        }
    }

    static long hashVisibleChunks(LongArrayList list) {
        if (list == null || list.isEmpty()) {
            return 0L;
        }
        int size = list.size();
        long[] elements = list.elements();
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < size; i++) {
            h ^= elements[i];
            h *= 1099511628211L;
        }
        return h;
    }

    static long hashFrustum(float[] planes, double vx, double vy, double vz) {
        long h = 0xcbf29ce484222325L;
        for (float f : planes) {
            h ^= Float.floatToIntBits(f);
            h *= 1099511628211L;
        }
        h ^= Double.doubleToLongBits(vx);
        h *= 1099511628211L;
        h ^= Double.doubleToLongBits(vy);
        h *= 1099511628211L;
        h ^= Double.doubleToLongBits(vz);
        h *= 1099511628211L;
        return h;
    }

    private void addSlice(TerrainSlice slice) {
        List<TerrainSlice> list = layerSlices[slice.layer()];
        slice.setLayerListIndex(list.size());
        list.add(slice);
        activeSlices.put(new ChunkMeshCapture.Key(slice.originX(), slice.originY(), slice.originZ(),
                net.minecraft.util.BlockRenderLayer.values()[slice.layer()]), slice);
        layerSliceMaps[slice.layer()].put(chunkPosKey(slice.originX(), slice.originY(), slice.originZ()), slice);
        invalidateLayerCache(slice.layer());
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
        layerSliceMaps[slice.layer()].remove(chunkPosKey(slice.originX(), slice.originY(), slice.originZ()));
        invalidateLayerCache(slice.layer());
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
                    for (Long2ObjectOpenHashMap<TerrainSlice> map : layerSliceMaps) {
                        map.clear();
                    }
                    invalidateAllCaches();
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
                        int byteSize = cap.quadCount() * TerrainVertexFormat.COMPACT_QUAD_SIZE;
                        TerrainArenaPool.Allocation alloc = arenaPool.allocate(byteSize);
                        if (cap.vertices().length == byteSize) {
                            arenaPool.upload(alloc, cap.vertices(), byteSize);
                        } else {
                            arenaPool.uploadCompact(alloc, cap.vertices(), cap.quadCount());
                        }
                        TerrainSlice slice = new TerrainSlice(
                                alloc.slabIndex(),
                                alloc.offset(),
                                alloc.offset() / TerrainVertexFormat.COMPACT_STRIDE,
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
        events.clear();
        source = activeSlices.size() + " active chunk layers across " + arenaPool.slabCount() + " slabs";
    }

    /**
     * Records one render layer's chunks, at the point in the frame where the game asked for that layer.
     */
    void recordLayer(VkCommandBuffer commandBuffer, int slot, float[] cameraMatrix, int layer) {
        recordLayer(commandBuffer, slot, cameraMatrix, layer, 0.0, 0.0, 0.0, 0, 0.0f, 1.0f, 1.0f, null, null);
    }

    /**
     * Records one render layer's chunks with camera viewpoint coordinates.
     */
    void recordLayer(VkCommandBuffer commandBuffer, int slot, float[] cameraMatrix, int layer,
                     double viewX, double viewY, double viewZ) {
        recordLayer(commandBuffer, slot, cameraMatrix, layer, viewX, viewY, viewZ, 0, 0.0f, 1.0f, 1.0f, null, null);
    }

    /**
     * Records one render layer's chunks with camera viewpoint, fog parameters, and optional visible chunk list.
     */
    void recordLayer(VkCommandBuffer commandBuffer, int slot, float[] cameraMatrix, int layer,
                     double viewX, double viewY, double viewZ,
                     int fogMode, float fogStart, float fogEnd, float fogDensity, float[] fogColor,
                     LongArrayList visibleChunks) {
        if (arenaPool == null || layer < 0 || layer >= layerSlices.length) {
            return;
        }
        List<TerrainSlice> list = layerSlices[layer];
        if (list.isEmpty() && (visibleChunks == null || visibleChunks.isEmpty())) {
            return;
        }
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
            writeConstants(constants, chunkMatrix, chunkOffsetBuffers[slot].deviceAddress(), alphaTest,
                    fogMode, fogStart, fogEnd, fogDensity, fogColor, viewX, viewY, viewZ);
            vkCmdPushConstants(commandBuffer, activePipeline.layout(),
                    VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, constants);

            int layerBaseCmd = layer * LAYER_CAPACITY;
            int layerBaseChunk = layer * LAYER_CAPACITY;

            // The translucent layer is sorted back to front every time the camera moves, and the visible-chunk list
            // it sorts does not change when the camera merely turns. So its command order is a function of the camera
            // as well, and caching it on the chunk list alone would keep replaying the order from wherever the player
            // used to stand - water and glass blending against each other in stale order. Rebuilding this one layer
            // every frame costs the sort the game already does.
            boolean cacheable = !isTranslucent;

            long visHash = (visibleChunks != null)
                    ? hashVisibleChunks(visibleChunks)
                    : hashFrustum(frustumPlanes, viewX, viewY, viewZ);

            // Tier 1 Check: Current slot already has the exact GPU buffer!
            if (cacheable && slotCacheValid[slot][layer]
                    && slotGeometryVersion[slot][layer] == geometryVersion[layer]
                    && slotVisibilityHash[slot][layer] == visHash) {
                Batches batches = slotBatches[slot][layer];
                int batchCount = batches.size();
                for (int b = 0; b < batchCount; b++) {
                    int slab = batches.slab(b);
                    int firstCmd = batches.firstCommand(b);
                    int count = batches.commandCount(b);
                    vkCmdBindVertexBuffers(commandBuffer, 0,
                            stack.longs(arenaPool.slab(slab).buffer().handle()),
                            stack.mallocLong(1).put(0, 0L));
                    vkCmdDrawIndexedIndirect(commandBuffer, indirectBuffers[slot].handle(),
                            (long) firstCmd * 20L, count, 20);
                }
                drawn = slotDrawnTotal[slot][layer];
                drawCount += drawn;
                if (++logFrameCounter % 300 == 1 && list.size() > 0) {
                    LOGGER.info("terrain layer {}: {} of {} slices drawn via MDI (cache: TIER 1 GPU HIT)",
                            layer, drawn, list.size());
                }
                return;
            }

            long indirectMapped = indirectBuffers[slot].mappedAddress();
            long chunkOffsetMapped = chunkOffsetBuffers[slot].mappedAddress();

            // Tier 2 Check: Shared host staging buffer already has the exact data!
            if (cacheable && stagingCacheValid[layer]
                    && stagingGeometryVersion[layer] == geometryVersion[layer]
                    && stagingVisibilityHash[layer] == visHash) {
                int totalDrawn = stagingDrawnTotal[layer];
                if (totalDrawn > 0) {
                    long hostOffsetBase = hostOffsetStagingAddr + ((long) layerBaseChunk << 4);
                    long hostIndirectBase = hostIndirectStagingAddr + (long) layerBaseCmd * 20L;
                    long gpuOffsetBase = chunkOffsetMapped + ((long) layerBaseChunk << 4);
                    long gpuIndirectBase = indirectMapped + (long) layerBaseCmd * 20L;
                    MemoryUtil.memCopy(hostOffsetBase, gpuOffsetBase, (long) totalDrawn << 4);
                    MemoryUtil.memCopy(hostIndirectBase, gpuIndirectBase, (long) totalDrawn * 20L);
                }
                Batches staged = stagingBatches[layer];
                Batches batches = slotBatches[slot][layer];
                batches.copyFrom(staged);
                int batchCount = batches.size();
                for (int b = 0; b < batchCount; b++) {
                    int slab = batches.slab(b);
                    int firstCmd = batches.firstCommand(b);
                    int count = batches.commandCount(b);
                    vkCmdBindVertexBuffers(commandBuffer, 0,
                            stack.longs(arenaPool.slab(slab).buffer().handle()),
                            stack.mallocLong(1).put(0, 0L));
                    vkCmdDrawIndexedIndirect(commandBuffer, indirectBuffers[slot].handle(),
                            (long) firstCmd * 20L, count, 20);
                }
                slotDrawnTotal[slot][layer] = totalDrawn;
                slotGeometryVersion[slot][layer] = geometryVersion[layer];
                slotVisibilityHash[slot][layer] = visHash;
                slotCacheValid[slot][layer] = cacheable;
                drawn = totalDrawn;
                drawCount += drawn;
                if (++logFrameCounter % 300 == 1 && list.size() > 0) {
                    LOGGER.info("terrain layer {}: {} of {} slices drawn via MDI (cache: TIER 2 STAGING HIT)",
                            layer, drawn, list.size());
                }
                return;
            }

            // Tier 3: Cache Miss (Full Rebuild into Staging + Copy to Slot)
            int slabCount = arenaPool.slabCount();
            List<TerrainSlice> renderSlices;
            int renderSlicesCount;
            boolean bypassFrustum = false;

            if (visibleChunks != null) {
                translucentSortedList.clear();
                int count = visibleChunks.size();
                var map = layerSliceMaps[layer];
                for (int i = 0; i < count; i++) {
                    TerrainSlice slice = map.get(visibleChunks.getLong(i));
                    if (slice != null) {
                        translucentSortedList.add(slice);
                    }
                }
                if (isTranslucent) {
                    this.cameraX = viewX;
                    this.cameraY = viewY;
                    this.cameraZ = viewZ;
                    translucentSortedList.sort(translucentComparator);
                }
                renderSlices = translucentSortedList;
                renderSlicesCount = renderSlices.size();
                bypassFrustum = true;
            } else if (isTranslucent) {
                this.cameraX = viewX;
                this.cameraY = viewY;
                this.cameraZ = viewZ;
                translucentSortedList.clear();
                int layerSlicesCount = list.size();
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
                renderSlicesCount = list.size();
            }

            long hostOffsetBase = hostOffsetStagingAddr + ((long) layerBaseChunk << 4);
            long hostIndirectBase = hostIndirectStagingAddr + (long) layerBaseCmd * 20L;
            stagingBatches[layer].clear();
            int batchCount = 0;

            if (slabCount <= 1) {
                // Fast path: All slices in slab 0 (typical case)
                int layerDrawn = 0;
                for (int i = 0; i < renderSlicesCount; i++) {
                    TerrainSlice slice = renderSlices.get(i);
                    if (!bypassFrustum && !isTranslucent) {
                        // Frustum cull chunks outside the view cone: [origin - 1, origin + 17] covers the 16x16x16 chunk
                        // plus a 1-block margin for protruding geometry (torches, ladders, fences).
                        if (!isBoxInFrustum(slice.originX() - 1.0f, slice.originY() - 1.0f, slice.originZ() - 1.0f,
                                slice.originX() + 17.0f, slice.originY() + 17.0f, slice.originZ() + 17.0f)) {
                            culled++;
                            continue;
                        }
                    }

                    if (layerDrawn >= LAYER_CAPACITY) {
                        LOGGER.warn("Layer indirect buffer capacity ({}) reached in layer {}; skipping remaining slices",
                                LAYER_CAPACITY, layer);
                        break;
                    }

                    int chunkId = layerBaseChunk + layerDrawn;
                    long offsetAddr = hostOffsetBase + ((long) layerDrawn << 4);
                    MemoryUtil.memPutFloat(offsetAddr, (float) slice.originX());
                    MemoryUtil.memPutFloat(offsetAddr + 4, (float) slice.originY());
                    MemoryUtil.memPutFloat(offsetAddr + 8, (float) slice.originZ());
                    MemoryUtil.memPutFloat(offsetAddr + 12, 0.0f);

                    long cmdAddr = hostIndirectBase + (long) layerDrawn * 20L;
                    MemoryUtil.memPutInt(cmdAddr, slice.indexCount());
                    MemoryUtil.memPutInt(cmdAddr + 4, 1);
                    MemoryUtil.memPutInt(cmdAddr + 8, 0);
                    MemoryUtil.memPutInt(cmdAddr + 12, slice.vertexOffset());
                    MemoryUtil.memPutInt(cmdAddr + 16, chunkId);

                    layerDrawn++;
                }

                if (layerDrawn > 0) {
                    stagingBatches[layer].add(0, layerBaseCmd, layerDrawn);
                    batchCount = 1;

                    long gpuOffsetBase = chunkOffsetMapped + ((long) layerBaseChunk << 4);
                    long gpuIndirectBase = indirectMapped + (long) layerBaseCmd * 20L;
                    MemoryUtil.memCopy(hostOffsetBase, gpuOffsetBase, (long) layerDrawn << 4);
                    MemoryUtil.memCopy(hostIndirectBase, gpuIndirectBase, (long) layerDrawn * 20L);

                    vkCmdBindVertexBuffers(commandBuffer, 0,
                            stack.longs(arenaPool.slab(0).buffer().handle()),
                            stack.mallocLong(1).put(0, 0L));
                    vkCmdDrawIndexedIndirect(commandBuffer, indirectBuffers[slot].handle(),
                            (long) layerBaseCmd * 20L, layerDrawn, 20);
                }
                drawn = layerDrawn;
            } else {
                // Multi-slab path: bucket visible slices by slabIndex
                ensureSlabBuckets(slabCount);
                for (int i = 0; i < renderSlicesCount; i++) {
                    TerrainSlice slice = renderSlices.get(i);
                    if (!bypassFrustum && !isTranslucent) {
                        if (!isBoxInFrustum(slice.originX() - 1.0f, slice.originY() - 1.0f, slice.originZ() - 1.0f,
                                slice.originX() + 17.0f, slice.originY() + 17.0f, slice.originZ() + 17.0f)) {
                            culled++;
                            continue;
                        }
                    }
                    slabBuckets[slice.slabIndex()].add(i);
                }

                int layerDrawn = 0;
                for (int s = 0; s < slabCount; s++) {
                    IntArrayList bucket = slabBuckets[s];
                    if (bucket.isEmpty()) {
                        continue;
                    }
                    int startCmdIndex = layerBaseCmd + layerDrawn;
                    int startChunkId = layerBaseChunk + layerDrawn;
                    int bucketSize = bucket.size();
                    int slabDrawn = 0;
                    for (int k = 0; k < bucketSize; k++) {
                        if (layerDrawn >= LAYER_CAPACITY) {
                            LOGGER.warn("Layer indirect buffer capacity ({}) reached in layer {}; skipping remaining slices",
                                    LAYER_CAPACITY, layer);
                            break;
                        }
                        TerrainSlice slice = renderSlices.get(bucket.getInt(k));

                        int chunkId = startChunkId + slabDrawn;
                        long offsetAddr = hostOffsetBase + ((long) layerDrawn << 4);
                        MemoryUtil.memPutFloat(offsetAddr, (float) slice.originX());
                        MemoryUtil.memPutFloat(offsetAddr + 4, (float) slice.originY());
                        MemoryUtil.memPutFloat(offsetAddr + 8, (float) slice.originZ());
                        MemoryUtil.memPutFloat(offsetAddr + 12, 0.0f);

                        long cmdAddr = hostIndirectBase + (long) layerDrawn * 20L;
                        MemoryUtil.memPutInt(cmdAddr, slice.indexCount());
                        MemoryUtil.memPutInt(cmdAddr + 4, 1);
                        MemoryUtil.memPutInt(cmdAddr + 8, 0);
                        MemoryUtil.memPutInt(cmdAddr + 12, slice.vertexOffset());
                        MemoryUtil.memPutInt(cmdAddr + 16, chunkId);
                        slabDrawn++;
                        layerDrawn++;
                    }
                    bucket.clear();

                    if (slabDrawn > 0) {
                        stagingBatches[layer].add(s, startCmdIndex, slabDrawn);
                        batchCount++;
                        long gpuOffsetBase = chunkOffsetMapped + ((long) startChunkId << 4);
                        long gpuIndirectBase = indirectMapped + (long) startCmdIndex * 20L;
                        long srcOffset = (long) (startChunkId - layerBaseChunk) << 4;
                        long srcIndirect = (long) (startCmdIndex - layerBaseCmd) * 20L;
                        MemoryUtil.memCopy(hostOffsetBase + srcOffset, gpuOffsetBase, (long) slabDrawn << 4);
                        MemoryUtil.memCopy(hostIndirectBase + srcIndirect, gpuIndirectBase, (long) slabDrawn * 20L);

                        vkCmdBindVertexBuffers(commandBuffer, 0,
                                stack.longs(arenaPool.slab(s).buffer().handle()),
                                stack.mallocLong(1).put(0, 0L));
                        vkCmdDrawIndexedIndirect(commandBuffer, indirectBuffers[slot].handle(),
                                (long) startCmdIndex * 20L, slabDrawn, 20);
                    }
                    if (layerDrawn >= LAYER_CAPACITY) {
                        for (int remaining = s + 1; remaining < slabCount; remaining++) {
                            slabBuckets[remaining].clear();
                        }
                        break;
                    }
                }
                drawn = layerDrawn;
            }

            // Update both Tier 2 and Tier 1 caches

            stagingDrawnTotal[layer] = drawn;
            stagingGeometryVersion[layer] = geometryVersion[layer];
            stagingVisibilityHash[layer] = visHash;
            stagingCacheValid[layer] = cacheable;

            slotBatches[slot][layer].copyFrom(stagingBatches[layer]);
            slotDrawnTotal[slot][layer] = drawn;
            slotGeometryVersion[slot][layer] = geometryVersion[layer];
            slotVisibilityHash[slot][layer] = visHash;
            slotCacheValid[slot][layer] = cacheable;

            drawCount += drawn;
            if (++logFrameCounter % 300 == 1 && list.size() > 0) {
                LOGGER.info("terrain layer {}: {} of {} slices drawn via MDI (cache: REBUILD MISS, culled: {})",
                        layer, drawn, list.size(), culled);
            }
        }
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

    private static void writeConstants(ByteBuffer constants, Matrix4f matrix, long chunkDataAddress, boolean alphaTest,
                                       int fogMode, float fogStart, float fogEnd, float fogDensity, float[] fogColor,
                                       double viewX, double viewY, double viewZ) {
        constants.clear();
        matrix.get(constants);
        FloatBuffer floats = constants.asFloatBuffer();
        for (int col = 0; col < 16; col += 4) {
            floats.put(col + 1, -floats.get(col + 1));
            floats.put(col + 2, 0.5F * (floats.get(col + 2) + floats.get(col + 3)));
        }
        constants.putFloat(64, fogColor != null ? fogColor[0] : 0.0f);
        constants.putFloat(68, fogColor != null ? fogColor[1] : 0.0f);
        constants.putFloat(72, fogColor != null ? fogColor[2] : 0.0f);
        constants.putFloat(76, fogColor != null ? fogColor[3] : 1.0f);
        constants.putLong(80, chunkDataAddress);
        constants.putFloat(88, 0.1F);
        constants.putFloat(92, alphaTest ? 1.0F : 0.0F);
        constants.putFloat(96, 0.0F);
        constants.putInt(100, fogMode);
        constants.putFloat(104, fogStart);
        constants.putFloat(108, fogEnd);
        constants.putFloat(112, fogDensity);
        constants.putFloat(116, (float) viewX);
        constants.putFloat(120, (float) viewY);
        constants.putFloat(124, (float) viewZ);
        constants.position(0);
        constants.limit(128);
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
                int byteSize = chunk.quadCount() * TerrainVertexFormat.COMPACT_QUAD_SIZE;
                TerrainArenaPool.Allocation alloc = arenaPool.allocate(byteSize);
                if (chunk.vertices().length == byteSize) {
                    arenaPool.upload(alloc, chunk.vertices(), byteSize);
                } else {
                    arenaPool.uploadCompact(alloc, chunk.vertices(), chunk.quadCount());
                }
                TerrainSlice slice = new TerrainSlice(
                        alloc.slabIndex(),
                        alloc.offset(),
                        alloc.offset() / TerrainVertexFormat.COMPACT_STRIDE,
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
        invalidateAllCaches();
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
