package net.minecraftforge.client.render.vertex;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The GL primitive to Vulkan topology conversion.
 *
 * <p>The counts are the part worth testing. A quad strip of {@code n} vertices is {@code (n-2)/2} quads because
 * consecutive quads share an edge, and a fan of {@code n} vertices is {@code n-2} triangles - treating either as a
 * fixed vertices-per-primitive produces a mesh that is right except for the last primitive, which is exactly the
 * kind of error that survives a screenshot.</p>
 */
class PrimitiveShapeTest {

    private static int[] indices(PrimitiveShape shape, int vertexCount) {
        byte[] bytes = shape.indices(vertexCount);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
        int[] result = new int[shape.indexCount(vertexCount)];
        for (int i = 0; i < result.length; i++) {
            result[i] = PrimitiveShape.index32(vertexCount) ? buffer.getInt() : buffer.getShort() & 0xFFFF;
        }
        return result;
    }

    @Test
    void modesVulkanAcceptsArePassedThroughWithoutIndices() {
        // These are drawn straight from the uploaded bytes; building an index buffer would be work for nothing.
        for (int mode : new int[] {0, 1, 3, 4, 5}) {
            PrimitiveShape shape = PrimitiveShape.ofGlMode(mode);
            assertFalse(shape.needsIndexing(), "GL mode " + mode + " needs no index buffer");
            assertThrows(IllegalArgumentException.class, () -> shape.indices(12),
                    "asking for indices for GL mode " + mode + " is a caller error");
        }
    }

    @Test
    void quadsBecomeTwoTrianglesEach() {
        PrimitiveShape shape = PrimitiveShape.ofGlMode(7);

        assertEquals(PrimitiveShape.Topology.TRIANGLE_LIST, shape.topology());
        assertTrue(shape.needsIndexing());
        assertEquals(12, shape.indexCount(8), "two quads is four triangles");

        // Two quads, so the second quad's indices start at 4 - the base offset is the thing a single-quad test
        // cannot check.
        assertArrayEquals(new int[] {0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7}, indices(shape, 8));
    }

    @Test
    void quadStripSharesTheEdgeBetweenQuads() {
        PrimitiveShape shape = PrimitiveShape.ofGlMode(8);

        // Six vertices are two quads, not one and a half.
        assertEquals(12, shape.indexCount(6));
        assertArrayEquals(new int[] {0, 1, 2, 0, 2, 3, 2, 3, 4, 2, 4, 5}, indices(shape, 6));
    }

    @Test
    void triangleFanPivotsOnTheFirstVertex() {
        PrimitiveShape shape = PrimitiveShape.ofGlMode(6);

        // Five vertices are three triangles, not one.
        assertEquals(9, shape.indexCount(5));
        assertArrayEquals(new int[] {0, 1, 2, 0, 2, 3, 0, 3, 4}, indices(shape, 5));
    }

    @Test
    void lineLoopClosesBackToTheStart() {
        PrimitiveShape shape = PrimitiveShape.ofGlMode(2);

        assertEquals(8, shape.indexCount(4), "four segments for four vertices");
        assertArrayEquals(new int[] {0, 1, 1, 2, 2, 3, 3, 0}, indices(shape, 4));
    }

    @Test
    void shortCountsAreRejectedRatherThanSilentlyTruncated() {
        // Each of these would produce a wrong mesh if the count were rounded down instead.
        assertThrows(IllegalArgumentException.class, () -> PrimitiveShape.ofGlMode(7).indices(6),
                "six vertices is one and a half quads");
        assertThrows(IllegalArgumentException.class, () -> PrimitiveShape.ofGlMode(8).indices(5),
                "an odd quad strip has no last quad");
        assertThrows(IllegalArgumentException.class, () -> PrimitiveShape.ofGlMode(6).indices(2),
                "a fan needs three vertices for one triangle");
        assertThrows(IllegalArgumentException.class, () -> PrimitiveShape.ofGlMode(2).indices(1));
    }

    @Test
    void anUnknownModeIsLoud() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> PrimitiveShape.ofGlMode(42));
        assertTrue(thrown.getMessage().contains("42"), "the message has to name the mode: " + thrown.getMessage());
    }

    @Test
    void wideMeshesUseThirtyTwoBitIndices() {
        PrimitiveShape shape = PrimitiveShape.ofGlMode(7);

        assertFalse(PrimitiveShape.index32(0xFFFF), "65535 fits in 16 bits");
        assertTrue(PrimitiveShape.index32(0x10000), "65536 does not");
        assertEquals(0x10000 / 4 * 6 * Integer.BYTES, shape.indices(0x10000).length,
                "the index buffer widens as a whole, not per primitive");
    }
}
