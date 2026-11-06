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
 *
 * <p><b>Component types, because offsets alone are not enough.</b> {@code DefaultVertexFormats.BLOCK} is
 * {@code POSITION_3F + COLOR_4UB + TEX_2F + TEX_2S}, and getting a type wrong is silent - the bytes are still
 * there, they just mean something else. Verified against the real producer in {@code VanillaTerrainDataTest}.</p>
 *
 * <table>
 *   <caption>Attribute layout</caption>
 *   <tr><th>offset</th><th>size</th><th>vanilla element</th><th>Vulkan format</th></tr>
 *   <tr><td>0</td><td>12</td><td>{@code POSITION_3F}</td><td>{@code R32G32B32_SFLOAT}</td></tr>
 *   <tr><td>12</td><td>4</td><td>{@code COLOR_4UB}</td><td>{@code R8G8B8A8_UNORM}</td></tr>
 *   <tr><td>16</td><td>8</td><td>{@code TEX_2F}</td><td>{@code R32G32_SFLOAT}</td></tr>
 *   <tr><td>24</td><td>4</td><td>{@code TEX_2S}</td><td>{@code R16G16_UNORM}</td></tr>
 * </table>
 *
 * <p><b>The lightmap components are block light then sky light.</b> {@code BufferBuilder.lightmap(skyLight,
 * blockLight)} writes {@code blockLight} to the first short and {@code skyLight} to the second - the opposite of
 * its own argument order. Reading it as (sky, block) is a one-character mistake that no offset check catches,
 * which is why the order is written down here as well as in the shader.</p>
 */
final class TerrainVertexFormat {

    /** Bytes per vertex. */
    static final int STRIDE = 28;

    static final int POSITION_OFFSET = 0;
    static final int COLOR_OFFSET = 12;
    static final int UV_OFFSET = 16;
    static final int LIGHTMAP_OFFSET = 24;

    /** Colour is four unsigned bytes, not four floats. A float read spans colour and half of the UV. */
    static final int COLOR_BYTES = 4;
    /** Lightmap is two unsigned shorts: block light first, sky light second. */
    static final int LIGHTMAP_BYTES = 4;

    /** Vertices per quad. Vanilla draws {@code GL_QUADS}; Vulkan has no such primitive. */
    static final int VERTICES_PER_QUAD = 4;

    private TerrainVertexFormat() {}
}
