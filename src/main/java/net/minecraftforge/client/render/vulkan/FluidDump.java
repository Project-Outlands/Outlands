package net.minecraftforge.client.render.vulkan;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Writes a chunk layer's quads as the game produced them, before the compact packing.
 *
 * <p><b>Why this exists.</b> A seam one pixel wide between two adjacent fluid blocks cannot be argued about: either the
 * two quads' shared edge is identical in the vertices the game emitted - in which case the seam is not geometry and
 * the picture is being made by sampling or shading - or it is not, and the numbers say by how much. Guessing at that
 * from a screenshot wasted a session; a hundred lines of coordinates ends it.</p>
 *
 * <p>Off unless asked for ({@code -Doutlands.debug.fluidDump=true}), and bounded: eight meshes, five hundred quads.
 * The translucent layer is the one dumped because fluids are the only geometry that is asked about this way, and the
 * raw layout is the game's own ({@code DefaultVertexFormats.BLOCK}, 28 bytes a vertex), not the compact one, so the
 * numbers can be compared against what vanilla's own buffers hold.</p>
 */
public final class FluidDump {

    private static final boolean ENABLED = Boolean.getBoolean("outlands.debug.fluidDump");
    private static final int MAX_MESHES = 8;
    private static final int MAX_QUADS = 500;

    private static int meshes;
    private static int quads;

    private FluidDump() {
    }

    /**
     * Dumps the quads of one mesh.
     *
     * @param layer      the render layer; only the translucent one is dumped
     * @param source     the game's buffer, positioned at zero, {@code DefaultVertexFormats.BLOCK} stride
     * @param quadCount  how many quads the buffer holds
     * @param chunkX     the section's position, so a quad's world place can be worked out
     * @param chunkY     the section's y
     * @param chunkZ     the section's z
     */
    public static void maybeDump(net.minecraft.util.BlockRenderLayer layer, ByteBuffer source, int quadCount,
                          int chunkX, int chunkY, int chunkZ) {
        if (!ENABLED || layer != net.minecraft.util.BlockRenderLayer.TRANSLUCENT
                || meshes >= MAX_MESHES || quadCount == 0) {
            return;
        }
        meshes++;
        int stride = TerrainVertexFormat.RAW_STRIDE;
        StringBuilder out = new StringBuilder(quadCount * 4 * 60);
        out.append("# mesh ").append(meshes).append(" section ").append(chunkX).append(',').append(chunkY)
                .append(',').append(chunkZ).append(" layer ").append(layer).append(", ").append(quadCount)
                .append(" quads, raw BLOCK layout\n");
        out.append("# quad vertex x y z r g b a u v blockLight skyLight\n");
        int limit = Math.min(quadCount, MAX_QUADS - quads);
        for (int q = 0; q < limit; q++) {
            for (int v = 0; v < 4; v++) {
                int at = (q * 4 + v) * stride;
                if (at + stride > source.capacity()) {
                    break;
                }
                float x = source.getFloat(at + TerrainVertexFormat.RAW_POSITION_OFFSET);
                float y = source.getFloat(at + TerrainVertexFormat.RAW_POSITION_OFFSET + 4);
                float z = source.getFloat(at + TerrainVertexFormat.RAW_POSITION_OFFSET + 8);
                int colour = source.getInt(at + TerrainVertexFormat.RAW_COLOR_OFFSET);
                float u = source.getFloat(at + TerrainVertexFormat.RAW_UV_OFFSET);
                float w = source.getFloat(at + TerrainVertexFormat.RAW_UV_OFFSET + 4);
                int light = source.getShort(at + TerrainVertexFormat.RAW_LIGHTMAP_OFFSET) & 0xFFFF;
                int sky = source.getShort(at + TerrainVertexFormat.RAW_LIGHTMAP_OFFSET + 2) & 0xFFFF;
                out.append(q).append(' ').append(v)
                        .append(' ').append(x).append(' ').append(y).append(' ').append(z)
                        .append(' ').append((colour >> 16) & 0xFF).append(' ').append((colour >> 8) & 0xFF)
                        .append(' ').append(colour & 0xFF).append(' ').append((colour >>> 24) & 0xFF)
                        .append(' ').append(u).append(' ').append(w)
                        .append(' ').append(light).append(' ').append(sky).append('\n');
            }
        }
        quads += limit;
        try {
            Files.writeString(Path.of("logs", "outlands-fluid-dump.txt"), out,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
