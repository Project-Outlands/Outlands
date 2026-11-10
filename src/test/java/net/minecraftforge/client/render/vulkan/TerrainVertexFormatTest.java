package net.minecraftforge.client.render.vulkan;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TerrainVertexFormatTest {

    private static BufferBuilder buildTestQuad() {
        BufferBuilder builder = new BufferBuilder(256);
        builder.begin(7, DefaultVertexFormats.BLOCK);

        float[][] positions = {
                {0.0F, 0.0F, 0.0F},
                {1.0F, 0.0F, 0.0F},
                {1.0F, 1.0F, 0.0F},
                {0.0F, 1.0F, 0.0F},
        };
        for (int i = 0; i < positions.length; i++) {
            builder.pos(positions[i][0], positions[i][1], positions[i][2])
                    .color((64 + 32 * i) / 255.0F, 128 / 255.0F, 192 / 255.0F, 1.0F)
                    .tex(0.125F * i, 0.5F)
                    .lightmap(240, 7)
                    .endVertex();
        }
        builder.finishDrawing();
        return builder;
    }

    private static byte[] bytesOf(BufferBuilder builder) {
        ByteBuffer buf = builder.getByteBuffer().duplicate();
        buf.position(0);
        buf.limit(builder.getVertexCount() * TerrainVertexFormat.RAW_STRIDE);
        byte[] copy = new byte[buf.remaining()];
        buf.get(copy);
        return copy;
    }

    @Test
    void compactLayoutMatchesFormatSpecification() {
        BufferBuilder builder = buildTestQuad();
        byte[] raw = bytesOf(builder);
        assertEquals(4 * 28, raw.length, "Raw quad is 112 bytes");

        byte[] compact = TerrainVertexFormat.compactToBytes(raw, 1);
        assertEquals(4 * 16, compact.length, "Compact quad is 64 bytes");
        assertEquals(TerrainVertexFormat.COMPACT_QUAD_SIZE, compact.length);

        ByteBuffer buf = ByteBuffer.wrap(compact).order(ByteOrder.nativeOrder());

        // Vertex 0: pos = (0, 0, 0), color = (64, 128, 192, 255), u = 0.0, v = 0.5, bl = 7, sl = 240
        assertEquals(0, buf.getShort(0), "v0 pos.x");
        assertEquals(0, buf.getShort(2), "v0 pos.y");
        assertEquals(0, buf.getShort(4), "v0 pos.z");
        assertEquals(7, buf.get(6) & 0xFF, "v0 block light at byte 6");
        assertEquals(240, buf.get(7) & 0xFF, "v0 sky light at byte 7");

        // UV: u=0.0 -> 0, v=0.5 -> 32768
        assertEquals(0, buf.getShort(8) & 0xFFFF, "v0 u16");
        assertEquals(Math.round(0.5f * 65535.0f), buf.getShort(10) & 0xFFFF, "v0 v16");

        // Color: RGBA at offset 12..15
        assertEquals(64, buf.get(12) & 0xFF, "v0 color.r");
        assertEquals(128, buf.get(13) & 0xFF, "v0 color.g");
        assertEquals(192, buf.get(14) & 0xFF, "v0 color.b");
        assertEquals(255, buf.get(15) & 0xFF, "v0 color.a");

        // Vertex 2: pos = (1.0, 1.0, 0.0), color = (128, 128, 192, 255), u = 0.25, v = 0.5
        int v2Base = 2 * TerrainVertexFormat.COMPACT_STRIDE;
        short expectedPosScale = (short) Math.round(1.0f * TerrainVertexFormat.POSITION_SCALE);
        assertEquals(expectedPosScale, buf.getShort(v2Base), "v2 pos.x (scaled by 512)");
        assertEquals(expectedPosScale, buf.getShort(v2Base + 2), "v2 pos.y (scaled by 512)");
        assertEquals(0, buf.getShort(v2Base + 4), "v2 pos.z");
        assertEquals(7, buf.get(v2Base + 6) & 0xFF, "v2 block light");
        assertEquals(240, buf.get(v2Base + 7) & 0xFF, "v2 sky light");

        assertEquals(Math.round(0.25f * 65535.0f), buf.getShort(v2Base + 8) & 0xFFFF, "v2 u16");
        assertEquals(Math.round(0.5f * 65535.0f), buf.getShort(v2Base + 10) & 0xFFFF, "v2 v16");

        assertEquals(128, buf.get(v2Base + 12) & 0xFF, "v2 color.r");
    }

    @Test
    void directNativeCompactionMatchesByteArrayCompaction() {
        BufferBuilder builder = buildTestQuad();
        byte[] raw = bytesOf(builder);

        byte[] fromBytes = TerrainVertexFormat.compactToBytes(raw, 1);

        ByteBuffer directBuf = ByteBuffer.allocateDirect(TerrainVertexFormat.COMPACT_QUAD_SIZE)
                .order(ByteOrder.nativeOrder());
        long addr = MemoryUtil.memAddress(directBuf);
        TerrainVertexFormat.compact(raw, 1, addr);

        byte[] fromDirect = new byte[TerrainVertexFormat.COMPACT_QUAD_SIZE];
        directBuf.get(fromDirect);

        assertArrayEquals(fromBytes, fromDirect, "Direct memory compaction must exactly match byte array compaction");
    }

    @Test
    void directByteBufferCompactionMatchesByteArrayCompaction() {
        BufferBuilder builder = buildTestQuad();
        byte[] raw = bytesOf(builder);
        byte[] fromBytes = TerrainVertexFormat.compactToBytes(raw, 1);

        ByteBuffer src = builder.getByteBuffer();
        byte[] fromDirectBuffer = TerrainVertexFormat.compact(src, 1);

        assertArrayEquals(fromBytes, fromDirectBuffer, "ByteBuffer compaction must match byte array compaction");
    }

    @Test
    void shaderUnpackingArithmeticMatchesOriginalValues() {
        BufferBuilder builder = buildTestQuad();
        byte[] raw = bytesOf(builder);
        byte[] compact = TerrainVertexFormat.compactToBytes(raw, 1);
        ByteBuffer buf = ByteBuffer.wrap(compact).order(ByteOrder.nativeOrder());

        // Simulate Vertex 2 shader fetch:
        int v2Base = 2 * TerrainVertexFormat.COMPACT_STRIDE;

        // location 0: ivec4 inPosAndLight (R16G16B16A16_SINT)
        short sx = buf.getShort(v2Base);
        short sy = buf.getShort(v2Base + 2);
        short sz = buf.getShort(v2Base + 4);
        short sw = buf.getShort(v2Base + 6); // w component carries packed light

        // Shader: vec3 localPos = vec3(inPosAndLight.xyz) * (1.0 / 512.0);
        float localX = (float) sx * (1.0f / 512.0f);
        float localY = (float) sy * (1.0f / 512.0f);
        float localZ = (float) sz * (1.0f / 512.0f);
        assertEquals(1.0f, localX, 0.002f);
        assertEquals(1.0f, localY, 0.002f);
        assertEquals(0.0f, localZ, 0.002f);

        // Shader: int lightData = inPosAndLight.w;
        // outLightmap = vec2(float(lightData & 0xFF), float((lightData >> 8) & 0xFF));
        int lightData = (int) sw; // sign extension simulation
        float outBlockLight = (float) (lightData & 0xFF);
        float outSkyLight = (float) ((lightData >> 8) & 0xFF);
        assertEquals(7.0f, outBlockLight, 0.0f);
        assertEquals(240.0f, outSkyLight, 0.0f);

        // location 1: vec2 inUv (R16G16_UNORM) - hardware decodes uint16 / 65535.0f
        int u16 = buf.getShort(v2Base + 8) & 0xFFFF;
        int v16 = buf.getShort(v2Base + 10) & 0xFFFF;
        float hwU = (float) u16 / 65535.0f;
        float hwV = (float) v16 / 65535.0f;
        assertEquals(0.25f, hwU, 0.0001f);
        assertEquals(0.5f, hwV, 0.0001f);
    }
}
