package net.minecraftforge.client.render.vertex;

import net.minecraft.client.renderer.vertex.VertexFormatElement;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Rewrites any {@link VertexLayout} into the one layout the pipeline already knows.
 *
 * <p><b>Why normalise instead of building a pipeline per layout.</b> Vanilla uses around ten vertex formats, and a
 * pipeline per format would need a shader per format too - the shader has to know which attribute carries the
 * position. Normalising to a single 28-byte layout means one pipeline, one shader, and an arbitrary number of input
 * formats. The cost is a copy of the vertex bytes, which the GL path also paid: {@code glDrawArrays} from client
 * memory copies them into the driver on every draw.</p>
 *
 * <p><b>The target layout is {@code DefaultVertexFormats.BLOCK}.</b> Position, colour, texture UV and lightmap UV:
 * the format vanilla's terrain already produces and the one the terrain shader is written against. Reusing it means
 * the streamed draws go through code that has already been verified on screen.</p>
 *
 * <p><b>What happens to attributes the target has no room for.</b> Normals are dropped, because the target format
 * has none and vanilla's directional shading is already folded into the vertex colour. Attributes that are missing
 * entirely get a neutral value: white for colour, zero for UV, and full brightness for the lightmap, so a format
 * without one is not rendered black.</p>
 */
public final class VertexConverter {

    /** Bytes per output vertex: the target layout's stride. */
    public static final int STRIDE = 28;

    private static final int POSITION_OFFSET = 0;
    private static final int COLOR_OFFSET = 12;
    private static final int UV_OFFSET = 16;
    private static final int LIGHTMAP_OFFSET = 24;

    /** Full lightmap: both components at their maximum, which is what an unlit-input vertex should look like. */
    private static final int FULL_LIGHTMAP = 0xFFFF;

    private static final VarHandle FLOAT_VIEW =
            MethodHandles.byteArrayViewVarHandle(float[].class, ByteOrder.nativeOrder());
    private static final VarHandle SHORT_VIEW =
            MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.nativeOrder());

    private VertexConverter() {}

    /**
     * Converts {@code vertexCount} vertices from {@code source}'s layout into the target layout.
     *
     * @param source      bytes in {@code layout}'s layout, starting at position 0
     * @param layout      what those bytes are
     * @param vertexCount how many vertices to convert
     * @return {@code vertexCount * }{@link #STRIDE} bytes
     */
    public static byte[] convert(ByteBuffer source, VertexLayout layout, int vertexCount) {
        return convert(source, layout, vertexCount, 0xFFFFFFFF, null);
    }

    /**
     * Converts, using {@code fallbackColor} for vertices whose format carries no colour.
     *
     * <p><b>Why a fallback colour and not white.</b> Vanilla colours a whole draw with
     * {@code GlStateManager.color(...)} and then emits vertices without a colour attribute - {@code FontRenderer} does
     * exactly that, for the glyphs and for their shadow. Substituting white made every string render at full
     * brightness with a white shadow instead of a dark one, which reads as washed-out text and was the first thing
     * noticed on the language screen.</p>
     *
     * @param fallbackColor {@code 0xAARRGGBB} used when the layout has no colour element
     * @param fallbackLightmap {@code u, v} in 0..1 used when the layout has no lightmap element, or {@code null} for
     *                         full brightness
     */
    /**
     * Converts vertices in-place into an existing {@code target} buffer at {@code targetOffset}.
     */
    public static void convert(ByteBuffer source, VertexLayout layout, int vertexCount, int fallbackColor,
                               float[] fallbackLightmap, ByteBuffer target, int targetOffset) {
        convert(source, layout, vertexCount, fallbackColor, fallbackLightmap, false, null, null, null, target, targetOffset);
    }

    /**
     * Converts vertices in-place into an existing {@code target} buffer at {@code targetOffset}, optionally applying
     * fixed-function diffuse lighting in eye space.
     */
    public static void convert(ByteBuffer source, VertexLayout layout, int vertexCount, int fallbackColor,
                               float[] fallbackLightmap, boolean lightingEnabled, float[] normalMatrix,
                               float[] light0, float[] light1, ByteBuffer target, int targetOffset) {
        convert(source, layout, vertexCount, fallbackColor, fallbackLightmap, lightingEnabled, normalMatrix,
                light0, light1, null, target, targetOffset);
    }

    /**
     * Converts vertices directly from a byte array in-place into an existing {@code target} buffer at {@code targetOffset}.
     */
    public static void convert(byte[] source, VertexLayout layout, int vertexCount, int fallbackColor,
                               float[] fallbackLightmap, boolean lightingEnabled, float[] normalMatrix,
                               float[] light0, float[] light1, float[] textureMatrix,
                               ByteBuffer target, int targetOffset) {
        convert(source, 0, layout, vertexCount, fallbackColor, fallbackLightmap, lightingEnabled,
                normalMatrix, light0, light1, textureMatrix, target, targetOffset);
    }

    /**
     * Converts vertices directly from a byte array starting at {@code sourceOffset} in-place into an existing
     * {@code target} buffer at {@code targetOffset}.
     */
    public static void convert(byte[] source, int sourceOffset, VertexLayout layout, int vertexCount, int fallbackColor,
                               float[] fallbackLightmap, boolean lightingEnabled, float[] normalMatrix,
                               float[] light0, float[] light1, float[] textureMatrix,
                               ByteBuffer target, int targetOffset) {
        int inStride = layout.stride();
        int required = vertexCount * inStride;
        if (source.length - sourceOffset < required) {
            throw new IllegalArgumentException("layout needs " + required + " bytes for " + vertexCount
                    + " vertices at stride " + inStride + ", the array has " + (source.length - sourceOffset));
        }

        VertexLayout.Attribute[] attributes = layout.attributesArray();
        int attrCount = attributes.length;
        VertexLayout.Attribute normalAttr = null;
        VertexLayout.Attribute colorAttr = null;
        for (int i = 0; i < attrCount; i++) {
            VertexLayout.Attribute attr = attributes[i];
            if (attr.usage() == VertexFormatElement.EnumUsage.NORMAL) {
                normalAttr = attr;
            } else if (attr.usage() == VertexFormatElement.EnumUsage.COLOR) {
                colorAttr = attr;
            }
        }
        int fallbackLight = FULL_LIGHTMAP | (FULL_LIGHTMAP << 16);
        if (fallbackLightmap != null) {
            int block = Math.round(Math.max(0.0F, Math.min(240.0F, fallbackLightmap[0])));
            int sky = Math.round(Math.max(0.0F, Math.min(240.0F, fallbackLightmap[1])));
            fallbackLight = (block & 0xFFFF) | ((sky & 0xFFFF) << 16);
        }

        int fbR = (fallbackColor >>> 16) & 0xFF;
        int fbG = (fallbackColor >>> 8) & 0xFF;
        int fbB = fallbackColor & 0xFF;
        int fbA = (fallbackColor >>> 24) & 0xFF;

        float lastNx = Float.NaN, lastNy = Float.NaN, lastNz = Float.NaN;
        float cachedFactor = 1.0F;

        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int base = targetOffset + vertex * STRIDE;
            int sourceBase = sourceOffset + vertex * inStride;

            target.putFloat(base + UV_OFFSET, 0.0F);
            target.putFloat(base + UV_OFFSET + 4, 0.0F);
            target.putInt(base + LIGHTMAP_OFFSET, fallbackLight);

            for (int i = 0; i < attrCount; i++) {
                VertexLayout.Attribute attribute = attributes[i];
                int at = sourceBase + attribute.offset();
                switch (attribute.usage()) {
                    case POSITION -> copyPosition(source, at, target, base, attribute);
                    case UV -> copyUv(source, at, target, base, attribute, textureMatrix);
                    default -> {
                    }
                }
            }

            int r = fbR, g = fbG, b = fbB, a = fbA;
            if (colorAttr != null) {
                int at = sourceBase + colorAttr.offset();
                if (colorAttr.type() == VertexFormatElement.EnumType.UBYTE && colorAttr.count() == 4) {
                    r = source[at] & 0xFF;
                    g = source[at + 1] & 0xFF;
                    b = source[at + 2] & 0xFF;
                    a = source[at + 3] & 0xFF;
                } else if (colorAttr.type() == VertexFormatElement.EnumType.FLOAT) {
                    r = toByte((float) FLOAT_VIEW.get(source, at)) & 0xFF;
                    g = toByte((float) FLOAT_VIEW.get(source, at + 4)) & 0xFF;
                    b = toByte((float) FLOAT_VIEW.get(source, at + 8)) & 0xFF;
                    a = colorAttr.count() >= 4 ? (toByte((float) FLOAT_VIEW.get(source, at + 12)) & 0xFF) : 0xFF;
                }
            }

            if (normalAttr != null && lightingEnabled && normalMatrix != null && light0 != null && light1 != null) {
                int at = sourceBase + normalAttr.offset();
                float nx, ny, nz;
                if (normalAttr.type() == VertexFormatElement.EnumType.BYTE
                        || normalAttr.type() == VertexFormatElement.EnumType.UBYTE) {
                    nx = source[at];
                    ny = source[at + 1];
                    nz = source[at + 2];
                } else if (normalAttr.type() == VertexFormatElement.EnumType.FLOAT) {
                    nx = (float) FLOAT_VIEW.get(source, at);
                    ny = (float) FLOAT_VIEW.get(source, at + 4);
                    nz = (float) FLOAT_VIEW.get(source, at + 8);
                } else {
                    nx = 0.0F; ny = 0.0F; nz = 0.0F;
                }

                if (nx != lastNx || ny != lastNy || nz != lastNz) {
                    lastNx = nx;
                    lastNy = ny;
                    lastNz = nz;
                    cachedFactor = computeNormalLightingFactor(nx, ny, nz, normalAttr.type(), normalMatrix, light0, light1);
                }

                if (cachedFactor < 0.9999F) {
                    r = Math.round(r * cachedFactor);
                    g = Math.round(g * cachedFactor);
                    b = Math.round(b * cachedFactor);
                }
            }

            target.putInt(base + COLOR_OFFSET, (a << 24) | (b << 16) | (g << 8) | r);
        }
    }

    /**
     * Converts vertices in-place into an existing {@code target} buffer at {@code targetOffset}, optionally applying
     * fixed-function diffuse lighting in eye space and 2D texture coordinate transformation.
     */
    public static void convert(ByteBuffer source, VertexLayout layout, int vertexCount, int fallbackColor,
                               float[] fallbackLightmap, boolean lightingEnabled, float[] normalMatrix,
                               float[] light0, float[] light1, float[] textureMatrix,
                               ByteBuffer target, int targetOffset) {
        if (source.hasArray()) {
            convert(source.array(), source.arrayOffset() + source.position(), layout, vertexCount,
                    fallbackColor, fallbackLightmap, lightingEnabled, normalMatrix, light0, light1,
                    textureMatrix, target, targetOffset);
            return;
        }
        int inStride = layout.stride();
        int required = vertexCount * inStride;
        if (source.limit() < required) {
            throw new IllegalArgumentException("layout needs " + required + " bytes for " + vertexCount
                    + " vertices at stride " + inStride + ", the buffer has " + source.limit());
        }

        ByteBuffer in = source.duplicate().order(ByteOrder.nativeOrder());
        VertexLayout.Attribute[] attributes = layout.attributesArray();
        int attrCount = attributes.length;
        VertexLayout.Attribute normalAttr = null;
        VertexLayout.Attribute colorAttr = null;
        for (int i = 0; i < attrCount; i++) {
            VertexLayout.Attribute attr = attributes[i];
            if (attr.usage() == VertexFormatElement.EnumUsage.NORMAL) {
                normalAttr = attr;
            } else if (attr.usage() == VertexFormatElement.EnumUsage.COLOR) {
                colorAttr = attr;
            }
        }
        // Vanilla's constant lightmap coordinate, in the same unsigned-short encoding the attribute uses (0..240
        // for block light in low 16 bits, sky light in high 16 bits). A format without one therefore gets the
        // brightness the game asked for rather than a made-up full bright: that difference is a mob in a cave looking lit.
        int fallbackLight = FULL_LIGHTMAP | (FULL_LIGHTMAP << 16);
        if (fallbackLightmap != null) {
            int block = Math.round(Math.max(0.0F, Math.min(240.0F, fallbackLightmap[0])));
            int sky = Math.round(Math.max(0.0F, Math.min(240.0F, fallbackLightmap[1])));
            fallbackLight = (block & 0xFFFF) | ((sky & 0xFFFF) << 16);
        }

        // Fallback colour packed into native byte order (R8G8B8A8).
        // fallbackColor arrives as 0xAARRGGBB.
        int fbR = (fallbackColor >>> 16) & 0xFF;
        int fbG = (fallbackColor >>> 8) & 0xFF;
        int fbB = fallbackColor & 0xFF;
        int fbA = (fallbackColor >>> 24) & 0xFF;

        float lastNx = Float.NaN, lastNy = Float.NaN, lastNz = Float.NaN;
        float cachedFactor = 1.0F;

        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int base = targetOffset + vertex * STRIDE;
            int sourceBase = vertex * inStride;

            // Neutrals first, so a format that lacks an attribute still produces a sane vertex.
            target.putFloat(base + UV_OFFSET, 0.0F);
            target.putFloat(base + UV_OFFSET + 4, 0.0F);
            target.putInt(base + LIGHTMAP_OFFSET, fallbackLight);

            for (int i = 0; i < attrCount; i++) {
                VertexLayout.Attribute attribute = attributes[i];
                int at = sourceBase + attribute.offset();
                switch (attribute.usage()) {
                    case POSITION -> copyPosition(in, at, target, base, attribute);
                    case UV -> copyUv(in, at, target, base, attribute, textureMatrix);
                    default -> {
                        // NORMAL is handled below after colour is populated; GENERIC/PADDING skipped.
                    }
                }
            }

            int r = fbR, g = fbG, b = fbB, a = fbA;
            if (colorAttr != null) {
                int at = sourceBase + colorAttr.offset();
                if (colorAttr.type() == VertexFormatElement.EnumType.UBYTE && colorAttr.count() == 4) {
                    r = in.get(at) & 0xFF;
                    g = in.get(at + 1) & 0xFF;
                    b = in.get(at + 2) & 0xFF;
                    a = in.get(at + 3) & 0xFF;
                } else if (colorAttr.type() == VertexFormatElement.EnumType.FLOAT) {
                    r = toByte(in.getFloat(at)) & 0xFF;
                    g = toByte(in.getFloat(at + 4)) & 0xFF;
                    b = toByte(in.getFloat(at + 8)) & 0xFF;
                    a = colorAttr.count() >= 4 ? (toByte(in.getFloat(at + 12)) & 0xFF) : 0xFF;
                }
            }

            if (normalAttr != null && lightingEnabled && normalMatrix != null && light0 != null && light1 != null) {
                int at = sourceBase + normalAttr.offset();
                float nx, ny, nz;
                if (normalAttr.type() == VertexFormatElement.EnumType.BYTE
                        || normalAttr.type() == VertexFormatElement.EnumType.UBYTE) {
                    nx = in.get(at);
                    ny = in.get(at + 1);
                    nz = in.get(at + 2);
                } else if (normalAttr.type() == VertexFormatElement.EnumType.FLOAT) {
                    nx = in.getFloat(at);
                    ny = in.getFloat(at + 4);
                    nz = in.getFloat(at + 8);
                } else {
                    nx = 0.0F; ny = 0.0F; nz = 0.0F;
                }

                if (nx != lastNx || ny != lastNy || nz != lastNz) {
                    lastNx = nx;
                    lastNy = ny;
                    lastNz = nz;
                    cachedFactor = computeNormalLightingFactor(nx, ny, nz, normalAttr.type(), normalMatrix, light0, light1);
                }

                if (cachedFactor < 0.9999F) {
                    r = Math.round(r * cachedFactor);
                    g = Math.round(g * cachedFactor);
                    b = Math.round(b * cachedFactor);
                }
            }

            target.putInt(base + COLOR_OFFSET, (a << 24) | (b << 16) | (g << 8) | r);
        }
    }

    /**
     * Converts vertices, supplying a fallback colour and lightmap for formats that do not carry them.
     *
     * @param fallbackColor {@code 0xAARRGGBB} used when the layout has no colour element
     * @param fallbackLightmap {@code u, v} in 0..1 used when the layout has no lightmap element, or {@code null} for
     *                         full brightness
     */
    public static byte[] convert(ByteBuffer source, VertexLayout layout, int vertexCount, int fallbackColor,
                                 float[] fallbackLightmap) {
        return convert(source, layout, vertexCount, fallbackColor, fallbackLightmap, false, null, null, null);
    }

    public static byte[] convert(ByteBuffer source, VertexLayout layout, int vertexCount, int fallbackColor,
                                 float[] fallbackLightmap, boolean lightingEnabled, float[] normalMatrix,
                                 float[] light0, float[] light1) {
        return convert(source, layout, vertexCount, fallbackColor, fallbackLightmap, lightingEnabled, normalMatrix,
                light0, light1, null);
    }

    public static byte[] convert(ByteBuffer source, VertexLayout layout, int vertexCount, int fallbackColor,
                                 float[] fallbackLightmap, boolean lightingEnabled, float[] normalMatrix,
                                 float[] light0, float[] light1, float[] textureMatrix) {
        byte[] out = new byte[vertexCount * STRIDE];
        ByteBuffer target = ByteBuffer.wrap(out).order(ByteOrder.nativeOrder());
        convert(source, layout, vertexCount, fallbackColor, fallbackLightmap, lightingEnabled, normalMatrix, light0, light1, textureMatrix, target, 0);
        return out;
    }

    private static void copyPosition(byte[] source, int at, ByteBuffer out, int base,
            VertexLayout.Attribute attribute) {
        if (attribute.type() != VertexFormatElement.EnumType.FLOAT) {
            throw new UnsupportedOperationException("position of type " + attribute.type()
                    + " is not handled: vanilla only uses FLOAT positions");
        }
        out.putFloat(base + POSITION_OFFSET, (float) FLOAT_VIEW.get(source, at));
        out.putFloat(base + POSITION_OFFSET + 4, (float) FLOAT_VIEW.get(source, at + 4));
        // A two-component position exists (GUI points); its third coordinate is zero rather than absent.
        out.putFloat(base + POSITION_OFFSET + 8, attribute.count() >= 3 ? (float) FLOAT_VIEW.get(source, at + 8) : 0.0F);
    }

    private static void copyPosition(ByteBuffer in, int at, ByteBuffer out, int base,
            VertexLayout.Attribute attribute) {
        if (attribute.type() != VertexFormatElement.EnumType.FLOAT) {
            throw new UnsupportedOperationException("position of type " + attribute.type()
                    + " is not handled: vanilla only uses FLOAT positions");
        }
        out.putFloat(base + POSITION_OFFSET, in.getFloat(at));
        out.putFloat(base + POSITION_OFFSET + 4, in.getFloat(at + 4));
        // A two-component position exists (GUI points); its third coordinate is zero rather than absent.
        out.putFloat(base + POSITION_OFFSET + 8, attribute.count() >= 3 ? in.getFloat(at + 8) : 0.0F);
    }

    private static void copyUv(byte[] source, int at, ByteBuffer out, int base, VertexLayout.Attribute attribute,
                               float[] textureMatrix) {
        if (attribute.uvIndex() == 1) {
            // The second texture coordinate is the lightmap: two unsigned shorts in the target.
            if (attribute.type() != VertexFormatElement.EnumType.SHORT
                    && attribute.type() != VertexFormatElement.EnumType.USHORT) {
                throw new UnsupportedOperationException("lightmap of type " + attribute.type()
                        + " is not handled: vanilla uses SHORT");
            }
            out.putShort(base + LIGHTMAP_OFFSET, (short) SHORT_VIEW.get(source, at));
            out.putShort(base + LIGHTMAP_OFFSET + 2, (short) SHORT_VIEW.get(source, at + 2));
            return;
        }
        if (attribute.type() != VertexFormatElement.EnumType.FLOAT) {
            throw new UnsupportedOperationException("texture coordinate of type " + attribute.type()
                    + " is not handled: vanilla uses FLOAT");
        }
        float u = (float) FLOAT_VIEW.get(source, at);
        float v = (float) FLOAT_VIEW.get(source, at + 4);
        if (textureMatrix != null) {
            float uPrime = textureMatrix[0] * u + textureMatrix[4] * v + textureMatrix[12];
            float vPrime = textureMatrix[1] * u + textureMatrix[5] * v + textureMatrix[13];
            out.putFloat(base + UV_OFFSET, uPrime);
            out.putFloat(base + UV_OFFSET + 4, vPrime);
        } else {
            out.putFloat(base + UV_OFFSET, u);
            out.putFloat(base + UV_OFFSET + 4, v);
        }
    }

    private static void copyUv(ByteBuffer in, int at, ByteBuffer out, int base, VertexLayout.Attribute attribute,
                               float[] textureMatrix) {
        if (attribute.uvIndex() == 1) {
            // The second texture coordinate is the lightmap: two unsigned shorts in the target.
            if (attribute.type() != VertexFormatElement.EnumType.SHORT
                    && attribute.type() != VertexFormatElement.EnumType.USHORT) {
                throw new UnsupportedOperationException("lightmap of type " + attribute.type()
                        + " is not handled: vanilla uses SHORT");
            }
            out.putShort(base + LIGHTMAP_OFFSET, in.getShort(at));
            out.putShort(base + LIGHTMAP_OFFSET + 2, in.getShort(at + 2));
            return;
        }
        if (attribute.type() != VertexFormatElement.EnumType.FLOAT) {
            throw new UnsupportedOperationException("texture coordinate of type " + attribute.type()
                    + " is not handled: vanilla uses FLOAT");
        }
        float u = in.getFloat(at);
        float v = in.getFloat(at + 4);
        if (textureMatrix != null) {
            float uPrime = textureMatrix[0] * u + textureMatrix[4] * v + textureMatrix[12];
            float vPrime = textureMatrix[1] * u + textureMatrix[5] * v + textureMatrix[13];
            out.putFloat(base + UV_OFFSET, uPrime);
            out.putFloat(base + UV_OFFSET + 4, vPrime);
        } else {
            out.putFloat(base + UV_OFFSET, u);
            out.putFloat(base + UV_OFFSET + 4, v);
        }
    }

    private static byte toByte(float value) {
        int scaled = (int) (value * 255.0F);
        return (byte) Math.max(0, Math.min(255, scaled));
    }

    private static float computeNormalLightingFactor(float nx, float ny, float nz,
                                                     VertexFormatElement.EnumType type,
                                                     float[] nm, float[] l0, float[] l1) {
        if (type == VertexFormatElement.EnumType.BYTE || type == VertexFormatElement.EnumType.UBYTE) {
            nx /= 127.0F;
            ny /= 127.0F;
            nz /= 127.0F;
        }
        float lenSq = nx * nx + ny * ny + nz * nz;
        if (lenSq <= 0.01F) {
            return 1.0F;
        }
        // Transform normal to eye space using column-major 3x3 normalMatrix:
        float ex = nm[0] * nx + nm[3] * ny + nm[6] * nz;
        float ey = nm[1] * nx + nm[4] * ny + nm[7] * nz;
        float ez = nm[2] * nx + nm[5] * ny + nm[8] * nz;
        float eLenSq = ex * ex + ey * ey + ez * ez;
        if (eLenSq < 1e-4F) {
            return 1.0F;
        }
        float invLen = 1.0F / (float) Math.sqrt(eLenSq);
        ex *= invLen;
        ey *= invLen;
        ez *= invLen;

        // OpenGL standard item lighting: ambient 0.4, diffuse 0.6 per light
        float d0 = Math.max(0.0F, ex * l0[0] + ey * l0[1] + ez * l0[2]);
        float d1 = Math.max(0.0F, ex * l1[0] + ey * l1[1] + ez * l1[2]);
        return Math.min(1.0F, 0.4F + 0.6F * d0 + 0.6F * d1);
    }
}
