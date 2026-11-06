package net.minecraftforge.client.render.vulkan;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The quad-to-triangle conversion, which is the whole of what vanilla's data needs.
 *
 * <p>The assertions are about the two things that can silently produce a wrong picture: the winding order, and the
 * vertex base offsets for quads after the first. A mesh that only ever had one quad would pass either of those
 * being wrong.</p>
 */
class TerrainMeshBuilderTest {

    /** A quad's four vertices, each filled with a distinguishable byte pattern so pass-through is observable. */
    private static byte[] quads(int quadCount) {
        byte[] data = new byte[quadCount * 4 * TerrainVertexFormat.STRIDE];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        return data;
    }

    private static int[] indices(TerrainMeshBuilder.Mesh mesh) {
        ByteBuffer buffer = ByteBuffer.wrap(mesh.indices()).order(ByteOrder.nativeOrder());
        int[] result = new int[mesh.indexCount()];
        for (int i = 0; i < result.length; i++) {
            result[i] = mesh.index32() ? buffer.getInt() : buffer.getShort() & 0xFFFF;
        }
        return result;
    }

    @Test
    void oneQuadBecomesFourVerticesAndTwoTriangles() {
        TerrainMeshBuilder.Mesh mesh = TerrainMeshBuilder.build(quads(1), 1);

        assertEquals(4 * TerrainVertexFormat.STRIDE, mesh.vertices().length, "four vertices, not six");
        assertEquals(6, mesh.indexCount());
        // GL_QUADS tessellates 0,1,2,3 as (0,1,2) and (0,2,3). Anything else reverses the winding and the face
        // disappears under back-face culling.
        assertArrayEquals(new int[] {0, 1, 2, 0, 2, 3}, indices(mesh));
    }

    @Test
    void vertexBytesPassThroughUnchanged() {
        byte[] input = quads(3);
        TerrainMeshBuilder.Mesh mesh = TerrainMeshBuilder.build(input, 3);

        // No conversion, no reordering: the terrain format is already what the pipeline wants.
        assertArrayEquals(input, mesh.vertices());
    }

    @Test
    void laterQuadsAreIndexedFromTheirOwnBase() {
        TerrainMeshBuilder.Mesh mesh = TerrainMeshBuilder.build(quads(3), 3);

        assertArrayEquals(new int[] {
                0, 1, 2, 0, 2, 3,
                4, 5, 6, 4, 6, 7,
                8, 9, 10, 8, 10, 11,
        }, indices(mesh));
    }

    @Test
    void sixteenBitIndicesWhileTheyFit() {
        // 16383 quads is 65532 vertices, the largest 16-bit mesh.
        TerrainMeshBuilder.Mesh mesh = TerrainMeshBuilder.build(quads(16_383), 16_383);
        assertFalse(mesh.index32());
        assertEquals(16_383 * 6 * Short.BYTES, mesh.indices().length);
    }

    @Test
    void thirtyTwoBitIndicesWhenTheyDoNot() {
        // 16384 quads is 65536 vertices, one past what a 16-bit index can address.
        TerrainMeshBuilder.Mesh mesh = TerrainMeshBuilder.build(quads(16_384), 16_384);
        assertTrue(mesh.index32(), "a mesh past 65535 vertices must widen its indices");
        assertEquals(16_384 * 6 * Integer.BYTES, mesh.indices().length);
    }

    @Test
    void indexedIsSmallerThanExpanding() {
        int quadCount = 100;
        TerrainMeshBuilder.Mesh mesh = TerrainMeshBuilder.build(quads(quadCount), quadCount);

        int indexed = mesh.vertices().length + mesh.indices().length;
        int expanded = quadCount * 6 * TerrainVertexFormat.STRIDE;
        assertTrue(indexed < expanded, "indexing must save the 4-to-6 expansion: " + indexed + " vs " + expanded);
    }

    @Test
    void emptyInputIsAllowed() {
        TerrainMeshBuilder.Mesh mesh = TerrainMeshBuilder.build(new byte[0], 0);
        assertEquals(0, mesh.indexCount());
        assertEquals(0, mesh.vertices().length);
    }

    @Test
    void aTruncatedQuadIsRejected() {
        // Half a vertex too short: the kind of thing a buffer with a stale count would produce.
        byte[] input = new byte[4 * TerrainVertexFormat.STRIDE - 2];
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TerrainMeshBuilder.build(input, 1));
        assertTrue(error.getMessage().contains("expected"), error.getMessage());
    }
}
