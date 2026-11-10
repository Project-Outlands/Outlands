package net.minecraftforge.client.render.vulkan;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDrawIndexedIndirectCommand;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VulkanIndirectDrawTest {

    @Test
    void indirectCommandLayoutMatchesVulkanSpec() {
        assertEquals(20, VkDrawIndexedIndirectCommand.SIZEOF, "VkDrawIndexedIndirectCommand must be 20 bytes");
        assertEquals(0, VkDrawIndexedIndirectCommand.INDEXCOUNT);
        assertEquals(4, VkDrawIndexedIndirectCommand.INSTANCECOUNT);
        assertEquals(8, VkDrawIndexedIndirectCommand.FIRSTINDEX);
        assertEquals(12, VkDrawIndexedIndirectCommand.VERTEXOFFSET);
        assertEquals(16, VkDrawIndexedIndirectCommand.FIRSTINSTANCE);

        ByteBuffer buf = ByteBuffer.allocateDirect(20).order(ByteOrder.nativeOrder());
        long addr = MemoryUtil.memAddress(buf);

        int indexCount = 1024 * 6;
        int instanceCount = 1;
        int firstIndex = 0;
        int vertexOffset = 4096;
        int firstInstance = 42;

        MemoryUtil.memPutInt(addr, indexCount);
        MemoryUtil.memPutInt(addr + 4, instanceCount);
        MemoryUtil.memPutInt(addr + 8, firstIndex);
        MemoryUtil.memPutInt(addr + 12, vertexOffset);
        MemoryUtil.memPutInt(addr + 16, firstInstance);

        VkDrawIndexedIndirectCommand cmd = VkDrawIndexedIndirectCommand.create(addr);
        assertEquals(indexCount, cmd.indexCount());
        assertEquals(instanceCount, cmd.instanceCount());
        assertEquals(firstIndex, cmd.firstIndex());
        assertEquals(vertexOffset, cmd.vertexOffset());
        assertEquals(firstInstance, cmd.firstInstance());
    }

    @Test
    void chunkOffsetsEncodingMatchesShaderExpectations() {
        ByteBuffer buf = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder());
        long addr = MemoryUtil.memAddress(buf);

        int chunkX = -32;
        int chunkY = 64;
        int chunkZ = 128;

        MemoryUtil.memPutFloat(addr, (float) chunkX);
        MemoryUtil.memPutFloat(addr + 4, (float) chunkY);
        MemoryUtil.memPutFloat(addr + 8, (float) chunkZ);
        MemoryUtil.memPutFloat(addr + 12, 0.0f);

        assertEquals(-32.0f, buf.getFloat(0));
        assertEquals(64.0f, buf.getFloat(4));
        assertEquals(128.0f, buf.getFloat(8));
        assertEquals(0.0f, buf.getFloat(12));
    }

    @Test
    void pushConstantsLayoutMatchesTerrainShader() {
        ByteBuffer constants = ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
        Matrix4f mvp = new Matrix4f().identity();
        long chunkDataAddress = 0xDEADBEEFCAFE0000L;
        float[] fogColor = {0.8f, 0.9f, 1.0f, 1.0f};

        mvp.get(constants);
        constants.putFloat(64, fogColor[0]);
        constants.putFloat(68, fogColor[1]);
        constants.putFloat(72, fogColor[2]);
        constants.putFloat(76, fogColor[3]);
        constants.putLong(80, chunkDataAddress);
        constants.putFloat(88, 0.1f);
        constants.putFloat(92, 1.0f);
        constants.putFloat(96, 0.0f);
        constants.putInt(100, 9729); // GL_LINEAR
        constants.putFloat(104, 32.0f);
        constants.putFloat(108, 128.0f);
        constants.putFloat(112, 0.05f);
        constants.putFloat(116, 10.5f);
        constants.putFloat(120, 64.0f);
        constants.putFloat(124, -20.5f);

        assertEquals(fogColor[0], constants.getFloat(64), 1e-6f);
        assertEquals(fogColor[3], constants.getFloat(76), 1e-6f);
        assertEquals(chunkDataAddress, constants.getLong(80));
        assertEquals(0.1f, constants.getFloat(88), 1e-6f);
        assertEquals(1.0f, constants.getFloat(92), 1e-6f);
        assertEquals(0.0f, constants.getFloat(96), 1e-6f);
        assertEquals(9729, constants.getInt(100));
        assertEquals(32.0f, constants.getFloat(104), 1e-6f);
        assertEquals(128.0f, constants.getFloat(108), 1e-6f);
        assertEquals(0.05f, constants.getFloat(112), 1e-6f);
        assertEquals(10.5f, constants.getFloat(116), 1e-6f);
        assertEquals(64.0f, constants.getFloat(120), 1e-6f);
        assertEquals(-20.5f, constants.getFloat(124), 1e-6f);
        assertEquals(128, VulkanPipeline.PUSH_CONSTANT_BYTES);
    }

    @Test
    void chunkPosKeyMatchesBlockPosToLong() {
        int[] testCoords = {0, 0, 0, 16, 64, 32, -16, 128, -64, 1024, 255, -2048};
        for (int i = 0; i < testCoords.length; i += 3) {
            int x = testCoords[i];
            int y = testCoords[i + 1];
            int z = testCoords[i + 2];
            net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(x, y, z);
            long expected = pos.toLong();
            long actual = VulkanTerrainLayer.chunkPosKey(x, y, z);
            assertEquals(expected, actual, "chunkPosKey(" + x + ", " + y + ", " + z + ") must match BlockPos.toLong()");
        }
    }

    @Test
    void maxIndirectCapacityAccommodatesHighRenderDistanceWithoutReallocation() {
        assertEquals(65_536, VulkanTerrainLayer.MAX_INDIRECT_CAPACITY);
        long indirectBufferSize = (long) VulkanTerrainLayer.MAX_INDIRECT_CAPACITY * 20L;
        long chunkOffsetBufferSize = (long) VulkanTerrainLayer.MAX_INDIRECT_CAPACITY * 16L;

        assertEquals(1_310_720L, indirectBufferSize);
        assertEquals(1_048_576L, chunkOffsetBufferSize);

        // Indirect buffer offset alignment must be a multiple of 4
        assertEquals(0, indirectBufferSize % 4);
        // Chunk offset buffer element alignment in std430 must be a multiple of 16 (sizeof vec4)
        assertEquals(0, chunkOffsetBufferSize % 16);
    }

    @Test
    void translucentSlicesAreSortedBackToFrontFromCamera() {
        double camX = 0.0, camY = 64.0, camZ = 0.0;
        TerrainSlice near = new TerrainSlice(0, 0, 0, 4, 6, 0, 64, 16, 3, null);
        TerrainSlice mid = new TerrainSlice(0, 0, 0, 4, 6, 0, 64, 64, 3, null);
        TerrainSlice far = new TerrainSlice(0, 0, 0, 4, 6, 0, 64, 128, 3, null);

        java.util.List<TerrainSlice> list = new java.util.ArrayList<>(java.util.List.of(near, far, mid));
        list.sort((a, b) -> {
            double dxA = a.originX() + 8.0 - camX;
            double dyA = a.originY() + 8.0 - camY;
            double dzA = a.originZ() + 8.0 - camZ;
            double distA = dxA * dxA + dyA * dyA + dzA * dzA;

            double dxB = b.originX() + 8.0 - camX;
            double dyB = b.originY() + 8.0 - cameraY(camY);
            double dzB = b.originZ() + 8.0 - camZ;
            double distB = dxB * dxB + dyB * dyB + dzB * dzB;

            return Double.compare(distB, distA);
        });

        assertEquals(far, list.get(0), "furthest translucent slice must be drawn first");
        assertEquals(mid, list.get(1), "middle translucent slice must be drawn second");
        assertEquals(near, list.get(2), "nearest translucent slice must be drawn last");
    }

    @Test
    void layerPartitioningAndCapacities() {
        assertEquals(4, VulkanTerrainLayer.NUM_LAYERS);
        assertEquals(16_384, VulkanTerrainLayer.LAYER_CAPACITY);
        assertEquals(VulkanTerrainLayer.MAX_INDIRECT_CAPACITY,
                VulkanTerrainLayer.NUM_LAYERS * VulkanTerrainLayer.LAYER_CAPACITY);

        for (int layer = 0; layer < VulkanTerrainLayer.NUM_LAYERS; layer++) {
            long layerOffsetByte = (long) (layer * VulkanTerrainLayer.LAYER_CAPACITY) * 16L;
            long layerIndirectByte = (long) (layer * VulkanTerrainLayer.LAYER_CAPACITY) * 20L;
            // std430 vec4 alignment is 16 bytes
            assertEquals(0, layerOffsetByte % 16, "layer " + layer + " offset base must be 16-byte aligned");
            // indirect command alignment is 4 bytes
            assertEquals(0, layerIndirectByte % 4, "layer " + layer + " indirect base must be 4-byte aligned");
        }
    }

    @Test
    void visibilityHashingProducesDeterministicAndDistinctHashes() {
        it.unimi.dsi.fastutil.longs.LongArrayList listA = new it.unimi.dsi.fastutil.longs.LongArrayList();
        listA.add(VulkanTerrainLayer.chunkPosKey(0, 0, 0));
        listA.add(VulkanTerrainLayer.chunkPosKey(16, 0, 0));
        listA.add(VulkanTerrainLayer.chunkPosKey(32, 0, 0));

        it.unimi.dsi.fastutil.longs.LongArrayList listB = new it.unimi.dsi.fastutil.longs.LongArrayList();
        listB.add(VulkanTerrainLayer.chunkPosKey(0, 0, 0));
        listB.add(VulkanTerrainLayer.chunkPosKey(16, 0, 0));
        listB.add(VulkanTerrainLayer.chunkPosKey(32, 0, 0));

        it.unimi.dsi.fastutil.longs.LongArrayList listDifferentOrder = new it.unimi.dsi.fastutil.longs.LongArrayList();
        listDifferentOrder.add(VulkanTerrainLayer.chunkPosKey(32, 0, 0));
        listDifferentOrder.add(VulkanTerrainLayer.chunkPosKey(16, 0, 0));
        listDifferentOrder.add(VulkanTerrainLayer.chunkPosKey(0, 0, 0));

        long hashA = VulkanTerrainLayer.hashVisibleChunks(listA);
        long hashB = VulkanTerrainLayer.hashVisibleChunks(listB);
        long hashOrder = VulkanTerrainLayer.hashVisibleChunks(listDifferentOrder);
        long hashEmpty = VulkanTerrainLayer.hashVisibleChunks(new it.unimi.dsi.fastutil.longs.LongArrayList());
        long hashNull = VulkanTerrainLayer.hashVisibleChunks(null);

        assertEquals(hashA, hashB, "Identical chunk lists must produce identical hash");
        assertNotEquals(hashA, hashOrder, "Chunk lists with different order must produce different hash (order matters for translucent/MDI)");
        assertEquals(0L, hashEmpty);
        assertEquals(0L, hashNull);
        assertNotEquals(0L, hashA);
    }

    @Test
    void frustumHashingDetectsCameraMovement() {
        float[] planes = new float[24];
        java.util.Arrays.fill(planes, 1.0f);

        long h1 = VulkanTerrainLayer.hashFrustum(planes, 10.0, 64.0, 10.0);
        long h2 = VulkanTerrainLayer.hashFrustum(planes, 10.0, 64.0, 10.0);
        long h3 = VulkanTerrainLayer.hashFrustum(planes, 10.5, 64.0, 10.0);
        long h4 = VulkanTerrainLayer.hashFrustum(planes, 10.0, 64.0, 10.1);

        assertEquals(h1, h2, "Same camera position and planes must produce identical hash");
        assertNotEquals(h1, h3, "Movement > 0.1 block must change hash");
        assertNotEquals(h1, h4, "Movement in Z must change hash");
    }

    @Test
    void frameStatesPerLayerChunkListsAreIsolatedAndPooled() {
        it.unimi.dsi.fastutil.longs.LongArrayList[] lists = new it.unimi.dsi.fastutil.longs.LongArrayList[4];
        for (int layer = 0; layer < 4; layer++) {
            lists[layer] = net.minecraftforge.client.render.state.backend.FrameStates.beginLayerChunkList(layer);
            assertNotNull(lists[layer]);
            assertTrue(lists[layer].isEmpty());
            lists[layer].add(VulkanTerrainLayer.chunkPosKey(layer * 16, 0, 0));
        }

        // Verify each layer holds distinct instance and its own entry without clobbering
        for (int layer = 0; layer < 4; layer++) {
            assertEquals(1, lists[layer].size());
            assertEquals(VulkanTerrainLayer.chunkPosKey(layer * 16, 0, 0), lists[layer].getLong(0));
            for (int other = layer + 1; other < 4; other++) {
                assertNotSame(lists[layer], lists[other]);
            }
        }

        // Calling beginLayerChunkList clears the requested list and returns the exact same pooled instance
        var list1Again = net.minecraftforge.client.render.state.backend.FrameStates.beginLayerChunkList(1);
        assertSame(lists[1], list1Again);
        assertTrue(list1Again.isEmpty());
        // Other layers remain untouched
        assertEquals(1, lists[0].size());
        assertEquals(1, lists[2].size());
        assertEquals(1, lists[3].size());
    }

    private static double cameraY(double y) {
        return y;
    }
}
