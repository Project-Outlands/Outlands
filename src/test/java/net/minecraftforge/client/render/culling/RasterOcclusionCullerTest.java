package net.minecraftforge.client.render.culling;

import grondag.bitraster.PackedBox;
import net.minecraftforge.client.render.culling.geometry.OccluderBoxes;
import net.minecraftforge.client.render.culling.geometry.RenderableBounds;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.*;

class RasterOcclusionCullerTest {

    @Test
    void testOccluderBoxesAndRenderableBounds() {
        RenderableBounds bounds = new RenderableBounds();
        assertTrue(bounds.isEmpty());
        assertEquals(PackedBox.FULL_BOX, bounds.packed());

        // Mark a block at (2, 3, 4)
        bounds.mark(2, 3, 4);
        assertFalse(bounds.isEmpty());
        int packed = bounds.packed();
        assertNotEquals(PackedBox.FULL_BOX, packed);

        // Test decomposing a solid 16x16x16 chunk (4096 blocks)
        BitSet bitSet = new BitSet(4096);
        bitSet.set(0, 4096);
        long[] opaqueBits = bitSet.toLongArray();
        int[] boxes = OccluderBoxes.from(opaqueBits);
        assertNotNull(boxes);
        assertTrue(boxes.length >= 1);
    }

    @Test
    void testPerspectiveOcclusionCulling() {
        RasterOcclusionCuller culler = new RasterOcclusionCuller();

        // Standard perspective projection matrix: 70 deg FOV, 1.0 aspect, 0.05 near, 500 far
        Matrix4f proj = new Matrix4f().setPerspective((float) Math.toRadians(70.0), 1.0f, 0.05f, 500.0f);
        // Looking along -Z (identity rotation)
        Matrix4f view = new Matrix4f();
        Matrix4f vp = new Matrix4f(proj).mul(view);

        // Camera at (8, 8, 0) - centered looking down -Z at the chunk column (0, 0)
        culler.prepareScene(1, 8.0, 8.0, 0.0, vp, 256.0f);

        // 1. Near chunk at (0, 0, -16) [distSq = 1 <= 3] must always be VISIBLE
        RasterOcclusionCuller.SectionVisibility nearVis = culler.testSection(0, 0, -16, 1, PackedBox.FULL_BOX);
        assertEquals(RasterOcclusionCuller.SectionVisibility.VISIBLE, nearVis);

        // 2. Foreground chunk at (0, 0, -32) [distSq = 4]
        RasterOcclusionCuller.SectionVisibility fgVis = culler.testSection(0, 0, -32, 4, PackedBox.FULL_BOX);
        assertEquals(RasterOcclusionCuller.SectionVisibility.VISIBLE, fgVis);

        // Rasterize solid chunk into buffer
        int[] fullChunkData = new int[] { PackedBox.FULL_BOX, PackedBox.FULL_BOX };
        culler.occludeSection(fullChunkData);

        // 3. Background chunk directly behind at (0, 0, -64) [distSq = 16]
        RasterOcclusionCuller.SectionVisibility bgVis = culler.testSection(0, 0, -64, 16, PackedBox.FULL_BOX);
        assertEquals(RasterOcclusionCuller.SectionVisibility.HIDDEN, bgVis,
                "Background chunk directly behind a solid chunk should be occluded (HIDDEN)");

        // 4. Side chunk at (24, 0, -64) [distSq = 17], within FOV but not covered by the foreground chunk
        RasterOcclusionCuller.SectionVisibility sideVis = culler.testSection(24, 0, -64, 17, PackedBox.FULL_BOX);
        assertEquals(RasterOcclusionCuller.SectionVisibility.VISIBLE, sideVis,
                "Side chunk within FOV and outside occluder projection should be VISIBLE");
    }
}
