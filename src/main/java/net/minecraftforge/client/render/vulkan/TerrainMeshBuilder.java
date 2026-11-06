package net.minecraftforge.client.render.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Turns vanilla's quads into indexed triangles.
 *
 * <p><b>The only thing that has to change.</b> Vanilla accumulates {@code GL_QUADS} with {@code glDrawArrays}, and
 * Vulkan has no quad primitive. Everything else about the data is already right: the vertex bytes, the layout, and
 * the baked lighting all pass through untouched (see {@link TerrainVertexFormat}).</p>
 *
 * <p><b>Why index rather than expand.</b> Expanding four vertices into six costs 168 bytes per quad; keeping four
 * and adding six indices costs 124 with 16-bit indices. That is 26% of every chunk's vertex memory, and the index
 * buffer is written once per rebuild. Vertices are not shared between neighbouring quads - vanilla gives each quad
 * its own four, with its own colour - so indexing saves the 4-to-6 expansion and nothing more. That is still the
 * larger half of the saving.</p>
 *
 * <p><b>Winding.</b> GL tessellates a quad {@code 0,1,2,3} as triangles {@code (0,1,2)} and {@code (0,2,3)}. The
 * same order is emitted here, so a mesh that looked right under GL keeps its front faces under Vulkan without the
 * pipeline having to flip its front-face setting.</p>
 */
final class TerrainMeshBuilder {

    /** 65535 is the largest 16-bit index; a mesh with more vertices has to use 32-bit. */
    private static final int MAX_UINT16_VERTICES = 0xFFFF;

    private TerrainMeshBuilder() {}

    /**
     * The result of a build: vertex bytes verbatim, plus indices.
     *
     * @param vertices    {@code quadCount * 4} vertices, {@link TerrainVertexFormat#STRIDE} bytes each
     * @param indices     {@code quadCount * 6} indices, 2 or 4 bytes each depending on {@code index32}
     * @param index32     whether the indices are 32-bit, which the draw call has to be told
     */
    record Mesh(byte[] vertices, byte[] indices, int quadCount, boolean index32) {

        int indexCount() {
            return quadCount * 6;
        }
    }

    /**
     * Builds a mesh from vanilla's quad data.
     *
     * @param quads {@code quadCount * 4 * 28} bytes, in the order vanilla wrote them
     * @param quadCount number of {@code GL_QUADS} quads
     * @throws IllegalArgumentException if {@code quads} is not exactly the expected length
     */
    static Mesh build(byte[] quads, int quadCount) {
        if (quadCount < 0) {
            throw new IllegalArgumentException("negative quad count: " + quadCount);
        }
        int expected = quadCount * TerrainVertexFormat.VERTICES_PER_QUAD * TerrainVertexFormat.STRIDE;
        if (quads.length != expected) {
            throw new IllegalArgumentException("expected " + expected + " bytes for " + quadCount + " quads ("
                    + TerrainVertexFormat.VERTICES_PER_QUAD + " vertices x " + TerrainVertexFormat.STRIDE
                    + " bytes), got " + quads.length);
        }

        int vertexCount = quadCount * TerrainVertexFormat.VERTICES_PER_QUAD;
        boolean index32 = vertexCount > MAX_UINT16_VERTICES;
        int indexBytes = index32 ? Integer.BYTES : Short.BYTES;

        // The vertex bytes are the input: no copy, no reordering, no conversion.
        byte[] vertices = quads.clone();

        ByteBuffer indices = ByteBuffer.allocate(quadCount * 6 * indexBytes).order(ByteOrder.nativeOrder());
        for (int quad = 0; quad < quadCount; quad++) {
            int base = quad * TerrainVertexFormat.VERTICES_PER_QUAD;
            // GL_QUADS tessellation: (0,1,2) then (0,2,3).
            putIndex(indices, base, index32);
            putIndex(indices, base + 1, index32);
            putIndex(indices, base + 2, index32);
            putIndex(indices, base, index32);
            putIndex(indices, base + 2, index32);
            putIndex(indices, base + 3, index32);
        }

        return new Mesh(vertices, indices.array(), quadCount, index32);
    }

    private static void putIndex(ByteBuffer target, int value, boolean index32) {
        if (index32) {
            target.putInt(value);
        } else {
            target.putShort((short) value);
        }
    }
}
