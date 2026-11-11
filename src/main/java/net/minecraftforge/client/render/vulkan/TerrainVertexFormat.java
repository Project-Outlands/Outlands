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
        for (int i = 0; i < vertexCount; i++) {
            putVertex(dst,
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

    private static void compactArray(byte[] raw, long dstAddress, int quadCount) {
        int vertexCount = quadCount * VERTICES_PER_QUAD;
        int src = 0;
        long dst = dstAddress;
        for (int i = 0; i < vertexCount; i++) {
            putVertex(dst,
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

    /** Writes one 16-byte vertex: position and light in the first word, texture coordinates and colour in the second. */
    private static void putVertex(long dst, float x, float y, float z, int color, float u, float v,
                                  short blockLight, short skyLight) {
        // Vanilla writes block light to the element's first short and sky light to the second - the opposite of the
        // argument order of BufferBuilder.lightmap. The terrain shader reads them back in this order.
        int light = Math.min(255, Math.max(0, blockLight & 0xFFFF))
                | (Math.min(255, Math.max(0, skyLight & 0xFFFF)) << 8);
        long posLight = ((long) (fixed(x) & 0xFFFF))
                | (((long) (fixed(y) & 0xFFFF)) << 16)
                | (((long) (fixed(z) & 0xFFFF)) << 32)
                | (((long) light) << 48);
        int u16 = Math.min(65535, Math.max(0, Math.round(u * UV_SCALE)));
        int v16 = Math.min(65535, Math.max(0, Math.round(v * UV_SCALE)));
        long uvColor = ((long) u16) | (((long) v16) << 16) | (((long) color & 0xFFFFFFFFL) << 32);

        MemoryUtil.memPutLong(dst, posLight);
        MemoryUtil.memPutLong(dst + 8, uvColor);
    }

    /**
     * Compacts quads from a buffer in vanilla's layout into a new byte array.
     *
     * <p>A Java array has no address, so the write goes through a direct scratch buffer and one bulk copy. That is
     * the cost of the alloc-and-copy entry point; the arena path writes straight into mapped memory and pays nothing.
     * (Addressing a heap {@code ByteBuffer} is not an option - the address it reports is not usable memory.)</p>
     */
    public static byte[] compact(ByteBuffer srcBuffer, int quadCount) {
        ByteBuffer scratch = scratch(quadCount);
        compactDirect(MemoryUtil.memAddress(srcBuffer), MemoryUtil.memAddress(scratch), quadCount);
        return toArray(scratch, quadCount);
    }

    /**
     * Compacts quads from a byte array in vanilla's layout into the given address, which must be mapped memory.
     */
    public static void compact(byte[] raw, int quadCount, long dstAddress) {
        compactArray(raw, dstAddress, quadCount);
    }

    /**
     * Compacts quads from a byte array in vanilla's layout, allocating the result.
     */
    public static byte[] compactToBytes(byte[] raw, int quadCount) {
        ByteBuffer scratch = scratch(quadCount);
        compactArray(raw, MemoryUtil.memAddress(scratch), quadCount);
        return toArray(scratch, quadCount);
    }

    private static ByteBuffer scratch(int quadCount) {
        return ByteBuffer.allocateDirect(quadCount * COMPACT_QUAD_SIZE).order(ByteOrder.nativeOrder());
    }

    private static byte[] toArray(ByteBuffer scratch, int quadCount) {
        byte[] dst = new byte[quadCount * COMPACT_QUAD_SIZE];
        scratch.position(0);
        scratch.get(dst);
        return dst;
    }
}
