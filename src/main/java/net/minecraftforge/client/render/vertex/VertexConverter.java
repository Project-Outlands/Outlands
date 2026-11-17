package net.minecraftforge.client.render.vertex;

import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import org.lwjgl.system.MemoryUtil;

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

    /** What the source carries for colour, resolved once per call so the vertex loop has no type tests. */
    private static final int COLOR_NONE = 0;
    private static final int COLOR_UBYTE4 = 1;
    private static final int COLOR_FLOAT4 = 2;
    private static final int COLOR_FLOAT3 = 3;

    /** What the source carries for a normal, likewise. */
    private static final int NORMAL_NONE = 0;
    private static final int NORMAL_BYTE = 1;
    private static final int NORMAL_FLOAT = 2;

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
                               LightingModel lighting, ByteBuffer target, int targetOffset) {
        convert(source, layout, vertexCount, fallbackColor, fallbackLightmap, lightingEnabled, normalMatrix,
                lighting, null, target, targetOffset);
    }

    /**
     * Converts vertices directly from a byte array in-place into an existing {@code target} buffer at {@code targetOffset}.
     */
    public static void convert(byte[] source, VertexLayout layout, int vertexCount, int fallbackColor,
                               float[] fallbackLightmap, boolean lightingEnabled, float[] normalMatrix,
                               LightingModel lighting, float[] textureMatrix,
                               ByteBuffer target, int targetOffset) {
        convert(source, 0, layout, vertexCount, fallbackColor, fallbackLightmap, lightingEnabled,
                normalMatrix, lighting, textureMatrix, target, targetOffset);
    }

    /**
     * Converts vertices directly from a byte array starting at {@code sourceOffset} in-place into an existing
     * {@code target} buffer at {@code targetOffset}.
     */
    public static void convert(byte[] source, int sourceOffset, VertexLayout layout, int vertexCount, int fallbackColor,
                               float[] fallbackLightmap, boolean lightingEnabled, float[] normalMatrix,
                               LightingModel lighting, float[] textureMatrix,
                               ByteBuffer target, int targetOffset) {
        int inStride = layout.stride();
        int required = vertexCount * inStride;
        if (source.length - sourceOffset < required) {
            throw new IllegalArgumentException("layout needs " + required + " bytes for " + vertexCount
                    + " vertices at stride " + inStride + ", the array has " + (source.length - sourceOffset));
        }
        if (vertexCount <= 0) {
            return;
        }

        // Already the target layout, byte for byte. Vanilla's BLOCK format is exactly it, and it is the format tile
        // entities, items and most mod draws use - so the common case costs a copy and nothing else.
        if (layout.format() == DefaultVertexFormats.BLOCK) {
            copyRange(source, sourceOffset, target, targetOffset, vertexCount * STRIDE);
            return;
        }

        convertGeneral(source, sourceOffset, layout, vertexCount, fallbackColor, fallbackLightmap, lightingEnabled,
                normalMatrix, lighting, textureMatrix, target, targetOffset);
    }

    /**
     * The loop for every format without a specialised one of its own.
     *
     * <p>Package-private because it is the answer the specialised loops have to agree with: a test converts the same
     * bytes through both and compares them, which is the only way to be sure an offset in a hand-written loop is
     * right without reading it twice.</p>
     */
    static void convertGeneral(byte[] source, int sourceOffset, VertexLayout layout, int vertexCount,
                               int fallbackColor, float[] fallbackLightmap, boolean lightingEnabled,
                               float[] normalMatrix, LightingModel lighting, float[] textureMatrix,
                               ByteBuffer target, int targetOffset) {
        int inStride = layout.stride();
        // Every attribute is resolved once, before the loop. The switch this replaced ran per vertex per attribute,
        // and for the formats that reach here it was most of what the method did.
        int positionAt = -1;
        int positionCount = 3;
        int uvAt = -1;
        int lightmapAt = -1;
        int colorAt = -1;
        int colorKind = COLOR_NONE;
        int normalAt = -1;
        int normalKind = NORMAL_NONE;
        for (VertexLayout.Attribute attribute : layout.attributesArray()) {
            switch (attribute.usage()) {
                case POSITION -> {
                    if (attribute.type() != VertexFormatElement.EnumType.FLOAT) {
                        throw new UnsupportedOperationException("position of type " + attribute.type()
                                + " is not handled: vanilla only uses FLOAT positions");
                    }
                    positionAt = attribute.offset();
                    positionCount = attribute.count();
                }
                case UV -> {
                    if (attribute.uvIndex() == 1) {
                        // The second texture coordinate is the lightmap: two unsigned shorts in the target.
                        if (attribute.type() != VertexFormatElement.EnumType.SHORT
                                && attribute.type() != VertexFormatElement.EnumType.USHORT) {
                            throw new UnsupportedOperationException("lightmap of type " + attribute.type()
                                    + " is not handled: vanilla uses SHORT");
                        }
                        lightmapAt = attribute.offset();
                    } else {
                        if (attribute.type() != VertexFormatElement.EnumType.FLOAT) {
                            throw new UnsupportedOperationException("texture coordinate of type " + attribute.type()
                                    + " is not handled: vanilla uses FLOAT");
                        }
                        uvAt = attribute.offset();
                    }
                }
                case COLOR -> {
                    colorAt = attribute.offset();
                    if (attribute.type() == VertexFormatElement.EnumType.FLOAT) {
                        colorKind = attribute.count() >= 4 ? COLOR_FLOAT4 : COLOR_FLOAT3;
                    } else if (attribute.type() == VertexFormatElement.EnumType.UBYTE && attribute.count() == 4) {
                        colorKind = COLOR_UBYTE4;
                    } else {
                        // No case for anything else: the fallback colour is used whole, which is what the old code
                        // did by reading nothing.
                        colorKind = COLOR_NONE;
                    }
                }
                case NORMAL -> {
                    normalAt = attribute.offset();
                    normalKind = switch (attribute.type()) {
                        case BYTE, UBYTE -> NORMAL_BYTE;
                        case FLOAT -> NORMAL_FLOAT;
                        default -> NORMAL_NONE;
                    };
                }
                default -> {
                }
            }
        }

        int fallbackLight = fallbackLight(fallbackLightmap);

        int fbR = (fallbackColor >>> 16) & 0xFF;
        int fbG = (fallbackColor >>> 8) & 0xFF;
        int fbB = fallbackColor & 0xFF;
        int fbA = (fallbackColor >>> 24) & 0xFF;

        boolean lit = normalAt >= 0 && lightingEnabled && normalMatrix != null && lighting != null;

        // Two layouts are most of a frame - the old-model one at 48% of the vertices and 79% of the draws, and the one
        // a frame's clouds and batched quads use at 28% of the vertices - and each has a loop that knows its own
        // layout. Matched on the resolved attributes rather than on the format object, so a mod that builds an equal
        // format of its own gets the same loop. See convertOldModelLit.
        if (lit && inStride == 24 && positionAt == 0 && positionCount == 3 && uvAt == 12 && lightmapAt < 0
                && colorKind == COLOR_NONE && normalAt == 20 && normalKind == NORMAL_BYTE) {
            convertOldModelLit(source, sourceOffset, vertexCount, fallbackColor, fallbackLight,
                    normalMatrix, lighting, textureMatrix, target, targetOffset);
            return;
        }
        if (inStride == 24 && positionAt == 0 && positionCount == 3 && uvAt == 12 && lightmapAt < 0
                && normalAt < 0 && colorKind == COLOR_UBYTE4 && colorAt == 20) {
            convertPositionTexColor(source, sourceOffset, vertexCount, fallbackColor, fallbackLight,
                    textureMatrix, target, targetOffset);
            return;
        }
        float[] cachedVertex = lit ? new float[] {1.0F, 1.0F, 1.0F} : null;
        float[] cachedConstant = lit ? new float[] {0.0F, 0.0F, 0.0F} : null;
        float lastNx = Float.NaN, lastNy = Float.NaN, lastNz = Float.NaN;

        // A direct target is the mapped vertex buffer, and writing to it through a long address is the only way to
        // avoid a bounds check per component. A heap target is a test or a byte array, where the buffer's own put is
        // the right call, so the choice is made once here rather than per write.
        long address = target.isDirect() ? MemoryUtil.memAddress(target) : 0L;
        boolean mvpTexture = textureMatrix != null;

        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int base = targetOffset + vertex * STRIDE;
            int sourceBase = sourceOffset + vertex * inStride;

            // Neutrals first, so a format that lacks an attribute still produces a sane vertex.
            putFloat(address, target, base + UV_OFFSET, 0.0F);
            putFloat(address, target, base + UV_OFFSET + 4, 0.0F);
            putInt(address, target, base + LIGHTMAP_OFFSET, fallbackLight);

            if (positionAt >= 0) {
                int at = sourceBase + positionAt;
                putFloat(address, target, base + POSITION_OFFSET, (float) FLOAT_VIEW.get(source, at));
                putFloat(address, target, base + POSITION_OFFSET + 4, (float) FLOAT_VIEW.get(source, at + 4));
                // A two-component position exists (GUI points); its third coordinate is zero rather than absent.
                putFloat(address, target, base + POSITION_OFFSET + 8,
                        positionCount >= 3 ? (float) FLOAT_VIEW.get(source, at + 8) : 0.0F);
            }

            if (lightmapAt >= 0) {
                int at = sourceBase + lightmapAt;
                putShort(address, target, base + LIGHTMAP_OFFSET, (short) SHORT_VIEW.get(source, at));
                putShort(address, target, base + LIGHTMAP_OFFSET + 2, (short) SHORT_VIEW.get(source, at + 2));
            }

            if (uvAt >= 0) {
                int at = sourceBase + uvAt;
                float u = (float) FLOAT_VIEW.get(source, at);
                float v = (float) FLOAT_VIEW.get(source, at + 4);
                if (mvpTexture) {
                    putFloat(address, target, base + UV_OFFSET,
                            textureMatrix[0] * u + textureMatrix[4] * v + textureMatrix[12]);
                    putFloat(address, target, base + UV_OFFSET + 4,
                            textureMatrix[1] * u + textureMatrix[5] * v + textureMatrix[13]);
                } else {
                    putFloat(address, target, base + UV_OFFSET, u);
                    putFloat(address, target, base + UV_OFFSET + 4, v);
                }
            }

            int r = fbR, g = fbG, b = fbB, a = fbA;
            switch (colorKind) {
                case COLOR_UBYTE4 -> {
                    int at = sourceBase + colorAt;
                    r = source[at] & 0xFF;
                    g = source[at + 1] & 0xFF;
                    b = source[at + 2] & 0xFF;
                    a = source[at + 3] & 0xFF;
                }
                case COLOR_FLOAT4 -> {
                    int at = sourceBase + colorAt;
                    r = toByte((float) FLOAT_VIEW.get(source, at)) & 0xFF;
                    g = toByte((float) FLOAT_VIEW.get(source, at + 4)) & 0xFF;
                    b = toByte((float) FLOAT_VIEW.get(source, at + 8)) & 0xFF;
                    a = toByte((float) FLOAT_VIEW.get(source, at + 12)) & 0xFF;
                }
                case COLOR_FLOAT3 -> {
                    int at = sourceBase + colorAt;
                    r = toByte((float) FLOAT_VIEW.get(source, at)) & 0xFF;
                    g = toByte((float) FLOAT_VIEW.get(source, at + 4)) & 0xFF;
                    b = toByte((float) FLOAT_VIEW.get(source, at + 8)) & 0xFF;
                    a = 0xFF;
                }
                default -> {
                }
            }

            if (lit) {
                int at = sourceBase + normalAt;
                float nx, ny, nz;
                switch (normalKind) {
                    case NORMAL_BYTE -> {
                        nx = source[at];
                        ny = source[at + 1];
                        nz = source[at + 2];
                    }
                    case NORMAL_FLOAT -> {
                        nx = (float) FLOAT_VIEW.get(source, at);
                        ny = (float) FLOAT_VIEW.get(source, at + 4);
                        nz = (float) FLOAT_VIEW.get(source, at + 8);
                    }
                    default -> {
                        nx = 0.0F;
                        ny = 0.0F;
                        nz = 0.0F;
                    }
                }

                if (nx != lastNx || ny != lastNy || nz != lastNz) {
                    lastNx = nx;
                    lastNy = ny;
                    lastNz = nz;
                    computeLightingFactor(nx, ny, nz, normalKind == NORMAL_BYTE
                                    ? VertexFormatElement.EnumType.BYTE : VertexFormatElement.EnumType.FLOAT,
                            normalMatrix, lighting, cachedVertex, cachedConstant);
                }

                // GL's equation as the model rearranged it: vertex * A + B. With colour material off - or on with a
                // mode that names no component this model has - A is zero and B is the material's own lit colour, so
                // the vertex colour takes no part and no special case is needed here.
                r = clampByte(Math.round(r * cachedVertex[0] + 255.0F * cachedConstant[0]));
                g = clampByte(Math.round(g * cachedVertex[1] + 255.0F * cachedConstant[1]));
                b = clampByte(Math.round(b * cachedVertex[2] + 255.0F * cachedConstant[2]));
            }

            putInt(address, target, base + COLOR_OFFSET, (a << 24) | (b << 16) | (g << 8) | r);
        }
    }

    /** The light a format without a lightmap attribute gets, from the fallback the caller supplied. */
    private static int fallbackLight(float[] fallbackLightmap) {
        int light = FULL_LIGHTMAP | (FULL_LIGHTMAP << 16);
        if (fallbackLightmap != null) {
            int block = Math.round(Math.max(0.0F, Math.min(240.0F, fallbackLightmap[0])));
            int sky = Math.round(Math.max(0.0F, Math.min(240.0F, fallbackLightmap[1])));
            light = (block & 0xFFFF) | ((sky & 0xFFFF) << 16);
        }
        return light;
    }

    /**
     * {@code OLDMODEL_POSITION_TEX_NORMAL}, lit: position at 0, texture at 12, normal at 20, 24 bytes a vertex.
     *
     * <p><b>Why a method of its own.</b> The general loop asks a question per vertex that this format answers the same
     * way every time: is there a lightmap, a colour, how many position components, which kind of colour, which kind of
     * normal. Those questions are cheap, and they were never the problem - the trouble is that the compiler only knows
     * their answer from its profile. A draw of a different format arrives, a branch the profile recorded as never
     * taken is taken, and HotSpot discards the whole compiled method as an uncommon trap; execution falls back to the
     * interpreter until a new version is compiled. One session's log had three such traps for the general method, one
     * of them after every couple of seconds of play, and a run that converted at 16 ns a vertex spent a whole
     * fifteen-second window at 327.</p>
     *
     * <p>Here there is nothing to guess: the method is only reached by a draw whose format is exactly this one, so the
     * per-vertex work is five reads, three writes and the lighting the normals ask for.</p>
     */
    static void convertOldModelLit(byte[] source, int sourceOffset, int vertexCount, int fallbackColor,
                                   int fallbackLight, float[] normalMatrix, LightingModel lighting,
                                   float[] textureMatrix, ByteBuffer target, int targetOffset) {
        // Position at 0, texture at 12, normal at 20: the caller has just matched the layout against exactly that.
        int inStride = 24;
        int fbR = (fallbackColor >>> 16) & 0xFF;
        int fbG = (fallbackColor >>> 8) & 0xFF;
        int fbB = fallbackColor & 0xFF;
        int fbA = (fallbackColor >>> 24) & 0xFF;

        // The lighting is a function of the normal alone, so it is recomputed only where the normal changes - which,
        // for a model, is once a face rather than once a vertex.
        float[] cachedVertex = {1.0F, 1.0F, 1.0F};
        float[] cachedConstant = {0.0F, 0.0F, 0.0F};
        float lastNx = Float.NaN, lastNy = Float.NaN, lastNz = Float.NaN;

        long address = target.isDirect() ? MemoryUtil.memAddress(target) : 0L;
        boolean transformed = textureMatrix != null;

        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int base = targetOffset + vertex * STRIDE;
            int at = sourceOffset + vertex * inStride;

            putFloat(address, target, base + POSITION_OFFSET, (float) FLOAT_VIEW.get(source, at));
            putFloat(address, target, base + POSITION_OFFSET + 4, (float) FLOAT_VIEW.get(source, at + 4));
            putFloat(address, target, base + POSITION_OFFSET + 8, (float) FLOAT_VIEW.get(source, at + 8));

            float u = (float) FLOAT_VIEW.get(source, at + 12);
            float v = (float) FLOAT_VIEW.get(source, at + 16);
            if (transformed) {
                putFloat(address, target, base + UV_OFFSET,
                        textureMatrix[0] * u + textureMatrix[4] * v + textureMatrix[12]);
                putFloat(address, target, base + UV_OFFSET + 4,
                        textureMatrix[1] * u + textureMatrix[5] * v + textureMatrix[13]);
            } else {
                putFloat(address, target, base + UV_OFFSET, u);
                putFloat(address, target, base + UV_OFFSET + 4, v);
            }

            // The format carries no lightmap, so every vertex gets the caller's fallback - full brightness unless the
            // game asked for something else.
            putInt(address, target, base + LIGHTMAP_OFFSET, fallbackLight);

            float nx = source[at + 20];
            float ny = source[at + 21];
            float nz = source[at + 22];
            if (nx != lastNx || ny != lastNy || nz != lastNz) {
                lastNx = nx;
                lastNy = ny;
                lastNz = nz;
                computeLightingFactor(nx, ny, nz, VertexFormatElement.EnumType.BYTE, normalMatrix, lighting,
                        cachedVertex, cachedConstant);
            }

            int r = clampByte(Math.round(fbR * cachedVertex[0] + 255.0F * cachedConstant[0]));
            int g = clampByte(Math.round(fbG * cachedVertex[1] + 255.0F * cachedConstant[1]));
            int b = clampByte(Math.round(fbB * cachedVertex[2] + 255.0F * cachedConstant[2]));
            putInt(address, target, base + COLOR_OFFSET, (fbA << 24) | (b << 16) | (g << 8) | r);
        }
    }

    /**
     * {@code POSITION_TEX_COLOR}: position at 0, texture at 12, colour at 20 as four unsigned bytes, 24 bytes a vertex.
     *
     * <p>Split out for the same reason as {@link #convertOldModelLit}, and this is the format a frame's particles and
     * its batched quads arrive in - few draws, thousands of vertices each, so the loop is entered rarely and runs
     * long, which is the worst shape for a branch that is not in the profile.</p>
     */
    static void convertPositionTexColor(byte[] source, int sourceOffset, int vertexCount, int fallbackColor,
                                        int fallbackLight, float[] textureMatrix, ByteBuffer target,
                                        int targetOffset) {
        // Position at 0, texture at 12, four colour bytes at 20: matched by the caller, like the loop above.
        int inStride = 24;

        long address = target.isDirect() ? MemoryUtil.memAddress(target) : 0L;
        boolean transformed = textureMatrix != null;

        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int base = targetOffset + vertex * STRIDE;
            int at = sourceOffset + vertex * inStride;

            putFloat(address, target, base + POSITION_OFFSET, (float) FLOAT_VIEW.get(source, at));
            putFloat(address, target, base + POSITION_OFFSET + 4, (float) FLOAT_VIEW.get(source, at + 4));
            putFloat(address, target, base + POSITION_OFFSET + 8, (float) FLOAT_VIEW.get(source, at + 8));

            float u = (float) FLOAT_VIEW.get(source, at + 12);
            float v = (float) FLOAT_VIEW.get(source, at + 16);
            if (transformed) {
                putFloat(address, target, base + UV_OFFSET,
                        textureMatrix[0] * u + textureMatrix[4] * v + textureMatrix[12]);
                putFloat(address, target, base + UV_OFFSET + 4,
                        textureMatrix[1] * u + textureMatrix[5] * v + textureMatrix[13]);
            } else {
                putFloat(address, target, base + UV_OFFSET, u);
                putFloat(address, target, base + UV_OFFSET + 4, v);
            }

            putInt(address, target, base + LIGHTMAP_OFFSET, fallbackLight);
            putInt(address, target, base + COLOR_OFFSET,
                    ((source[at + 23] & 0xFF) << 24) | ((source[at + 22] & 0xFF) << 16)
                            | ((source[at + 21] & 0xFF) << 8) | (source[at + 20] & 0xFF));
        }
    }

    /** Copies a byte range into the target, whichever kind of buffer it is. */
    private static void copyRange(byte[] source, int sourceOffset, ByteBuffer target, int targetOffset, int length) {
        if (target.isDirect()) {
            MemoryUtil.memByteBuffer(MemoryUtil.memAddress(target) + targetOffset, length)
                    .put(source, sourceOffset, length);
        } else {
            target.put(source, sourceOffset, length);
        }
    }

    private static void putFloat(long address, ByteBuffer out, int index, float value) {
        if (address != 0L) {
            MemoryUtil.memPutFloat(address + index, value);
        } else {
            out.putFloat(index, value);
        }
    }

    private static void putInt(long address, ByteBuffer out, int index, int value) {
        if (address != 0L) {
            MemoryUtil.memPutInt(address + index, value);
        } else {
            out.putInt(index, value);
        }
    }

    private static void putShort(long address, ByteBuffer out, int index, short value) {
        if (address != 0L) {
            MemoryUtil.memPutShort(address + index, value);
        } else {
            out.putShort(index, value);
        }
    }

    /**
     * Converts vertices in-place into an existing {@code target} buffer at {@code targetOffset}, optionally applying
     * fixed-function diffuse lighting in eye space and 2D texture coordinate transformation.
     */
    public static void convert(ByteBuffer source, VertexLayout layout, int vertexCount, int fallbackColor,
                               float[] fallbackLightmap, boolean lightingEnabled, float[] normalMatrix,
                               LightingModel lighting, float[] textureMatrix,
                               ByteBuffer target, int targetOffset) {
        if (source.hasArray()) {
            convert(source.array(), source.arrayOffset() + source.position(), layout, vertexCount,
                    fallbackColor, fallbackLightmap, lightingEnabled, normalMatrix, lighting,
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
        int fallbackLight = fallbackLight(fallbackLightmap);

        // Fallback colour packed into native byte order (R8G8B8A8).
        // fallbackColor arrives as 0xAARRGGBB.
        int fbR = (fallbackColor >>> 16) & 0xFF;
        int fbG = (fallbackColor >>> 8) & 0xFF;
        int fbB = fallbackColor & 0xFF;
        int fbA = (fallbackColor >>> 24) & 0xFF;

        float lastNx = Float.NaN, lastNy = Float.NaN, lastNz = Float.NaN;
        float[] cachedVertex = {1.0F, 1.0F, 1.0F};
        float[] cachedConstant = {0.0F, 0.0F, 0.0F};

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

            if (normalAttr != null && lightingEnabled && normalMatrix != null && lighting != null) {
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
                    computeLightingFactor(nx, ny, nz, normalAttr.type(), normalMatrix, lighting,
                            cachedVertex, cachedConstant);
                }

                // GL's equation as the model rearranged it: vertex * A + B. With colour material off - or on with a
                // mode that names no component this model has - A is zero and B is the material's own lit colour, so
                // the vertex colour takes no part and no special case is needed here.
                r = clampByte(Math.round(r * cachedVertex[0] + 255.0F * cachedConstant[0]));
                g = clampByte(Math.round(g * cachedVertex[1] + 255.0F * cachedConstant[1]));
                b = clampByte(Math.round(b * cachedVertex[2] + 255.0F * cachedConstant[2]));
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
                                 LightingModel lighting) {
        return convert(source, layout, vertexCount, fallbackColor, fallbackLightmap, lightingEnabled, normalMatrix,
                lighting, null);
    }

    public static byte[] convert(ByteBuffer source, VertexLayout layout, int vertexCount, int fallbackColor,
                                 float[] fallbackLightmap, boolean lightingEnabled, float[] normalMatrix,
                                 LightingModel lighting, float[] textureMatrix) {
        byte[] out = new byte[vertexCount * STRIDE];
        ByteBuffer target = ByteBuffer.wrap(out).order(ByteOrder.nativeOrder());
        convert(source, layout, vertexCount, fallbackColor, fallbackLightmap, lightingEnabled, normalMatrix,
                lighting, textureMatrix, target, 0);
        return out;
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


    /**
     * Computes the lighting factor for one normal and writes it into {@code out}.
     *
     * <p>The normal is transformed into eye space, where the lights live, and normalised: a vertex normal is not
     * unit length in general - a byte normal is 127 long, a float one is whatever the model shipped - and the
     * Lambert term is only a cosine for a unit vector. A normal that cannot be normalised leaves the factor at 1,
     * i.e. the vertex is drawn unattenuated rather than black, which is what a model with a zero normal means.</p>
     */
    private static void computeLightingFactor(float nx, float ny, float nz,
                                              VertexFormatElement.EnumType type,
                                              float[] nm, LightingModel lighting,
                                              float[] outVertex, float[] outConstant) {
        if (type == VertexFormatElement.EnumType.BYTE || type == VertexFormatElement.EnumType.UBYTE) {
            nx /= 127.0F;
            ny /= 127.0F;
            nz /= 127.0F;
        }
        float lenSq = nx * nx + ny * ny + nz * nz;
        if (lenSq <= 0.01F) {
            // A zero normal means "no normal": drawn unattenuated rather than black, which is what the model asked
            // for. The constant part is zero because there is no Lambert term to add.
            outVertex[0] = 1.0F;
            outVertex[1] = 1.0F;
            outVertex[2] = 1.0F;
            outConstant[0] = 0.0F;
            outConstant[1] = 0.0F;
            outConstant[2] = 0.0F;
            return;
        }
        // Transform normal to eye space using column-major 3x3 normalMatrix:
        float ex = nm[0] * nx + nm[3] * ny + nm[6] * nz;
        float ey = nm[1] * nx + nm[4] * ny + nm[7] * nz;
        float ez = nm[2] * nx + nm[5] * ny + nm[8] * nz;
        float eLenSq = ex * ex + ey * ey + ez * ez;
        if (eLenSq < 1e-4F) {
            outVertex[0] = 1.0F;
            outVertex[1] = 1.0F;
            outVertex[2] = 1.0F;
            outConstant[0] = 0.0F;
            outConstant[1] = 0.0F;
            outConstant[2] = 0.0F;
            return;
        }
        float invLen = 1.0F / (float) Math.sqrt(eLenSq);
        lighting.shadeFactor(ex * invLen, ey * invLen, ez * invLen, outVertex, outConstant);
    }

    /** {@code GL_QUADS}: the mode whose flat shading this converter applies. */
    private static final int GL_QUADS = 7;
    /** {@code GL_TRIANGLES}. */
    private static final int GL_TRIANGLES = 4;

    private static boolean flatShadingReported;

    /**
     * {@code glShadeModel(GL_FLAT)}: every vertex of a primitive takes the colour of its last one.
     *
     * <p><b>Why a pass over the output rather than a branch in the loop.</b> Flat shading is a property of the
     * <i>primitive</i>, and the loop that writes vertices does not know where one primitive ends. Copying the
     * provoking vertex's finished colour forward afterwards is the same answer with none of the bookkeeping, and it
     * also carries the lighting with it - which is what GL's flat shading does.</p>
     *
     * <p>Only quads and triangles are handled: a strip or a fan would need the triangle order, and no draw in the
     * pack asks for one while the model is flat. Anything else is reported once and left smooth, because a wrong
     * primitive is worse than an interpolation.</p>
     */
    public static void applyFlatShading(ByteBuffer target, int targetOffset, int vertexCount, int glMode) {
        int primitive = switch (glMode) {
            case GL_QUADS -> 4;
            case GL_TRIANGLES -> 3;
            default -> 0;
        };
        if (primitive == 0) {
            if (!flatShadingReported) {
                flatShadingReported = true;
                net.minecraftforge.client.render.state.backend.FrameStates.reportSkippedOnce(
                        "flat shading of primitive mode " + glMode,
                        "flat shading is applied to quads and triangles; a strip or fan needs the triangle order");
            }
            return;
        }
        for (int start = 0; start + primitive <= vertexCount; start += primitive) {
            int from = targetOffset + (start + primitive - 1) * STRIDE + COLOR_OFFSET;
            int r = target.get(from) & 0xFF;
            int g = target.get(from + 1) & 0xFF;
            int b = target.get(from + 2) & 0xFF;
            int a = target.get(from + 3) & 0xFF;
            for (int i = 0; i < primitive - 1; i++) {
                int to = targetOffset + (start + i) * STRIDE + COLOR_OFFSET;
                target.put(to, (byte) r).put(to + 1, (byte) g).put(to + 2, (byte) b).put(to + 3, (byte) a);
            }
        }
    }

    private static int clampByte(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }
}
