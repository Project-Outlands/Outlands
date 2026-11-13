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

    /**
     * Exactly what {@code RenderHelper.enableStandardItemLighting} installs: model ambient 0.4, two white lights at
     * 0.6 diffuse, and colour material on so the vertex colour is the material.
     *
     * <p>The expected values in the lit tests below are the numbers this model produces, which are the same numbers
     * the hard-coded 0.4 + 0.6*d0 + 0.6*d1 produced - the point of the model is that the numbers now come from the
     * game's own state rather than from a constant that only matches it by coincidence.</p>
     */
    static LightingModel standardItemLighting() {
        float[] ambient = new float[LightingModel.LIGHTS * 3];
        float[] diffuse = new float[LightingModel.LIGHTS * 3];
        diffuse[0] = diffuse[1] = diffuse[2] = 0.6F;
        diffuse[3] = diffuse[4] = diffuse[5] = 0.6F;
        float[] direction = new float[LightingModel.LIGHTS * 3];
        direction[0] = 0.1617F;
        direction[1] = 0.8085F;
        direction[2] = -0.5659F;
        direction[3] = -0.1617F;
        direction[4] = 0.8085F;
        direction[5] = 0.5659F;
        boolean[] enabled = new boolean[LightingModel.LIGHTS];
        enabled[0] = true;
        enabled[1] = true;
        return new LightingModel(new float[] {0.4F, 0.4F, 0.4F}, ambient, diffuse, direction, enabled, true,
                LightingModel.AMBIENT_AND_DIFFUSE);
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

    @Test
    void convertInPlaceMatchesConvert() {
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

        byte[] regular = VertexConverter.convert(source, VertexLayout.of(format), 1, 0x11223344, new float[] {100.0F, 200.0F});

        int offset = 64;
        ByteBuffer directTarget = ByteBuffer.allocateDirect(offset + regular.length).order(ByteOrder.nativeOrder());
        VertexConverter.convert(source, VertexLayout.of(format), 1, 0x11223344, new float[] {100.0F, 200.0F}, directTarget, offset);

        byte[] inPlace = new byte[regular.length];
        directTarget.position(offset);
        directTarget.get(inPlace);

        org.junit.jupiter.api.Assertions.assertArrayEquals(regular, inPlace);

        byte[] arraySource = new byte[format.getSize()];
        source.position(0);
        source.get(arraySource);

        ByteBuffer arrayDirectTarget = ByteBuffer.allocateDirect(offset + regular.length).order(ByteOrder.nativeOrder());
        VertexConverter.convert(arraySource, VertexLayout.of(format), 1, 0x11223344, new float[] {100.0F, 200.0F},
                false, null, null, null, arrayDirectTarget, offset);

        byte[] fromArray = new byte[regular.length];
        arrayDirectTarget.position(offset);
        arrayDirectTarget.get(fromArray);

        org.junit.jupiter.api.Assertions.assertArrayEquals(regular, fromArray, "ByteArray convert must match ByteBuffer convert");
    }

    @Test
    void fallbackColorComponentsAreInRgbaOrder() {
        VertexFormat format = DefaultVertexFormats.POSITION_TEX;
        ByteBuffer source = direct(format.getSize());
        source.putFloat(0, 1.0F);
        source.putFloat(4, 2.0F);
        source.putFloat(8, 3.0F);
        source.putFloat(12, 0.5F);
        source.putFloat(16, 0.5F);

        int fallbackColor = (0xAA << 24) | (0x11 << 16) | (0x22 << 8) | 0x33;
        ByteBuffer out = ByteBuffer.wrap(VertexConverter.convert(source, VertexLayout.of(format), 1, fallbackColor, null))
                .order(ByteOrder.nativeOrder());

        assertEquals(0x11, out.get(12) & 0xFF, "red matches fallback color");
        assertEquals(0x22, out.get(13) & 0xFF, "green matches fallback color");
        assertEquals(0x33, out.get(14) & 0xFF, "blue matches fallback color");
        assertEquals(0xAA, out.get(15) & 0xFF, "alpha matches fallback color");
    }

    @Test
    void normalAttributeAppliesDiffuseLightingOnItemFormat() {
        VertexFormat format = DefaultVertexFormats.ITEM;
        int stride = format.getSize();
        ByteBuffer source = direct(stride * 4);

        // ITEM layout: POSITION(12), COLOR(4), TEX(8), NORMAL(3), PADDING(1) -> total 28 bytes.
        // Vertex 0: Y+ (top face), normal (0, 127, 0) -> diffuse factor 1.0
        source.put(12, (byte) 200); // R
        source.put(13, (byte) 200); // G
        source.put(14, (byte) 200); // B
        source.put(15, (byte) 255); // A
        source.put(24, (byte) 0);
        source.put(25, (byte) 127);
        source.put(26, (byte) 0);

        // Vertex 1: Y- (bottom face), normal (0, -127, 0) -> diffuse factor 0.5
        int b1 = stride;
        source.put(b1 + 12, (byte) 200);
        source.put(b1 + 13, (byte) 200);
        source.put(b1 + 14, (byte) 200);
        source.put(b1 + 15, (byte) 255);
        source.put(b1 + 24, (byte) 0);
        source.put(b1 + 25, (byte) -127);
        source.put(b1 + 26, (byte) 0);

        // Vertex 2: Z+ (north/south), normal (0, 0, 127) -> diffuse factor 0.8
        int b2 = stride * 2;
        source.put(b2 + 12, (byte) 200);
        source.put(b2 + 13, (byte) 200);
        source.put(b2 + 14, (byte) 200);
        source.put(b2 + 15, (byte) 255);
        source.put(b2 + 24, (byte) 0);
        source.put(b2 + 25, (byte) 0);
        source.put(b2 + 26, (byte) 127);

        // Vertex 3: X+ (east/west), normal (127, 0, 0) -> diffuse factor 0.6
        int b3 = stride * 3;
        source.put(b3 + 12, (byte) 200);
        source.put(b3 + 13, (byte) 200);
        source.put(b3 + 14, (byte) 200);
        source.put(b3 + 15, (byte) 255);
        source.put(b3 + 24, (byte) 127);
        source.put(b3 + 25, (byte) 0);
        source.put(b3 + 26, (byte) 0);

        // When lighting is disabled (e.g. 2D items in GUI), normals are ignored and colors stay untouched.
        ByteBuffer outNoLighting = ByteBuffer.wrap(VertexConverter.convert(source, VertexLayout.of(format), 4))
                .order(ByteOrder.nativeOrder());
        int outStride = VertexConverter.STRIDE;
        for (int i = 0; i < 4; i++) {
            assertEquals(200, outNoLighting.get(outStride * i + 12) & 0xFF, "unlit vertex " + i + " stays unattenuated");
            assertEquals(255, outNoLighting.get(outStride * i + 15) & 0xFF, "alpha is preserved");
        }

        // When lighting is enabled, diffuse lighting modulates vertex RGB based on normalMatrix and light vectors.
        float[] identity3x3 = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        LightingModel standardItemLighting = standardItemLighting();
        ByteBuffer out = ByteBuffer.wrap(VertexConverter.convert(source, VertexLayout.of(format), 4,
                        0xFFFFFFFF, null, true, identity3x3, standardItemLighting))
                .order(ByteOrder.nativeOrder());

        // Vertex 0: top face factor 1.0 -> 200
        assertEquals(200, out.get(12) & 0xFF, "top face stays unattenuated");
        assertEquals(255, out.get(15) & 0xFF, "alpha is preserved");

        // Vertex 1: bottom face factor 0.4 -> 80
        assertEquals(80, out.get(outStride + 12) & 0xFF, "bottom face factor 0.4");
        assertEquals(255, out.get(outStride + 15) & 0xFF, "alpha is preserved");

        // Vertex 2: Z+ (south) face factor 0.740 -> 148
        assertEquals(148, out.get(outStride * 2 + 12) & 0xFF, "Z+ face factor 0.740");

        // Vertex 3: X+ (east) face factor 0.497 -> 99
        assertEquals(99, out.get(outStride * 3 + 12) & 0xFF, "X+ face factor 0.497");
    }

    @Test
    void normalAttributeAppliesDiffuseLightingToFallbackColorOnOldModelFormat() {
        VertexFormat format = DefaultVertexFormats.OLDMODEL_POSITION_TEX_NORMAL;
        int stride = format.getSize();
        ByteBuffer source = direct(stride * 2);

        // OLDMODEL layout: POSITION(12), TEX(8), NORMAL(3), PADDING(1) -> total 24 bytes (no colour element).
        // Vertex 0: top face (0, 127, 0)
        source.put(20, (byte) 0);
        source.put(21, (byte) 127);
        source.put(22, (byte) 0);

        // Vertex 1: bottom face (0, -127, 0)
        source.put(stride + 20, (byte) 0);
        source.put(stride + 21, (byte) -127);
        source.put(stride + 22, (byte) 0);

        int fallbackColor = (0xFF << 24) | (200 << 16) | (200 << 8) | 200;
        float[] identity3x3 = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        ByteBuffer out = ByteBuffer.wrap(VertexConverter.convert(source, VertexLayout.of(format), 2, fallbackColor, null,
                        true, identity3x3, standardItemLighting()))
                .order(ByteOrder.nativeOrder());

        int outStride = VertexConverter.STRIDE;
        assertEquals(200, out.get(12) & 0xFF, "top face factor 1.0 on fallback colour");
        assertEquals(80, out.get(outStride + 12) & 0xFF, "bottom face factor 0.4 on fallback colour");
        assertEquals(255, out.get(15) & 0xFF);
        assertEquals(255, out.get(outStride + 15) & 0xFF);
    }

    @Test
    void textureMatrixTransformsUvCoordinatesForEnchantmentGlint() {
        VertexFormat format = DefaultVertexFormats.ITEM;
        int stride = format.getSize();
        ByteBuffer source = direct(stride * 2);

        // ITEM layout: POSITION(12), COLOR(4), TEX(8), NORMAL(3), PADDING(1) -> 28 bytes
        // Vertex 0: UV = (0.25, 0.5)
        source.putFloat(16, 0.25F);
        source.putFloat(20, 0.5F);

        // Vertex 1: UV = (0.1, 0.2)
        source.putFloat(stride + 16, 0.1F);
        source.putFloat(stride + 20, 0.2F);

        // Create texture matrix: scale(8.0, 8.0, 8.0) -> translate(0.5, 0.0, 0.0)
        org.joml.Matrix4f m = new org.joml.Matrix4f();
        m.scale(8.0F, 8.0F, 8.0F);
        m.translate(0.5F, 0.0F, 0.0F);
        float[] texMat = new float[16];
        m.get(texMat);

        ByteBuffer out = ByteBuffer.wrap(VertexConverter.convert(source, VertexLayout.of(format), 2,
                        0xFFFFFFFF, null, false, null, null, texMat))
                .order(ByteOrder.nativeOrder());

        int outStride = VertexConverter.STRIDE;
        // Vertex 0: u' = 0.25 * 8.0 + 4.0 = 6.0, v' = 0.5 * 8.0 = 4.0
        assertEquals(6.0F, out.getFloat(16), 0.001F);
        assertEquals(4.0F, out.getFloat(20), 0.001F);

        // Vertex 1: u' = 0.1 * 8.0 + 4.0 = 4.8, v' = 0.2 * 8.0 = 1.6
        assertEquals(4.8F, out.getFloat(outStride + 16), 0.001F);
        assertEquals(1.6F, out.getFloat(outStride + 20), 0.001F);
    }

    @Test
    void aTintedLightTintsTheVertexPerChannel() {
        // A red light and colour material on: the shaded colour is the vertex colour times the light's own colour, per
        // channel. A scalar factor could not express this - it would dim all three channels equally.
        float[] ambient = new float[LightingModel.LIGHTS * 3];
        float[] diffuse = new float[LightingModel.LIGHTS * 3];
        diffuse[0] = 1.0F;      // red only
        float[] direction = new float[LightingModel.LIGHTS * 3];
        direction[2] = 1.0F;    // straight at the +Z face
        boolean[] enabled = new boolean[LightingModel.LIGHTS];
        enabled[0] = true;
        LightingModel red = new LightingModel(new float[3], ambient, diffuse, direction, enabled, true,
                LightingModel.AMBIENT_AND_DIFFUSE);

        VertexFormat format = DefaultVertexFormats.ITEM;
        ByteBuffer source = direct(format.getSize());
        source.put(12, (byte) 200);
        source.put(13, (byte) 200);
        source.put(14, (byte) 200);
        source.put(15, (byte) 255);
        source.put(26, (byte) 127);   // normal +Z

        ByteBuffer out = ByteBuffer.wrap(VertexConverter.convert(source, VertexLayout.of(format), 1,
                        0xFFFFFFFF, null, true, new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1}, red))
                .order(ByteOrder.nativeOrder());

        assertEquals(200, out.get(12) & 0xFF, "red channel is fully lit");
        assertEquals(0, out.get(13) & 0xFF, "green has no light");
        assertEquals(0, out.get(14) & 0xFF, "blue has no light");
        assertEquals(255, out.get(15) & 0xFF, "alpha is preserved");
    }

    @Test
    void withoutColourMaterialTheVertexColourIsNotTheMaterial() {
        // GL's default material is ambient 0.2 / diffuse 0.8 and the vertex colour takes no part, so two vertices
        // that differ only in colour come out the same. That is what a mod gets for drawing lit geometry without
        // enabling colour material, and it is the case the hard-coded model could not represent at all.
        VertexFormat format = DefaultVertexFormats.ITEM;
        ByteBuffer source = direct(format.getSize() * 2);
        for (int i = 0; i < 2; i++) {
            int base = i * format.getSize();
            source.put(base + 12, (byte) (i == 0 ? 20 : 200));
            source.put(base + 13, (byte) (i == 0 ? 20 : 200));
            source.put(base + 14, (byte) (i == 0 ? 20 : 200));
            source.put(base + 15, (byte) 255);
            source.put(base + 25, (byte) 127);   // normal +Y, which neither light points at
        }

        LightingModel noColourMaterial = new LightingModel(new float[] {0.4F, 0.4F, 0.4F},
                new float[LightingModel.LIGHTS * 3], new float[LightingModel.LIGHTS * 3],
                new float[LightingModel.LIGHTS * 3], new boolean[LightingModel.LIGHTS], false,
                LightingModel.AMBIENT_AND_DIFFUSE);

        ByteBuffer out = ByteBuffer.wrap(VertexConverter.convert(source, VertexLayout.of(format), 2,
                        0xFFFFFFFF, null, true, new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1}, noColourMaterial))
                .order(ByteOrder.nativeOrder());

        // 0.2 * 0.4 = 0.08, which is 20 on the byte scale, for both vertices.
        int stride = VertexConverter.STRIDE;
        assertEquals(20, out.get(12) & 0xFF);
        assertEquals(out.get(12) & 0xFF, out.get(stride + 12) & 0xFF,
                "the vertex colour takes no part when colour material is off");
    }

}
