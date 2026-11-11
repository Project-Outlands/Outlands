package net.minecraftforge.client.render.vulkan;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Packing many chunk meshes into one buffer.
 *
 * <p>The thing worth testing is not that bytes get concatenated - it is the bookkeeping the draw call depends on:
 * each chunk's {@code firstIndex} and {@code vertexOffset}. A wrong offset draws the right number of triangles from
 * the wrong place, which looks like corrupted geometry rather than like an off-by-one.</p>
 */
class TerrainMeshPackerTest {

    /** Builds a mesh whose vertex bytes are distinguishable per mesh, so placement is observable. */
    /** The same mesh the old helper returned, wrapped with an origin and a layer, which is what packing now needs. */
    private static TerrainMeshPacker.Entry entry(TerrainMeshBuilder.Mesh mesh) {
        return new TerrainMeshPacker.Entry(mesh, 0, 0, 0, 0);
    }

    private static TerrainMeshBuilder.Mesh mesh(int quadCount, byte tag) {
        byte[] quads = new byte[quadCount * 4 * TerrainVertexFormat.RAW_STRIDE];
        for (int i = 0; i < quads.length; i++) {
            quads[i] = tag;
        }
        return TerrainMeshBuilder.build(quads, quadCount);
    }

    @Test
    void slicesPointAtEachChunksOwnData() {
        TerrainMeshPacker.Pack pack = TerrainMeshPacker.pack(List.of(entry(mesh(1, (byte) 1)), entry(mesh(2, (byte) 2))));

        assertEquals(2, pack.slices().size());

        TerrainMeshPacker.Slice first = pack.slices().get(0);
        assertEquals(0, first.firstIndex());
        assertEquals(6, first.indexCount());
        assertEquals(0, first.vertexOffset());

        // Second mesh starts after the first: 6 indices in, 4 vertices in.
        TerrainMeshPacker.Slice second = pack.slices().get(1);
        assertEquals(6, second.firstIndex());
        assertEquals(12, second.indexCount());
        assertEquals(4, second.vertexOffset());
    }

    @Test
    void vertexBytesAreConcatenatedInOrder() {
        TerrainMeshPacker.Pack pack = TerrainMeshPacker.pack(List.of(entry(mesh(1, (byte) 7)), entry(mesh(1, (byte) 9))));

        int perMesh = 4 * TerrainVertexFormat.RAW_STRIDE;
        assertEquals(perMesh * 2, pack.vertices().length);
        assertEquals(7, pack.vertices()[0], "first mesh's bytes come first");
        assertEquals(9, pack.vertices()[perMesh], "second mesh's bytes follow");
    }

    @Test
    void indicesStayChunkRelativeAndTheOffsetCarriesTheBase() {
        TerrainMeshPacker.Pack pack = TerrainMeshPacker.pack(List.of(entry(mesh(1, (byte) 1)), entry(mesh(1, (byte) 1))));

        ByteBuffer indices = ByteBuffer.wrap(pack.indices()).order(ByteOrder.nativeOrder());
        int[] read = new int[12];
        for (int i = 0; i < read.length; i++) {
            read[i] = pack.index32() ? indices.getInt() : indices.getShort() & 0xFFFF;
        }
        // Both chunks use the same local indices 0,1,2,0,2,3; the base comes from vertexOffset instead.
        assertArrayEquals(new int[] {0, 1, 2, 0, 2, 3, 0, 1, 2, 0, 2, 3}, read);
        assertEquals(4, pack.slices().get(1).vertexOffset());
    }

    @Test
    void aSmallPackStaysSixteenBit() {
        TerrainMeshPacker.Pack pack = TerrainMeshPacker.pack(List.of(entry(mesh(1, (byte) 0)), entry(mesh(1, (byte) 0))));
        assertFalse(pack.index32());
        assertEquals(12 * Short.BYTES, pack.indices().length);
    }

    @Test
    void oneLargeMeshWidensTheWholePack() {
        // 16384 quads is 65536 vertices: past what a 16-bit index can address once the pack shares an index space.
        TerrainMeshBuilder.Mesh big = mesh(16_384, (byte) 0);
        TerrainMeshPacker.Pack pack = TerrainMeshPacker.pack(List.of(entry(big), entry(mesh(1, (byte) 0))));

        assertTrue(pack.index32(), "the pack must widen when any slice pushes it past 65535 vertices");
        assertEquals((16_384 + 1) * 6 * Integer.BYTES, pack.indices().length);
    }

    @Test
    void wideningPreservesIndexValues() {
        TerrainMeshBuilder.Mesh big = mesh(16_384, (byte) 0);
        TerrainMeshBuilder.Mesh small = mesh(1, (byte) 0);
        TerrainMeshPacker.Pack pack = TerrainMeshPacker.pack(List.of(entry(big), entry(small)));

        ByteBuffer indices = ByteBuffer.wrap(pack.indices()).order(ByteOrder.nativeOrder());
        int lastSliceStart = pack.slices().get(1).firstIndex();
        indices.position(lastSliceStart * Integer.BYTES);
        // The small mesh's indices are still its own 0,1,2,0,2,3 - widened, not rebased.
        assertArrayEquals(new int[] {0, 1, 2, 0, 2, 3},
                new int[] {indices.getInt(), indices.getInt(), indices.getInt(),
                        indices.getInt(), indices.getInt(), indices.getInt()});
    }

    @Test
    void emptyPackIsAllowed() {
        TerrainMeshPacker.Pack pack = TerrainMeshPacker.pack(List.of());
        assertEquals(0, pack.vertices().length);
        assertEquals(0, pack.indices().length);
        assertTrue(pack.slices().isEmpty());
    }

    @Test
    void packingSavesNothingInBytesButRemovesBufferObjects() {
        // Worth stating as a test so nobody expects packing to shrink memory: it does not, it removes bindings.
        List<TerrainMeshPacker.Entry> meshes = List.of(entry(mesh(2, (byte) 1)), entry(mesh(3, (byte) 2)));
        TerrainMeshPacker.Pack pack = TerrainMeshPacker.pack(meshes);

        int separateVertices = meshes.stream().mapToInt(m -> m.mesh().vertices().length).sum();
        assertEquals(separateVertices, pack.vertices().length);
    }
}
