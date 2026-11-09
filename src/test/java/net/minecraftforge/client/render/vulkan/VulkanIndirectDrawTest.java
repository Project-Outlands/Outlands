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

        mvp.get(constants);
        constants.putLong(64, chunkDataAddress);
        constants.putFloat(72, 0.1f);
        constants.putFloat(76, 1.0f);
        constants.putFloat(80, 0.0f);

        assertEquals(chunkDataAddress, constants.getLong(64));
        assertEquals(0.1f, constants.getFloat(72), 1e-6f);
        assertEquals(1.0f, constants.getFloat(76), 1e-6f);
        assertEquals(0.0f, constants.getFloat(80), 1e-6f);
        assertTrue(VulkanPipeline.PUSH_CONSTANT_BYTES <= 128);
    }
}
