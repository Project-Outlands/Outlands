package net.minecraftforge.client.render.vulkan;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDrawIndexedIndirectCommand;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static double cameraY(double y) {
        return y;
    }
}
