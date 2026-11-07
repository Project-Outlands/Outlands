package net.minecraftforge.client.render.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Packs many chunk meshes into one vertex buffer and one index buffer.
 *
 * <p><b>Why.</b> Vanilla gives every chunk one buffer per render layer. At a render distance of 16 that is over a
 * thousand buffers, and every draw rebinds one. Packing them into a single buffer turns a rebind into an offset:
 * the same one buffer stays bound, and each chunk is one {@code vkCmdDrawIndexed(firstIndex, ...)}.</p>
 *
 * <p><b>What this does not do.</b> It does not reduce the number of draw calls - that still takes indirect draws,
 * and indirect draws should wait until there is real terrain and a measured cost to justify them. Packing removes
 * the per-chunk buffer object and the rebind, which is the part that is certainly wasted.</p>
 *
 * <p><b>Index widening.</b> Vanilla's indices are per chunk, so each chunk fits in 16 bits. Packed, the index space
 * is shared, so a pack that exceeds 65535 vertices has to widen every index to 32 bits. The widening happens once
 * per pack rather than per chunk, and {@link Pack#index32()} tells the draw call which type to bind.</p>
 *
 * <p><b>Vertex offsets, not rebased indices.</b> Indices stay as the mesh builder emitted them, relative to their
 * own chunk; the chunk's base vertex is passed as {@code vertexOffset} in the draw call. Rewriting every index on
 * every pack would be a full pass over the index data for a value the draw call already takes.</p>
 */
final class TerrainMeshPacker {

    /**
     * A chunk's slice of the packed buffers.
     *
     * @param firstIndex  where this chunk's indices start in the packed index buffer
     * @param indexCount  how many there are
     * @param vertexOffset the chunk's base vertex, passed as {@code vertexOffset} by the draw call
     * @param originX     the chunk's world x, which the vertex positions are relative to
     * @param originY     the chunk's world y
     * @param originZ     the chunk's world z
     * @param layer       the render layer's ordinal, so a slice is drawn by the layer it belongs to
     */
    record Slice(int firstIndex, int indexCount, int vertexOffset, int originX, int originY, int originZ,
                 int layer) {}

    /**
     * One chunk's mesh, with where it sits and which layer it belongs to.
     *
     * <p>The origin is needed because vanilla's chunk vertices are written relative to the chunk
     * ({@code BufferBuilder.setTranslation(-x, -y, -z)} in {@code RenderChunk.preRenderBlocks}), and the per-chunk
     * translation that puts them back in the world is applied to the matrix at draw time. Packing loses that
     * translation, so it travels with the slice instead.</p>
     */
    record Entry(TerrainMeshBuilder.Mesh mesh, int originX, int originY, int originZ, int layer) {}

    /**
     * The packed buffers and where each chunk landed.
     *
     * @param vertices   all chunks' vertices, concatenated, {@link TerrainVertexFormat#STRIDE} bytes each
     * @param indices    all chunks' indices, widened if {@code index32}
     * @param slices     one entry per chunk, in the order they were packed
     * @param index32    whether the indices are 32-bit
     */
    record Pack(byte[] vertices, byte[] indices, List<Slice> slices, boolean index32) {}

    private TerrainMeshPacker() {}

    /** Packs the meshes in the given order. Slices are returned in the same order. */
    static Pack pack(List<Entry> entries) {
        int totalVertices = 0;
        int totalIndices = 0;
        for (Entry entry : entries) {
            TerrainMeshBuilder.Mesh mesh = entry.mesh();
            totalVertices += mesh.vertices().length / TerrainVertexFormat.STRIDE;
            totalIndices += mesh.indexCount();
        }
        // The whole pack shares one index space, so one mesh that needs 32 bits forces the whole pack to.
        boolean index32 = totalVertices > 0xFFFF;

        byte[] vertices = new byte[totalVertices * TerrainVertexFormat.STRIDE];
        ByteBuffer indices = ByteBuffer.allocate(totalIndices * (index32 ? Integer.BYTES : Short.BYTES))
                .order(ByteOrder.nativeOrder());
        List<Slice> slices = new ArrayList<>(entries.size());

        int vertexBase = 0;
        int indexBase = 0;
        for (Entry entry : entries) {
            TerrainMeshBuilder.Mesh mesh = entry.mesh();
            int vertexCount = mesh.vertices().length / TerrainVertexFormat.STRIDE;
            System.arraycopy(mesh.vertices(), 0, vertices, vertexBase * TerrainVertexFormat.STRIDE,
                    mesh.vertices().length);

            // Indices keep their chunk-relative values; the draw call supplies vertexOffset.
            int meshIndexCount = mesh.indexCount();
            ByteBuffer source = ByteBuffer.wrap(mesh.indices()).order(ByteOrder.nativeOrder());
            for (int i = 0; i < meshIndexCount; i++) {
                int index = mesh.index32() ? source.getInt() : source.getShort() & 0xFFFF;
                if (index32) {
                    indices.putInt(index);
                } else {
                    indices.putShort((short) index);
                }
            }

            slices.add(new Slice(indexBase, meshIndexCount, vertexBase, entry.originX(), entry.originY(),
                    entry.originZ(), entry.layer()));
            vertexBase += vertexCount;
            indexBase += meshIndexCount;
        }

        return new Pack(vertices, indices.array(), List.copyOf(slices), index32);
    }
}
