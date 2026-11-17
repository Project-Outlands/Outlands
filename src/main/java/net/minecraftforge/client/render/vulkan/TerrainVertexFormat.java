package net.minecraftforge.client.render.vulkan;

import org.lwjgl.system.MemoryUtil;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Terrain vertex layout definitions and fast zero-allocation compaction.
 *
 * <p><b>28-byte raw incoming layout:</b> {@code DefaultVertexFormats.BLOCK} as produced by vanilla Minecraft:
 * 12 bytes position (3x float32), 4 bytes color (4x uint8 unorm), 8 bytes UV (2x float32), 4 bytes lightmap (2x uint16).
 * Quad size: 112 bytes.</p>
 *
 * <p><b>16-byte compact GPU layout:</b>
 * <ul>
 *   <li><b>Offset 0..7 (8 bytes):</b> {@code VK_FORMAT_R16G16B16A16_SINT} - 3x int16 relative position scaled by 512.0
 *       covering [-64.0, +63.998] with sub-millimeter precision, followed by 2x uint8 lightmap (block light in byte 6,
 *       sky light in byte 7, values in [0..240]).</li>
 *   <li><b>Offset 8..11 (4 bytes):</b> {@code VK_FORMAT_R16G16_UNORM} - 2x uint16 unorm texture coordinates in [0..1]
 *       providing 16 sub-texel steps on 4096x4096 atlases. Hardware-decoded into float32 on vertex fetch.</li>
 *   <li><b>Offset 12..15 (4 bytes):</b> {@code VK_FORMAT_R8G8B8A8_UNORM} - 4x uint8 unorm vertex baked shade and AO.
 *       Hardware-decoded into float32 on vertex fetch.</li>
 * </ul>
 * Quad size: 64 bytes (perfect single 64-byte GPU cacheline alignment).</p>
 */
public final class TerrainVertexFormat {

    /** Incoming raw vanilla DefaultVertexFormats.BLOCK: 28 bytes per vertex. */
    public static final int RAW_STRIDE = 28;
    public static final int RAW_POSITION_OFFSET = 0;
    public static final int RAW_COLOR_OFFSET = 12;
    public static final int RAW_UV_OFFSET = 16;
    public static final int RAW_LIGHTMAP_OFFSET = 24;

    /** Compact GPU vertex layout: 16 bytes per vertex. */
    public static final int COMPACT_STRIDE = 16;
    public static final int COMPACT_POSITION_LIGHT_OFFSET = 0; // 8 bytes: 3x s16 pos (scale 512.0), 2x u8 light
    public static final int COMPACT_UV_OFFSET = 8;             // 4 bytes: 2x u16 unorm (scale 65535.0)
    public static final int COMPACT_COLOR_OFFSET = 12;         // 4 bytes: 4x u8 unorm (RGBA)

    /** Vertices per quad. */
    public static final int VERTICES_PER_QUAD = 4;
    public static final int RAW_QUAD_SIZE = VERTICES_PER_QUAD * RAW_STRIDE;         // 112 bytes
    public static final int COMPACT_QUAD_SIZE = VERTICES_PER_QUAD * COMPACT_STRIDE; // 64 bytes

    /** Position fixed-point scale factor (512.0f covers [-64.0, +63.998] blocks). */
    public static final float POSITION_SCALE = 512.0f;

    /** UV unorm scale factor (65535.0f). */
    public static final float UV_SCALE = 65535.0f;

    public static final int COLOR_BYTES = 4;
    public static final int LIGHTMAP_BYTES = 4;

    private static final VarHandle FLOAT_VIEW =
            MethodHandles.byteArrayViewVarHandle(float[].class, ByteOrder.nativeOrder());
    private static final VarHandle SHORT_VIEW =
            MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.nativeOrder());
    private static final VarHandle INT_VIEW =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.nativeOrder());
    private static final VarHandle LONG_VIEW =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());

    private TerrainVertexFormat() {}

    /**
     * A section-local coordinate as signed fixed point, saturated.
     *
     * <p>The cast alone wraps: {@code (short) (70 * 512)} is negative, so a vertex written outside the [-64, 64) the
     * format covers jumps to the opposite side of the chunk instead of being pinned at the edge. Vanilla keeps
     * section coordinates inside that range, so this is a guard rather than a path - which is exactly why it has to
     * be cheap and correct: nothing else would notice it firing.</p>
     */
    private static short fixed(float value) {
        int scaled = Math.round(value * POSITION_SCALE);
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
    }

    /**
     * Compacts {@code quadCount} quads from vanilla's 28-byte layout into the 16-byte GPU layout.
     *
     * <p>Two loops, because reading is the only part that differs: a mapped buffer is read by address, a Java array
     * through byte-view var handles. Everything after the read - the fixed-point position, the packed lightmap, the
     * normalized texture coordinates - is the same arithmetic in both, and lives in one place.</p>
     */
    private static void compactDirect(long srcAddress, long dstAddress, int quadCount) {
        int vertexCount = quadCount * VERTICES_PER_QUAD;
        long src = srcAddress;
        long dst = dstAddress;
        long uBounds = 0L;
        long vBounds = 0L;
        for (int i = 0; i < vertexCount; i++) {
            if ((i & (VERTICES_PER_QUAD - 1)) == 0) {
                uBounds = quadUvBoundsAddress(src, RAW_UV_OFFSET);
                vBounds = quadUvBoundsAddress(src, RAW_UV_OFFSET + 4);
            }
            putVertex(dst, uBounds, vBounds,
                    MemoryUtil.memGetFloat(src + RAW_POSITION_OFFSET),
                    MemoryUtil.memGetFloat(src + RAW_POSITION_OFFSET + 4),
                    MemoryUtil.memGetFloat(src + RAW_POSITION_OFFSET + 8),
                    MemoryUtil.memGetInt(src + RAW_COLOR_OFFSET),
                    MemoryUtil.memGetFloat(src + RAW_UV_OFFSET),
                    MemoryUtil.memGetFloat(src + RAW_UV_OFFSET + 4),
                    MemoryUtil.memGetShort(src + RAW_LIGHTMAP_OFFSET),
                    MemoryUtil.memGetShort(src + RAW_LIGHTMAP_OFFSET + 2));
            src += RAW_STRIDE;
            dst += COMPACT_STRIDE;
        }
    }

    private static void compactArray(byte[] raw, byte[] dst, int quadCount) {
        int vertexCount = quadCount * VERTICES_PER_QUAD;
        int src = 0;
        int out = 0;
        long uBounds = 0L;
        long vBounds = 0L;
        for (int i = 0; i < vertexCount; i++) {
            if ((i & (VERTICES_PER_QUAD - 1)) == 0) {
                uBounds = quadUvBoundsArray(raw, src, RAW_UV_OFFSET);
                vBounds = quadUvBoundsArray(raw, src, RAW_UV_OFFSET + 4);
            }
            putVertex(dst, out, uBounds, vBounds,
                    (float) FLOAT_VIEW.get(raw, src + RAW_POSITION_OFFSET),
                    (float) FLOAT_VIEW.get(raw, src + RAW_POSITION_OFFSET + 4),
                    (float) FLOAT_VIEW.get(raw, src + RAW_POSITION_OFFSET + 8),
                    (int) INT_VIEW.get(raw, src + RAW_COLOR_OFFSET),
                    (float) FLOAT_VIEW.get(raw, src + RAW_UV_OFFSET),
                    (float) FLOAT_VIEW.get(raw, src + RAW_UV_OFFSET + 4),
                    (short) SHORT_VIEW.get(raw, src + RAW_LIGHTMAP_OFFSET),
                    (short) SHORT_VIEW.get(raw, src + RAW_LIGHTMAP_OFFSET + 2));
            src += RAW_STRIDE;
            out += COMPACT_STRIDE;
        }
    }

    private static void compactArrayToAddress(byte[] raw, long dstAddress, int quadCount) {
        int vertexCount = quadCount * VERTICES_PER_QUAD;
        int src = 0;
        long dst = dstAddress;
        long uBounds = 0L;
        long vBounds = 0L;
        for (int i = 0; i < vertexCount; i++) {
            if ((i & (VERTICES_PER_QUAD - 1)) == 0) {
                uBounds = quadUvBoundsArray(raw, src, RAW_UV_OFFSET);
                vBounds = quadUvBoundsArray(raw, src, RAW_UV_OFFSET + 4);
            }
            putVertex(dst, uBounds, vBounds,
                    (float) FLOAT_VIEW.get(raw, src + RAW_POSITION_OFFSET),
                    (float) FLOAT_VIEW.get(raw, src + RAW_POSITION_OFFSET + 4),
                    (float) FLOAT_VIEW.get(raw, src + RAW_POSITION_OFFSET + 8),
                    (int) INT_VIEW.get(raw, src + RAW_COLOR_OFFSET),
                    (float) FLOAT_VIEW.get(raw, src + RAW_UV_OFFSET),
                    (float) FLOAT_VIEW.get(raw, src + RAW_UV_OFFSET + 4),
                    (short) SHORT_VIEW.get(raw, src + RAW_LIGHTMAP_OFFSET),
                    (short) SHORT_VIEW.get(raw, src + RAW_LIGHTMAP_OFFSET + 2));
            src += RAW_STRIDE;
            dst += COMPACT_STRIDE;
        }
    }

    /** The two 64-bit words of one vertex, in the order the layout stores them. */
    private static long posLight(float x, float y, float z, short blockLight, short skyLight) {
        // Vanilla writes block light to the element's first short and sky light to the second - the opposite of the
        // argument order of BufferBuilder.lightmap. The terrain shader reads them back in this order.
        int light = Math.min(255, Math.max(0, blockLight & 0xFFFF))
                | (Math.min(255, Math.max(0, skyLight & 0xFFFF)) << 8);
        return ((long) (fixed(x) & 0xFFFF))
                | (((long) (fixed(y) & 0xFFFF)) << 16)
                | (((long) (fixed(z) & 0xFFFF)) << 32)
                | (((long) light) << 48);
    }

    /**
     * The quantised bounds of one texture axis over a quad's four vertices, packed as {@code (low << 32) | high}.
     *
     * <p><b>Why a quad needs its own bounds.</b> {@code u} and {@code v} are stored as 16-bit normalized values, so a
     * sprite edge is not exactly representable: the step is 1/65535, which is 0.0625 of a texel on a 4096-wide atlas,
     * and rounding to the nearest step can land a hair <i>outside</i> the sprite. At the edge of a block face that hair
     * is enough to sample the next sprite in the atlas - which is a visible seam at every block boundary of a fluid,
     * because the atlas has no gutter between sprites and the fluid's neighbour is its own darker flow texture.
     * Rounding each vertex against its quad's own rectangle costs one comparison and removes it.</p>
     */
    private static long quadUvBoundsAddress(long srcAddress, int uvOffset) {
        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        for (int v = 0; v < VERTICES_PER_QUAD; v++) {
            float value = MemoryUtil.memGetFloat(srcAddress + (long) v * RAW_STRIDE + uvOffset);
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        return quantisedBounds(min, max);
    }

    private static long quadUvBoundsArray(byte[] raw, int src, int uvOffset) {
        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        for (int v = 0; v < VERTICES_PER_QUAD; v++) {
            float value = (float) FLOAT_VIEW.get(raw, src + v * RAW_STRIDE + uvOffset);
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        return quantisedBounds(min, max);
    }

    private static long quantisedBounds(float min, float max) {
        int low = (int) Math.ceil(min * UV_SCALE);
        int high = (int) Math.floor(max * UV_SCALE);
        if (low > high) {
            // A degenerate quad, or one whose rectangle is narrower than a step: nothing to keep it inside of.
            low = high = Math.round(min * UV_SCALE);
        }
        low = Math.min(65535, Math.max(0, low));
        high = Math.min(65535, Math.max(0, high));
        return (((long) low) << 32) | (high & 0xFFFFFFFFL);
    }

    private static int quantise(float value, long bounds) {
        int low = (int) (bounds >> 32);
        int high = (int) bounds;
        int quantised = Math.round(value * UV_SCALE);
        return Math.min(high, Math.max(low, quantised));
    }

    private static long uvColor(float u, float v, long uBounds, long vBounds, int color) {
        int u16 = quantise(u, uBounds);
        int v16 = quantise(v, vBounds);
        return ((long) u16) | (((long) v16) << 16) | (((long) color & 0xFFFFFFFFL) << 32);
    }

    private static void putVertex(long dst, long uBounds, long vBounds, float x, float y, float z, int color,
                                  float u, float v, short blockLight, short skyLight) {
        MemoryUtil.memPutLong(dst, posLight(x, y, z, blockLight, skyLight));
        MemoryUtil.memPutLong(dst + 8, uvColor(u, v, uBounds, vBounds, color));
    }

    private static void putVertex(byte[] dst, int offset, long uBounds, long vBounds, float x, float y, float z,
                                  int color, float u, float v, short blockLight, short skyLight) {
        LONG_VIEW.set(dst, offset, posLight(x, y, z, blockLight, skyLight));
        LONG_VIEW.set(dst, offset + 8, uvColor(u, v, uBounds, vBounds, color));
    }

    /** Compacts from mapped memory into a byte array: the arena's path when a mesh is rebuilt in place. */
    private static void compactDirectToArray(long srcAddress, byte[] dst, int quadCount) {
        int vertexCount = quadCount * VERTICES_PER_QUAD;
        long src = srcAddress;
        int out = 0;
        long uBounds = 0L;
        long vBounds = 0L;
        for (int i = 0; i < vertexCount; i++) {
            if ((i & (VERTICES_PER_QUAD - 1)) == 0) {
                uBounds = quadUvBoundsAddress(src, RAW_UV_OFFSET);
                vBounds = quadUvBoundsAddress(src, RAW_UV_OFFSET + 4);
            }
            putVertex(dst, out, uBounds, vBounds,
                    MemoryUtil.memGetFloat(src + RAW_POSITION_OFFSET),
                    MemoryUtil.memGetFloat(src + RAW_POSITION_OFFSET + 4),
                    MemoryUtil.memGetFloat(src + RAW_POSITION_OFFSET + 8),
                    MemoryUtil.memGetInt(src + RAW_COLOR_OFFSET),
                    MemoryUtil.memGetFloat(src + RAW_UV_OFFSET),
                    MemoryUtil.memGetFloat(src + RAW_UV_OFFSET + 4),
                    MemoryUtil.memGetShort(src + RAW_LIGHTMAP_OFFSET),
                    MemoryUtil.memGetShort(src + RAW_LIGHTMAP_OFFSET + 2));
            src += RAW_STRIDE;
            out += COMPACT_STRIDE;
        }
    }

    /**
     * Compacts quads from a buffer in vanilla's layout into a new byte array.
     *
     * <p>Allocates the result and nothing else. A Java array is written through a byte-view long handle rather than a
     * wrapped buffer, so there is no scratch allocation and no second pass - which matters because this is the
     * per-chunk-layer path, not a one-off.</p>
     */
    public static byte[] compact(ByteBuffer srcBuffer, int quadCount) {
        byte[] dst = new byte[quadCount * COMPACT_QUAD_SIZE];
        compactDirectToArray(MemoryUtil.memAddress(srcBuffer), dst, quadCount);
        return dst;
    }

    /**
     * Compacts quads into the caller's array, which must hold at least {@code quadCount * 16} bytes.
     *
     * <p>The entry point for a caller that already owns a buffer - a recycled one, say. Nothing is allocated, and the
     * offset is zero: the array is the mesh from its first byte.</p>
     */
    public static void compact(ByteBuffer srcBuffer, int quadCount, byte[] dst) {
        int required = quadCount * COMPACT_QUAD_SIZE;
        if (dst.length < required) {
            throw new IllegalArgumentException("compacting " + quadCount + " quads needs " + required
                    + " bytes, the array holds " + dst.length);
        }
        compactDirectToArray(MemoryUtil.memAddress(srcBuffer), dst, quadCount);
    }

    /**
     * Compacts quads from a byte array in vanilla's layout into the given address, which must be mapped memory.
     */
    public static void compact(byte[] raw, int quadCount, long dstAddress) {
        compactArrayToAddress(raw, dstAddress, quadCount);
    }

    /**
     * Compacts quads from a byte array in vanilla's layout, allocating the result.
     */
    public static byte[] compactToBytes(byte[] raw, int quadCount) {
        byte[] dst = new byte[quadCount * COMPACT_QUAD_SIZE];
        compactArray(raw, dst, quadCount);
        return dst;
    }

    /**
     * Compacts quads already in mapped memory into other mapped memory. Used when a mesh moves within the arena.
     */
    public static void compact(long srcAddress, long dstAddress, int quadCount) {
        compactDirect(srcAddress, dstAddress, quadCount);
    }
}
