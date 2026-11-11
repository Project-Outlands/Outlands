package net.minecraftforge.client.render.culling;

import grondag.bitraster.BoxOccluder;
import grondag.bitraster.Constants;
import grondag.bitraster.Matrix4L;
import grondag.bitraster.PackedBox;
import grondag.bitraster.PerspectiveRasterizer;
import org.joml.Matrix4fc;

/**
 * CPU Software BitRaster Occlusion Culler (Phase 3.3).
 *
 * <p>Uses Grondag's ultra-fast CPU 1-bit rasterizer to project opaque terrain geometry (mountains, ground)
 * into a low-resolution coverage buffer. Distant subchunks hidden behind mountains or terrain are tested
 * against the buffer and culled prior to BFS propagation and GPU draw call generation.</p>
 */
public final class RasterOcclusionCuller extends BoxOccluder {
    /** Sections this close (in squared chunk distance <= 3) are drawn but never tested (almost always visible). */
    public static final int NEAR_SQUARED_CHUNK_DIST = 3;

    private static final int SIZE_STEP_TILES = 8;
    private static final float PIXELS_PER_BLOCK = 1.0f;

    /**
     * Switch for bisecting a rendering hole: when false, nothing is reported hidden, so the traversal submits every
     * section it reaches.
     */
    private static final boolean ENABLED = !"false".equals(System.getProperty("outlands.cull.raster"));

    private int viewSequence = 0;

    public enum SectionVisibility {
        /** nothing of the section is visible, and nothing behind it can be reached through it */
        HIDDEN,
        /** the section draws nothing visible, but the space it occupies is not fully covered, so
         *  traversal must continue through it or geometry behind it would be lost */
        TRAVERSABLE,
        /** the section is visible and can be used to occlude other sections */
        VISIBLE
    }

    public RasterOcclusionCuller() {
        super(new PerspectiveRasterizer());
    }

    @Override
    public boolean isBoxVisible(int packedBox, int fuzz) {
        return isBoxVisibleFromPerspective(packedBox, fuzz);
    }

    @Override
    public void occludeBox(int packedBox) {
        occludeFromPerspective(packedBox);
    }

    /**
     * Prepares the mask for one frame: sizes the buffer, clears it, and sets the view the boxes will be projected
     * with.
     *
     * <p>{@code viewVersion} is the frame the caller is preparing, and it is passed through rather than replaced by an
     * internal counter: the rasteriser uses it to decide when cached state from a previous view is stale, so a
     * silently-ignored argument made that decision always true.</p>
     */
    public void prepareScene(int viewVersion, double camX, double camY, double camZ, Matrix4fc vpMatrix, float testDistance) {
        if (vpMatrix == null) {
            return;
        }

        this.viewSequence = viewVersion;
        chooseBufferSize(vpMatrix, testDistance);
        invalidate();
        prepareScene(viewVersion, camX, camY, camZ,
                Matrix4L::loadIdentity, m -> copyMatrix(vpMatrix, m));
    }

    private static void copyMatrix(Matrix4fc src, Matrix4L dst) {
        dst.set(
                src.m00(), src.m10(), src.m20(), src.m30(),
                src.m01(), src.m11(), src.m21(), src.m31(),
                src.m02(), src.m12(), src.m22(), src.m32(),
                src.m03(), src.m13(), src.m23(), src.m33()
        );
    }

    private void chooseBufferSize(Matrix4fc vp, float searchDistance) {
        float perspectiveRow = (float) Math.sqrt((vp.m03() * vp.m03()) + (vp.m13() * vp.m13()) + (vp.m23() * vp.m23()));
        if (!(perspectiveRow > 0.5f) || !(searchDistance > 0)) {
            return;
        }

        float scaleX = (float) Math.sqrt((vp.m00() * vp.m00()) + (vp.m10() * vp.m10()) + (vp.m20() * vp.m20()));
        float scaleY = (float) Math.sqrt((vp.m01() * vp.m01()) + (vp.m11() * vp.m11()) + (vp.m21() * vp.m21()));

        if (!(scaleX > 0) || !(scaleY > 0)) {
            return;
        }

        float required = 2.0f * searchDistance * PIXELS_PER_BLOCK;
        int widthTiles = chooseAxisTiles(bufferWidth() >> Constants.TILE_AXIS_SHIFT, required / scaleX);
        int heightTiles = chooseAxisTiles(bufferHeight() >> Constants.TILE_AXIS_SHIFT, required / scaleY);
        resizeBuffer(widthTiles, heightTiles);
    }

    private static int chooseAxisTiles(int currentTiles, float requiredPixels) {
        int required = tilesFor(requiredPixels);
        if (required > currentTiles) {
            return required;
        }
        int shrunk = Math.min(Constants.MAX_SIZE_TILES, required + SIZE_STEP_TILES);
        return shrunk < currentTiles ? shrunk : currentTiles;
    }

    private static int tilesFor(float pixels) {
        int tiles = ((int) Math.ceil(pixels / (8 * SIZE_STEP_TILES))) * SIZE_STEP_TILES;
        return Math.max(Constants.MIN_SIZE_TILES, Math.min(Constants.MAX_SIZE_TILES, tiles));
    }

    /**
     * Tests if a subchunk at (originX, originY, originZ) is visible through the coverage buffer.
     *
     * @param originX block X coordinate of chunk origin (chunkX << 4)
     * @param originY block Y coordinate of chunk origin (chunkY << 4)
     * @param originZ block Z coordinate of chunk origin (chunkZ << 4)
     * @param squaredChunkDist squared distance in chunks from camera
     * @param bounds packed box bounds to test
     * @return {@link SectionVisibility#VISIBLE}, {@link SectionVisibility#TRAVERSABLE}, or {@link SectionVisibility#HIDDEN}
     */
    public SectionVisibility testSection(int originX, int originY, int originZ, int squaredChunkDist, int bounds) {
        if (!ENABLED) {
            return SectionVisibility.VISIBLE;
        }
        prepareRegion(originX, originY, originZ,
                PackedBox.rangeFromSquareChunkDist(squaredChunkDist), squaredChunkDist);

        if (squaredChunkDist <= NEAR_SQUARED_CHUNK_DIST) {
            return SectionVisibility.VISIBLE;
        }

        if (isBoxCenterClear(bounds)) {
            return SectionVisibility.VISIBLE;
        }

        if (isBoxVisible(bounds, 0)) {
            return SectionVisibility.VISIBLE;
        }

        if (bounds != PackedBox.FULL_BOX && (isBoxCenterClear(PackedBox.FULL_BOX) || isBoxVisible(PackedBox.FULL_BOX, 0))) {
            return SectionVisibility.TRAVERSABLE;
        }

        return SectionVisibility.HIDDEN;
    }

    /**
     * Draws the section's occluder boxes for the region prepared by the preceding {@link #testSection}.
     */
    public void occludeSection(int[] data) {
        if (data != null && data.length > 1) {
            occlude(data);
        }
    }

    /**
     * Draws a section's occluder boxes without a preceding {@link #testSection}.
     */
    public void occludeSectionAt(int originX, int originY, int originZ, int squaredChunkDist, int[] data) {
        if (data == null || data.length <= 1) {
            return;
        }
        prepareRegion(originX, originY, originZ,
                PackedBox.rangeFromSquareChunkDist(squaredChunkDist), squaredChunkDist);
        occlude(data);
    }
}
