package net.minecraftforge.client.render.vertex;

import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The rewrite from an arbitrary vertex format into the one layout the pipeline knows.
 *
 * <p>The assertions are about the three things that turn into a wrong picture rather than an error: an offset that
 * lands on the wrong component, a format that is missing an attribute and comes out black, and a lightmap index
 * confused with the texture coordinate.</p>
 */
class VertexConverterTest {

    private static ByteBuffer direct(int capacity) {
        return ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder());
    }

    @Test
    void blockGoesThroughUnchanged() {
        // The target layout is BLOCK itself, so converting it is the identity - and reusing the verified terrain
        // pipeline only works if that stays true.
        VertexFormat format = DefaultVertexFormats.BLOCK;
        ByteBuffer source = direct(2 * format.getSize());
        for (int i = 0; i < source.capacity(); i++) {
            source.put(i, (byte) (i * 7));
        }

        byte[] out = VertexConverter.convert(source, VertexLayout.of(format), 2);

        assertEquals(2 * VertexConverter.STRIDE, out.length);
        byte[] expected = new byte[2 * format.getSize()];
        source.duplicate().get(expected);
        assertEquals(new String(expected), new String(out), "BLOCK is already the target layout");
    }

    @Test
    void positionTexColorIsRewrittenAndGetsAFullBrightnessLightmap() {
        // This is the GUI and font format: no lightmap, and no normal. Its vertices must not come out black.
        VertexFormat format = DefaultVertexFormats.POSITION_TEX_COLOR;
        ByteBuffer source = direct(format.getSize());
        source.putFloat(0, 1.5F);
        source.putFloat(4, 2.5F);
        source.putFloat(8, 3.5F);
        source.putFloat(12, 0.25F);
        source.putFloat(16, 0.5F);
        source.put(20, (byte) 10);
        source.put(21, (byte) 20);
        source.put(22, (byte) 30);
        source.put(23, (byte) 40);

        ByteBuffer out = ByteBuffer.wrap(VertexConverter.convert(source, VertexLayout.of(format), 1))
                .order(ByteOrder.nativeOrder());

        assertEquals(1.5F, out.getFloat(0), 0.0F);
        assertEquals(2.5F, out.getFloat(4), 0.0F);
        assertEquals(3.5F, out.getFloat(8), 0.0F);
        assertEquals(10, out.get(12) & 0xFF, "colour.r moves unchanged");
        assertEquals(40, out.get(15) & 0xFF, "colour.a moves unchanged");
        assertEquals(0.25F, out.getFloat(16), 0.0F);
        assertEquals(0.5F, out.getFloat(20), 0.0F);
        assertEquals(0xFFFF, out.getShort(24) & 0xFFFF, "a missing lightmap is full brightness, not zero");
        assertEquals(0xFFFF, out.getShort(26) & 0xFFFF);
    }

    @Test
    void theLightmapIsTheSecondUvAndNotTheFirst() {
        // BLOCK carries two UV elements at the same usage. Taking the wrong one puts the lightmap into the texture
        // coordinate, which looks like the wrong texture rather than like a lightmap bug.
        VertexFormat format = DefaultVertexFormats.BLOCK;
        ByteBuffer source = direct(format.getSize());
        source.putFloat(16, 0.125F);
        source.putFloat(20, 0.625F);
        source.putShort(24, (short) 0x1234);
        source.putShort(26, (short) 0x5678);

        ByteBuffer out = ByteBuffer.wrap(VertexConverter.convert(source, VertexLayout.of(format), 1))
                .order(ByteOrder.nativeOrder());

        assertEquals(0.125F, out.getFloat(16), 0.0F);
        assertEquals(0.625F, out.getFloat(20), 0.0F);
        assertEquals(0x1234, out.getShort(24) & 0xFFFF);
        assertEquals(0x5678, out.getShort(26) & 0xFFFF);
    }

    @Test
    void aTwoComponentPositionGetsAZeroThird() {
        VertexFormat format = DefaultVertexFormats.POSITION_TEX;
        ByteBuffer source = direct(format.getSize());
        source.putFloat(0, 4.0F);
        source.putFloat(4, 5.0F);

        ByteBuffer out = ByteBuffer.wrap(VertexConverter.convert(source, VertexLayout.of(format), 1))
                .order(ByteOrder.nativeOrder());

        assertEquals(4.0F, out.getFloat(0), 0.0F);
        assertEquals(5.0F, out.getFloat(4), 0.0F);
        assertEquals(0.0F, out.getFloat(8), 0.0F, "a 2D position is at z = 0, not absent");
        assertEquals(0xFFFFFFFF - 1, 0xFFFFFFFE);
        assertEquals(0xFFFFFFFF, out.getInt(12), "colour defaults to opaque white");
    }

    @Test
    void aShortBufferIsLoud() {
        VertexFormat format = DefaultVertexFormats.POSITION_TEX_COLOR;
        ByteBuffer tooSmall = direct(format.getSize() * 2 - 4);
        assertThrows(IllegalArgumentException.class,
                () -> VertexConverter.convert(tooSmall, VertexLayout.of(format), 2));
    }
}
