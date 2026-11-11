package net.minecraftforge.client.render.vulkan;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The data path fed by vanilla's real producer instead of hand-written bytes.
 *
 * <p><b>Why this test exists at all.</b> {@link TerrainMeshBuilderTest} writes its own bytes, so it proves the
 * conversion is self-consistent but not that it reads what the game actually emits. Every assumption the Vulkan
 * path makes - stride, offsets, component types, quad ordering, byte order - is an assumption about
 * {@code DefaultVertexFormats.BLOCK} and {@code BufferBuilder}, and those are the two things a wrong guess would
 * be invisible against. So this test drives the real {@link BufferBuilder} and reads the result back through the
 * same constants the pipeline uses.</p>
 *
 * <p><b>Why {@code BufferBuilder} can run here.</b> It is pure CPU: it packs into a {@code ByteBuffer} and touches
 * no GL. That is what makes the whole terrain path testable without a context, and it is also why the capture hook
 * in {@code ChunkRenderDispatcher} can hand over bytes rather than a buffer object.</p>
 *
 * <p><b>What this does not prove.</b> That the capture hook fires in a live client. That needs the client to boot,
 * which needs the render targets and the state layer - tracked in {@code docs/vulkan-backend-plan.md} §8.4.1.</p>
 */
class VanillaTerrainDataTest {

    /**
     * Builds one quad the way {@code BlockModelRenderer} does: four {@code endVertex()} calls with position,
     * colour, UV and lightmap set for each.
     *
     * <p>The values are chosen to be distinguishable in each field, so an offset that is off by one attribute
     * shows up as a wrong number rather than as a plausible one.</p>
     */
    private static BufferBuilder buildOneQuad() {
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
                    // The colour vanilla bakes: face shading and ambient occlusion, already multiplied in.
                    // Given as exact byte fractions so this test is about offsets and not about how vanilla
                    // rounds a float into an unsigned byte.
                    .color((64 + 32 * i) / 255.0F, 128 / 255.0F, 192 / 255.0F, 1.0F)
                    .tex(0.125F * i, 0.5F)
                    // lightmap(skyLight, blockLight). Chosen so the two components are different numbers: a
                    // swapped read is then visible rather than symmetric.
                    .lightmap(240, 7)
                    .endVertex();
        }
        return builder;
    }

    private static byte[] bytesOf(BufferBuilder builder) {
        ByteBuffer buffer = builder.getByteBuffer();
        byte[] out = new byte[builder.getVertexCount() * TerrainVertexFormat.RAW_STRIDE];
        ByteBuffer view = buffer.duplicate();
        view.position(0);
        view.get(out);
        return out;
    }

    @Test
    void vanillaBlockFormatIsTwentyEightBytes() {
        BufferBuilder builder = buildOneQuad();

        assertEquals(4, builder.getVertexCount(), "one quad is four vertices");
        assertEquals(TerrainVertexFormat.RAW_STRIDE, builder.getVertexFormat().getSize(),
                "the pipeline's stride must be the format's own size, not a guess");
        assertEquals(4 * TerrainVertexFormat.RAW_STRIDE, bytesOf(builder).length);
    }

    @Test
    void offsetsMatchWhatVanillaWrites() {
        BufferBuilder builder = buildOneQuad();
        ByteBuffer buffer = builder.getByteBuffer().duplicate().order(ByteOrder.nativeOrder());

        // Each field read with the type vanilla actually writes, which is also the type the pipeline declares.
        // Reading colour as a float here was the first version of this test, and it produced NaN: the four colour
        // components are bytes, so a float read spans colour and the first half of the UV.
        buffer.position(0 + TerrainVertexFormat.RAW_POSITION_OFFSET);
        assertEquals(0.0F, buffer.getFloat(), 0.0F, "position.x of the first vertex");

        buffer.position(0 + TerrainVertexFormat.RAW_COLOR_OFFSET);
        assertEquals(64, buffer.get() & 0xFF, "colour.r of the first vertex, R8G8B8A8_UNORM");
        assertEquals(128, buffer.get() & 0xFF, "colour.g");
        assertEquals(192, buffer.get() & 0xFF, "colour.b");
        assertEquals(255, buffer.get() & 0xFF, "colour.a");

        buffer.position(0 + TerrainVertexFormat.RAW_UV_OFFSET);
        assertEquals(0.0F, buffer.getFloat(), 0.0F, "u of the first vertex");
        assertEquals(0.5F, buffer.getFloat(), 0.0F, "v of the first vertex");

        buffer.position(0 + TerrainVertexFormat.RAW_LIGHTMAP_OFFSET);
        // Block light first, then sky light: BufferBuilder.lightmap writes blockLight to offset 0 and skyLight to
        // offset 2. A shader that reads this as (sky, block) gets a dark world at noon and a bright one at night,
        // which is the kind of mistake that looks like a lighting bug rather than a format bug.
        assertEquals(7, buffer.getShort() & 0xFFFF, "block light is the first component");
        assertEquals(240, buffer.getShort() & 0xFFFF, "sky light is the second component");
    }

    @Test
    void thirdVertexCarriesItsOwnValues() {
        BufferBuilder builder = buildOneQuad();
        ByteBuffer buffer = builder.getByteBuffer().duplicate().order(ByteOrder.nativeOrder());
        int base = 2 * TerrainVertexFormat.RAW_STRIDE;

        // A quad after the first would be at a different base; here the point is that the stride lands exactly on
        // vertex boundaries, which is what makes any base offset correct.
        buffer.position(base + TerrainVertexFormat.RAW_POSITION_OFFSET);
        assertEquals(1.0F, buffer.getFloat(), 0.0F, "third vertex position.x");
        assertEquals(1.0F, buffer.getFloat(), 0.0F, "third vertex position.y");

        buffer.position(base + TerrainVertexFormat.RAW_COLOR_OFFSET);
        assertEquals(128, buffer.get() & 0xFF, "third vertex colour.r");
    }

    @Test
    void realVanillaBytesSurviveTheQuadToTriangleSplit() {
        BufferBuilder builder = buildOneQuad();
        byte[] captured = bytesOf(builder);

        TerrainMeshBuilder.Mesh mesh = TerrainMeshBuilder.build(captured, 1);

        // The bytes are what vanilla wrote: no conversion, no reordering, no normal invented.
        assertEquals(4 * TerrainVertexFormat.RAW_STRIDE, mesh.vertices().length);
        assertArrayEquals(captured, mesh.vertices(), "vertex bytes pass through verbatim");
        assertEquals(6, mesh.indexCount(), "GL_QUADS becomes two triangles");
        assertTrue(mesh.index32() == false, "four vertices never need 32-bit indices");
    }
}
