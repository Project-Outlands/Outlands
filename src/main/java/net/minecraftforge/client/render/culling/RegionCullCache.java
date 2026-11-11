package net.minecraftforge.client.render.culling;

import net.minecraft.client.renderer.culling.ClippingHelper;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.culling.ICamera;

/**
 * Hierarchical region-level frustum culling cache (Phase 3.2).
 *
 * <p>Subchunks are grouped into 8x4x8 subchunk Regions (128x64x128 blocks).
 * A conservative classification against the camera frustum is cached per region once per frame.
 * If a region is {@link #FULLY_INSIDE}, all 256 subchunks within it bypass frustum plane tests entirely.
 * If a region is {@link #OUTSIDE}, all subchunks in it are immediately skipped.</p>
 */
public final class RegionCullCache {
    public static final int FULLY_INSIDE = ClippingHelper.FULLY_INSIDE;
    public static final int PARTIALLY_INSIDE = ClippingHelper.PARTIALLY_INSIDE;
    public static final int OUTSIDE = ClippingHelper.OUTSIDE;

    private static final int TABLE_SIZE = 1024;
    private static final int MASK = TABLE_SIZE - 1;

    // Region size: 8x4x8 subchunks = 128x64x128 blocks
    public static final int REGION_SIZE_XZ = 128;
    public static final int REGION_SIZE_Y = 64;
    private static final double PAD = 2.0;

    // Exact coordinates, not a packed key: a region is chunk >> 3, so a coordinate reaches about 234,000 at the world
    // border and does not fit in 13 bits. Packing silently aliased distant regions onto each other, which could hand
    // one region the OUTSIDE verdict computed for another and cull a whole region that was in view.
    private final int[] cacheKeyX = new int[TABLE_SIZE];
    private final int[] cacheKeyY = new int[TABLE_SIZE];
    private final int[] cacheKeyZ = new int[TABLE_SIZE];
    private final int[] cacheFrames = new int[TABLE_SIZE];
    private final byte[] cacheState = new byte[TABLE_SIZE];
    private int currentFrame = -1;

    public void begin(int frameCount) {
        this.currentFrame = frameCount;
    }

    /**
     * Returns the cached region visibility classification, computing it on first access in this frame.
     *
     * @param rx region coordinate X (chunkX >> 3)
     * @param ry region coordinate Y (chunkY >> 2)
     * @param rz region coordinate Z (chunkZ >> 3)
     * @param camera current active frustum camera
     * @return one of {@link #FULLY_INSIDE}, {@link #PARTIALLY_INSIDE}, or {@link #OUTSIDE}
     */
    public int classify(int rx, int ry, int rz, ICamera camera) {
        int slot = hash(rx, ry, rz) & MASK;

        if (this.cacheFrames[slot] == this.currentFrame
                && this.cacheKeyX[slot] == rx && this.cacheKeyY[slot] == ry && this.cacheKeyZ[slot] == rz) {
            return this.cacheState[slot];
        }

        // Cache miss: compute intersection of the padded Region AABB against frustum
        double minX = (double) (rx << 7) - PAD;
        double minY = (double) (ry << 6) - PAD;
        double minZ = (double) (rz << 7) - PAD;
        double maxX = (double) ((rx << 7) + REGION_SIZE_XZ) + PAD;
        double maxY = (double) ((ry << 6) + REGION_SIZE_Y) + PAD;
        double maxZ = (double) ((rz << 7) + REGION_SIZE_XZ) + PAD;

        int state;
        if (camera instanceof Frustum) {
            state = ((Frustum) camera).intersectBox(minX, minY, minZ, maxX, maxY, maxZ);
        } else {
            state = camera.isBoundingBoxInFrustum(new net.minecraft.util.math.AxisAlignedBB(minX, minY, minZ, maxX, maxY, maxZ))
                    ? PARTIALLY_INSIDE : OUTSIDE;
        }

        this.cacheFrames[slot] = this.currentFrame;
        this.cacheKeyX[slot] = rx;
        this.cacheKeyY[slot] = ry;
        this.cacheKeyZ[slot] = rz;
        this.cacheState[slot] = (byte) state;
        return state;
    }

    private static int hash(int rx, int ry, int rz) {
        int key = rz;
        key = key * 0x9E3779B1 + ry;
        key = key * 0x9E3779B1 + rx;
        key ^= key >>> 16;
        key *= 0x85ebca6b;
        key ^= key >>> 13;
        key *= 0xc2b2ae35;
        key ^= key >>> 16;
        return key;
    }
}
