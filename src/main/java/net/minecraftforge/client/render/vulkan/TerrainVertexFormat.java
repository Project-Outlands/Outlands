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

    // Aliases for stream and legacy callers:
    public static final int STRIDE = RAW_STRIDE;
    public static final int POSITION_OFFSET = RAW_POSITION_OFFSET;
    public static final int COLOR_OFFSET = RAW_COLOR_OFFSET;
    public static final int UV_OFFSET = RAW_UV_OFFSET;
    public static final int LIGHTMAP_OFFSET = RAW_LIGHTMAP_OFFSET;
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
     * Compacts {@code quadCount} quads from a native direct {@link ByteBuffer} (28-byte raw layout)
     * into a newly allocated compact 16-byte/vertex {@code byte[]} (64 bytes per quad).
     */
    public static byte[] compact(ByteBuffer srcBuffer, int quadCount) {
        int dstSize = quadCount * COMPACT_QUAD_SIZE;
        byte[] dst = new byte[dstSize];
        ByteBuffer dstBuf = ByteBuffer.wrap(dst).order(ByteOrder.nativeOrder());
        int vertexCount = quadCount * VERTICES_PER_QUAD;

        if (srcBuffer.isDirect()) {
            long src = MemoryUtil.memAddress(srcBuffer);
            for (int i = 0; i < vertexCount; i++) {
                float x = MemoryUtil.memGetFloat(src + RAW_POSITION_OFFSET);
                float y = MemoryUtil.memGetFloat(src + RAW_POSITION_OFFSET + 4);
                float z = MemoryUtil.memGetFloat(src + RAW_POSITION_OFFSET + 8);
                int color = MemoryUtil.memGetInt(src + RAW_COLOR_OFFSET);
                float u = MemoryUtil.memGetFloat(src + RAW_UV_OFFSET);
                float v = MemoryUtil.memGetFloat(src + RAW_UV_OFFSET + 4);
                short blockLight = MemoryUtil.memGetShort(src + RAW_LIGHTMAP_OFFSET);
                short skyLight = MemoryUtil.memGetShort(src + RAW_LIGHTMAP_OFFSET + 2);

                short sx = fixed(x);
                short sy = fixed(y);
                short sz = fixed(z);
                int bl = Math.min(255, Math.max(0, blockLight & 0xFFFF));
                int sl = Math.min(255, Math.max(0, skyLight & 0xFFFF));
                int lightPacked = bl | (sl << 8);

                int u16 = Math.min(65535, Math.max(0, Math.round(u * UV_SCALE)));
                int v16 = Math.min(65535, Math.max(0, Math.round(v * UV_SCALE)));

                int base = i * COMPACT_STRIDE;
                long posLight = ((long) (sx & 0xFFFF))
                        | (((long) (sy & 0xFFFF)) << 16)
                        | (((long) (sz & 0xFFFF)) << 32)
                        | (((long) lightPacked) << 48);
                dstBuf.putLong(base, posLight);

                long uv = ((long) (u16 & 0xFFFF)) | (((long) (v16 & 0xFFFF)) << 16);
                long uvColor = uv | (((long) color & 0xFFFFFFFFL) << 32);
                dstBuf.putLong(base + 8, uvColor);

                src += RAW_STRIDE;
            }
        } else {
            int srcOffset = srcBuffer.position();
            for (int i = 0; i < vertexCount; i++) {
                float x = srcBuffer.getFloat(srcOffset + RAW_POSITION_OFFSET);
                float y = srcBuffer.getFloat(srcOffset + RAW_POSITION_OFFSET + 4);
                float z = srcBuffer.getFloat(srcOffset + RAW_POSITION_OFFSET + 8);
                int color = srcBuffer.getInt(srcOffset + RAW_COLOR_OFFSET);
                float u = srcBuffer.getFloat(srcOffset + RAW_UV_OFFSET);
                float v = srcBuffer.getFloat(srcOffset + RAW_UV_OFFSET + 4);
                short blockLight = srcBuffer.getShort(srcOffset + RAW_LIGHTMAP_OFFSET);
                short skyLight = srcBuffer.getShort(srcOffset + RAW_LIGHTMAP_OFFSET + 2);

                short sx = fixed(x);
                short sy = fixed(y);
                short sz = fixed(z);
                int bl = Math.min(255, Math.max(0, blockLight & 0xFFFF));
                int sl = Math.min(255, Math.max(0, skyLight & 0xFFFF));
                int lightPacked = bl | (sl << 8);

                int u16 = Math.min(65535, Math.max(0, Math.round(u * UV_SCALE)));
                int v16 = Math.min(65535, Math.max(0, Math.round(v * UV_SCALE)));

                int base = i * COMPACT_STRIDE;
                long posLight = ((long) (sx & 0xFFFF))
                        | (((long) (sy & 0xFFFF)) << 16)
                        | (((long) (sz & 0xFFFF)) << 32)
                        | (((long) lightPacked) << 48);
                dstBuf.putLong(base, posLight);

                long uv = ((long) (u16 & 0xFFFF)) | (((long) (v16 & 0xFFFF)) << 16);
                long uvColor = uv | (((long) color & 0xFFFFFFFFL) << 32);
                dstBuf.putLong(base + 8, uvColor);

                srcOffset += RAW_STRIDE;
            }
        }
        return dst;
    }

    /**
     * Compacts {@code quadCount} quads from raw 28-byte {@code DefaultVertexFormats.BLOCK} array
     * directly into mapped native memory at {@code dstAddress} (16 bytes per vertex, 64 bytes per quad).
     */
    public static void compact(byte[] raw, int quadCount, long dstAddress) {
        int vertexCount = quadCount * VERTICES_PER_QUAD;
        int srcOffset = 0;
        long dst = dstAddress;
        for (int i = 0; i < vertexCount; i++) {
            float x = (float) FLOAT_VIEW.get(raw, srcOffset + RAW_POSITION_OFFSET);
            float y = (float) FLOAT_VIEW.get(raw, srcOffset + RAW_POSITION_OFFSET + 4);
            float z = (float) FLOAT_VIEW.get(raw, srcOffset + RAW_POSITION_OFFSET + 8);
            int color = (int) INT_VIEW.get(raw, srcOffset + RAW_COLOR_OFFSET);
            float u = (float) FLOAT_VIEW.get(raw, srcOffset + RAW_UV_OFFSET);
            float v = (float) FLOAT_VIEW.get(raw, srcOffset + RAW_UV_OFFSET + 4);
            short blockLight = (short) SHORT_VIEW.get(raw, srcOffset + RAW_LIGHTMAP_OFFSET);
            short skyLight = (short) SHORT_VIEW.get(raw, srcOffset + RAW_LIGHTMAP_OFFSET + 2);

            short sx = fixed(x);
            short sy = fixed(y);
            short sz = fixed(z);
            int bl = Math.min(255, Math.max(0, blockLight & 0xFFFF));
            int sl = Math.min(255, Math.max(0, skyLight & 0xFFFF));
            int lightPacked = bl | (sl << 8);

            int u16 = Math.min(65535, Math.max(0, Math.round(u * UV_SCALE)));
            int v16 = Math.min(65535, Math.max(0, Math.round(v * UV_SCALE)));

            // 0..7: 3x s16 pos, 1x s16 light (2x u8)
            long posLight = ((long) (sx & 0xFFFF))
                    | (((long) (sy & 0xFFFF)) << 16)
                    | (((long) (sz & 0xFFFF)) << 32)
                    | (((long) lightPacked) << 48);
            MemoryUtil.memPutLong(dst, posLight);

            // 8..15: 2x u16 unorm UV, 4x u8 unorm Color (RGBA)
            long uv = ((long) (u16 & 0xFFFF)) | (((long) (v16 & 0xFFFF)) << 16);
            long uvColor = uv | (((long) color & 0xFFFFFFFFL) << 32);
            MemoryUtil.memPutLong(dst + 8, uvColor);

            srcOffset += RAW_STRIDE;
            dst += COMPACT_STRIDE;
        }
    }

    /**
     * Compacts {@code quadCount} quads from raw 28-byte {@code DefaultVertexFormats.BLOCK} array
     * into a new 16-byte {@code byte[]} (used primarily for tests).
     */
    public static byte[] compactToBytes(byte[] raw, int quadCount) {
        int dstSize = quadCount * COMPACT_QUAD_SIZE;
        byte[] dst = new byte[dstSize];
        ByteBuffer buf = ByteBuffer.wrap(dst).order(ByteOrder.nativeOrder());
        int vertexCount = quadCount * VERTICES_PER_QUAD;
        int srcOffset = 0;
        for (int i = 0; i < vertexCount; i++) {
            float x = (float) FLOAT_VIEW.get(raw, srcOffset + RAW_POSITION_OFFSET);
            float y = (float) FLOAT_VIEW.get(raw, srcOffset + RAW_POSITION_OFFSET + 4);
            float z = (float) FLOAT_VIEW.get(raw, srcOffset + RAW_POSITION_OFFSET + 8);
            int color = (int) INT_VIEW.get(raw, srcOffset + RAW_COLOR_OFFSET);
            float u = (float) FLOAT_VIEW.get(raw, srcOffset + RAW_UV_OFFSET);
            float v = (float) FLOAT_VIEW.get(raw, srcOffset + RAW_UV_OFFSET + 4);
            short blockLight = (short) SHORT_VIEW.get(raw, srcOffset + RAW_LIGHTMAP_OFFSET);
            short skyLight = (short) SHORT_VIEW.get(raw, srcOffset + RAW_LIGHTMAP_OFFSET + 2);

            short sx = fixed(x);
            short sy = fixed(y);
            short sz = fixed(z);
            int bl = Math.min(255, Math.max(0, blockLight & 0xFFFF));
            int sl = Math.min(255, Math.max(0, skyLight & 0xFFFF));
            int lightPacked = bl | (sl << 8);

            int u16 = Math.min(65535, Math.max(0, Math.round(u * UV_SCALE)));
            int v16 = Math.min(65535, Math.max(0, Math.round(v * UV_SCALE)));

            int base = i * COMPACT_STRIDE;
            long posLight = ((long) (sx & 0xFFFF))
                    | (((long) (sy & 0xFFFF)) << 16)
                    | (((long) (sz & 0xFFFF)) << 32)
                    | (((long) lightPacked) << 48);
            buf.putLong(base, posLight);

            long uv = ((long) (u16 & 0xFFFF)) | (((long) (v16 & 0xFFFF)) << 16);
            long uvColor = uv | (((long) color & 0xFFFFFFFFL) << 32);
            buf.putLong(base + 8, uvColor);

            srcOffset += RAW_STRIDE;
        }
        return dst;
    }
}
