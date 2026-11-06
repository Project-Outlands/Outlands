package net.minecraftforge.client.render.vulkan;

/**
 * The vertex layout vanilla's terrain already produces, and the pipeline consumes unchanged.
 *
 * <p><b>Nothing is converted.</b> {@code DefaultVertexFormats.BLOCK} is 28 bytes: position, colour, texture UV and
 * lightmap UV. That is exactly what the terrain shader wants, so the mesh builder only splits quads into triangles
 * and adds indices - the vertex bytes pass through verbatim. Writing a second vertex format here would mean a
 * per-vertex conversion pass over every chunk for no gain.</p>
 *
 * <p><b>There is no normal.</b> Vanilla bakes its directional shading and ambient occlusion into the vertex colour
 * before the vertex is ever written - that is what {@code BufferBuilder.putColorMultiplier} is doing, four times
 * per quad. So the shader multiplies colour by lightmap and has nothing to dot-product. A normal attribute would
 * be a third source of truth for the same information, and it would have to be invented during conversion because
 * vanilla never stores one.</p>
 *
 * <p>Offsets are the ones {@code VboRenderList.setupArrayPointers} passes to GL, so this is not an interpretation
 * of the format - it is the format, written down once for the code that reads it.</p>
 */
final class TerrainVertexFormat {

    /** Bytes per vertex. */
    static final int STRIDE = 28;

    static final int POSITION_OFFSET = 0;
    static final int COLOR_OFFSET = 12;
    static final int UV_OFFSET = 16;
    static final int LIGHTMAP_OFFSET = 24;

    /** Vertices per quad. Vanilla draws {@code GL_QUADS}; Vulkan has no such primitive. */
    static final int VERTICES_PER_QUAD = 4;

    private TerrainVertexFormat() {}
}
