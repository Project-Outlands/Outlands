package net.minecraftforge.client.render.vulkan;

import net.minecraftforge.client.render.ChunkMeshCapture;
import net.minecraftforge.client.render.graph.RenderGraphContext;
import net.minecraftforge.client.render.graph.RenderGraphNode;
import net.minecraftforge.client.render.graph.RenderPhases;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT16;
import static org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT32;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_GRAPHICS;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdBindIndexBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdBindPipeline;
import static org.lwjgl.vulkan.VK10.vkCmdBindDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCmdBindVertexBuffers;
import static org.lwjgl.vulkan.VK10.vkCmdDrawIndexed;
import static org.lwjgl.vulkan.VK10.vkCmdPushConstants;

/**
 * The {@code terrain.layer} phase.
 *
 * <p><b>What this proves.</b> That a SPIR-V shader loaded from the classpath, a pipeline built for vanilla's vertex
 * layout, quads converted to indexed triangles, and a pushed matrix produce the expected picture inside the frame
 * the backend owns.</p>
 *
 * <p><b>The geometry is placeholder, the format is not.</b> The vertices here are written exactly as vanilla writes
 * them - {@link TerrainVertexFormat#STRIDE} bytes, position then colour then texture UV then lightmap UV - and go
 * through the same {@link TerrainMeshBuilder} that chunk data will. So the picture shows the format and the
 * conversion working, not a shader effect that would hide a wrong offset.</p>
 *
 * <p><b>No normal, because vanilla has none.</b> Directional shading and ambient occlusion are baked into the
 * vertex colour before the vertex is written; the lightmap coordinate carries the remaining light. The shader
 * multiplies the two.</p>
 */
final class VulkanTerrainLayer implements RenderGraphNode, AutoCloseable {

    private final VkDevice device;
    private final VulkanContext context;
    private final VulkanPipeline pipeline;
    private final VulkanTextures textures;
    private VulkanBuffer vertices;
    private VulkanBuffer indices;
    private List<TerrainMeshPacker.Slice> slices;
    private int indexType;

    /** Where the current buffers came from: null while the placeholder is showing. */
    private String source;

    /** Draws the last frame issued, so the cost of the packing choice is observable rather than assumed. */
    private int drawCount;
    /** Scratch for the per-chunk matrix: the camera it is multiplied into arrives per layer, per frame. */
    private final Matrix4f chunkMatrix = new Matrix4f();
    /** Scratch for the camera the frame hands over, so a layer draw allocates nothing. */
    private final java.nio.FloatBuffer cameraScratch = java.nio.ByteBuffer.allocateDirect(64)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();

    private VulkanTerrainLayer(VkDevice device, VulkanContext context, VulkanPipeline pipeline,
                               VulkanTextures textures, VulkanBuffer vertices, VulkanBuffer indices,
                               TerrainMeshPacker.Pack pack, String source) {
        this.device = device;
        this.context = context;
        this.pipeline = pipeline;
        this.textures = textures;
        this.vertices = vertices;
        this.indices = indices;
        this.slices = pack.slices();
        this.indexType = pack.index32() ? VK_INDEX_TYPE_UINT32 : VK_INDEX_TYPE_UINT16;
        this.source = source;
    }

    /** How many indexed draws the last frame issued. */
    int drawCount() {
        return drawCount;
    }

    /** What the current mesh came from, for diagnostics. */
    String source() {
        return source;
    }

    static VulkanTerrainLayer create(VulkanContext context, int colorFormat, int depthFormat,
                                     VulkanDescriptors descriptors, VulkanTextures textures) {
        VkDevice device = context.device();
        VulkanShader vertex = VulkanShader.load(device, "/assets/outlands/shaders/terrain.vert.spv");
        VulkanShader fragment = VulkanShader.load(device, "/assets/outlands/shaders/terrain.frag.spv");
        VulkanPipeline pipeline;
        try {
            // The terrain shader samples the block atlas exactly like a streamed draw does, so it needs the same
            // descriptor set layout and takes the texture through the same cache.
            pipeline = VulkanPipeline.create(device, vertex, fragment, colorFormat, depthFormat,
                    descriptors.pipelineLayout(), descriptors.layout(), VulkanPipeline.PipelineState.terrain());
        } finally {
            // The module is only needed to build the pipeline; the pipeline owns the compiled code from then on.
            vertex.close();
            fragment.close();
        }

        // Every mesh packed into one vertex buffer and one index buffer: the buffer object and the rebind per
        // chunk disappear, and a chunk becomes an offset into a buffer that stays bound.
        // No placeholder geometry.
        //
        // The 4x1x4 grid of tinted cubes was scaffolding for the first pipeline: it proved the vertex format, the
        // packing and the shader before there was any chunk data. It has no place in a working renderer, and while it
        // was here it was not merely invisible - the main menu draws its panorama into a 256x256 corner of the frame,
        // so the placeholder showed through everywhere the menu's own background did not cover, and the blur pass
        // copied the mixture into the texture it then stretched across the screen. A renderer that draws a fake block
        // behind the menu is worse than one that draws nothing.
        TerrainMeshPacker.Pack pack = TerrainMeshPacker.pack(java.util.List.of());
        VulkanBuffer vertices = null;
        VulkanBuffer indices = null;
        try {
            // A zero-length buffer is not a thing Vulkan can create, and asking for one takes the driver down
            // rather than returning an error - measured as a segfault inside libvulkan_radeon. An empty mesh
            // therefore allocates nothing at all, which is also why the buffers are nullable from here on.
            if (pack.vertices().length > 0) {
                vertices = VulkanBuffer.create(device, context, pack.vertices());
                indices = VulkanBuffer.create(device, context, pack.indices());
            }
        } catch (RuntimeException e) {
            pipeline.close();
            if (vertices != null) {
                vertices.close();
            }
            throw e;
        }
        return new VulkanTerrainLayer(device, context, pipeline, textures, vertices, indices, pack, "none");
    }

    /** The descriptor set for the atlas, resolved by the frame before the rendering instance opened. */
    private long resolvedTexture;

    /** Records which set to bind. Called once per frame, before recording starts drawing. */
    @Override
    public void bindTextures(long descriptorSet) {
        this.resolvedTexture = descriptorSet;
    }

    /**
     * The texture handle this node samples, so the frame can resolve it alongside the draw list's textures.
     *
     * <p>The block atlas: the captured chunk meshes carry vanilla's own atlas coordinates, so anything else would
     * sample the right UVs from the wrong image.</p>
     */
    @Override
    public int requestedTexture() {
        return atlasTexture();
    }

    /**
     * The block atlas's handle, or {@code 0} before the atlas exists.
     *
     * <p>Read per frame rather than cached: the texture manager recreates the atlas on a resource reload, and holding
     * the old handle would sample an image the game has since deleted. Asking is cheap - it is a field read on the
     * texture map.</p>
     */
    private static int atlasTexture() {
        net.minecraft.client.renderer.texture.TextureMap map =
                net.minecraft.client.Minecraft.getMinecraft().getTextureMapBlocks();
        return map == null ? 0 : map.getGlTextureId();
    }

    @Override
    public String label() {
        return RenderPhases.TERRAIN_LAYER;
    }

    @Override
    public void run(RenderGraphContext context) {
        // This node draws nothing, on purpose.
        //
        // Terrain is not a phase that runs at a fixed point in the frame: vanilla issues each render layer between the
        // sky and the entities, with the camera matrix of that moment, and the translucent layer sorted back to front.
        // A node that runs before every recorded draw puts terrain underneath the sky, and one that runs after puts it
        // on top of the entities. So the frame's own sequence is where the layers belong, and this node is what that
        // sequence calls - see recordLayer. The phase stays registered because it is the answer to "who draws terrain",
        // and because a graph takeover will eventually move the draw here once phases own passes instead of draws.
        drawCount = 0;
    }

    /**
     * Records one render layer's chunks, at the point in the frame where the game asked for that layer.
     *
     * @param commandBuffer the frame's command buffer, with a rendering instance already open
     * @param cameraMatrix  the projection times modelview the game had in effect, column-major, GL convention
     * @param layer         the {@code BlockRenderLayer} ordinal whose chunks to draw
     */
    void recordLayer(VkCommandBuffer commandBuffer, float[] cameraMatrix, int layer) {
        if (vertices == null || indices == null || slices.isEmpty()) {
            return;
        }
        int drawn = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
            // The atlas set, resolved by the frame before this instance opened: resolving it here would record a copy
            // inside a render pass, which Vulkan does not allow - the validation layers named that one exactly.
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout(), 0,
                    stack.longs(resolvedTexture), null);
            // Bound once for the whole layer: this is what packing buys.
            vkCmdBindVertexBuffers(commandBuffer, 0, stack.longs(vertices.handle()), stack.mallocLong(1).put(0, 0L));
            vkCmdBindIndexBuffer(commandBuffer, indices.handle(), 0L, indexType);

            cameraScratch.clear();
            cameraScratch.put(cameraMatrix);
            cameraScratch.position(0);

            ByteBuffer constants = stack.malloc(VulkanPipeline.PUSH_CONSTANT_BYTES);
            constants.order(java.nio.ByteOrder.nativeOrder());

            // Vanilla's cutout layers discard fragments below an alpha threshold and the others do not; that is the
            // only difference between the two pipeline states here, and it goes in the push constant block.
            boolean alphaTest = layer == net.minecraft.util.BlockRenderLayer.CUTOUT_MIPPED.ordinal()
                    || layer == net.minecraft.util.BlockRenderLayer.CUTOUT.ordinal();

            boolean firstLayerReported = false;
            for (TerrainMeshPacker.Slice slice : slices) {
                if (slice.layer() != layer) {
                    continue;
                }
                if (!firstLayerReported && Boolean.getBoolean("outlands.render.debug")) {
                    firstLayerReported = true;
                    org.apache.logging.log4j.LogManager.getLogger("Outlands|render").info(
                            "terrain layer {}: {} of {} slices, first origin {},{} {}", layer, drawn, slices.size(),
                            slice.originX(), slice.originY(), slice.originZ());
                }
                // The chunk's vertices are relative to its origin, because vanilla built them that way and put the
                // translation on the matrix - the same thing it did per chunk in preRenderChunk.
                chunkMatrix.set(cameraScratch).translate(slice.originX(), slice.originY(), slice.originZ());
                writeConstants(constants, chunkMatrix, alphaTest);
                vkCmdPushConstants(commandBuffer, pipeline.layout(),
                        VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, constants);
                vkCmdDrawIndexed(commandBuffer, slice.indexCount(), 1, slice.firstIndex(), slice.vertexOffset(), 0);
                drawn++;
            }
        }
        drawCount = drawn;
    }

    /**
     * Fills the push constant block from a chunk's matrix.
     *
     * <p>Same block as the streamed draws, including the clip-space Y flip: GL's projection has +Y up and Vulkan's NDC
     * has it down, and the flip is applied to the matrix rather than by negating the viewport so both paths agree.</p>
     */
    private static void writeConstants(ByteBuffer constants, Matrix4f matrix, boolean alphaTest) {
        constants.clear();
        matrix.get(constants);
        // Column-major, so row 1 is at 1, 5, 9 and 13.
        java.nio.FloatBuffer floats = constants.asFloatBuffer();
        for (int index = 1; index < 16; index += 4) {
            floats.put(index, -floats.get(index));
        }
        floats.put(16, 0.1F);                          // alpha reference: vanilla's cutout threshold
        floats.put(17, alphaTest ? 1.0F : 0.0F);       // the test itself
        floats.put(18, 0.0F);                          // no u-flip: the atlas is uploaded, not copied
        // Back to the start: Matrix4f.get left the ByteBuffer's position at its end, and every vkCmd* takes its
        // address as base + position. Leaving it there submits the block from 64 bytes past the matrix, which reads
        // stack garbage into the shader's matrix - measured as terrain drawn in the wrong place rather than an error.
        constants.position(0);
    }

    /**
     * Replaces the mesh with real chunk data.
     *
     * <p>Called from the render thread with whatever {@link ChunkMeshCapture} collected since the last frame. The
     * whole terrain is one packed buffer, so this rebuilds it: incremental updates would need per-chunk slices that
     * survive repacking, which is worth doing when the rebuild cost is measured rather than assumed.</p>
     */
    void useCaptured(java.util.List<ChunkMeshCapture.Captured> captured) {
        if (captured.isEmpty()) {
            return;
        }
        java.util.List<TerrainMeshPacker.Entry> entries = new java.util.ArrayList<>(captured.size());
        int quads = 0;
        for (ChunkMeshCapture.Captured chunk : captured) {
            // The capture is vanilla's own BLOCK bytes; the builder only splits quads into triangles and adds
            // indices, so this is where the two halves meet.
            TerrainMeshBuilder.Mesh mesh = TerrainMeshBuilder.build(chunk.vertices(), chunk.quadCount());
            entries.add(new TerrainMeshPacker.Entry(mesh, chunk.chunkX(), chunk.chunkY(), chunk.chunkZ(),
                    chunk.layer().ordinal()));
            quads += chunk.quadCount();
        }

        TerrainMeshPacker.Pack pack = TerrainMeshPacker.pack(entries);
        VulkanBuffer nextVertices = null;
        VulkanBuffer nextIndices = null;
        if (pack.vertices().length > 0) {
            // Same rule as construction: a zero-length buffer is not creatable, and asking takes the driver down.
            nextVertices = VulkanBuffer.create(device, context, pack.vertices());
            try {
                nextIndices = VulkanBuffer.create(device, context, pack.indices());
            } catch (RuntimeException e) {
                nextVertices.close();
                throw e;
            }
        }

        VulkanBuffer oldVertices = vertices;
        VulkanBuffer oldIndices = indices;
        vertices = nextVertices;
        indices = nextIndices;
        slices = pack.slices();
        indexType = pack.index32() ? VK_INDEX_TYPE_UINT32 : VK_INDEX_TYPE_UINT16;
        source = captured.size() + " chunk layers, " + quads + " quads";
        // The old buffers may still be in flight; the device is idle at this point in the frame, before recording.
        if (oldIndices != null) {
            oldIndices.close();
        }
        if (oldVertices != null) {
            oldVertices.close();
        }
    }

    @Override
    public void close() {
        if (indices != null) {
            indices.close();
        }
        if (vertices != null) {
            vertices.close();
        }
        pipeline.close();
    }

    /**
     * Several cubes, one mesh each, so the packing is actually exercised.
     *
     * <p>One cube would pass with every slice pointing at the same place. A grid of them makes a wrong
     * {@code firstIndex} or {@code vertexOffset} draw the wrong cube's geometry in the wrong position, which is
     * visible immediately.</p>
     */
    private static List<TerrainMeshBuilder.Mesh> meshes() {
        List<TerrainMeshBuilder.Mesh> meshes = new ArrayList<>();
        // A 4x1x4 grid of unit cubes spaced apart, each with a slightly different tint so they are distinguishable.
        for (int x = -1; x <= 2; x++) {
            for (int z = -1; z <= 2; z++) {
                meshes.add(cube(x * 2.2f, 0.0f, z * 2.2f, 0.8f + 0.05f * x, 0.8f, 0.8f - 0.05f * z));
            }
        }
        return meshes;
    }

    /**
     * A cube of six quads, written in vanilla's format.
     *
     * <p>The colours are the shade vanilla's diffuse term would have baked in for each facing, so the picture is
     * what vanilla's own lighting would produce for a block lit from above. The lightmap is full daylight in both
     * channels, which leaves the vertex colour as the only thing varying - so a wrong colour offset or a wrong
     * stride shows up immediately.</p>
     */
    private static TerrainMeshBuilder.Mesh cube(float offsetX, float offsetY, float offsetZ,
                                                float tintR, float tintG, float tintB) {
        // Per face: four corners, counter-clockwise seen from outside.
        float[][][] corners = {
                {{1, -1, -1}, {1, -1, 1}, {1, 1, 1}, {1, 1, -1}},     // +X
                {{-1, -1, 1}, {-1, -1, -1}, {-1, 1, -1}, {-1, 1, 1}}, // -X
                {{-1, 1, -1}, {1, 1, -1}, {1, 1, 1}, {-1, 1, 1}},     // +Y
                {{-1, -1, 1}, {1, -1, 1}, {1, -1, -1}, {-1, -1, -1}}, // -Y
                {{-1, -1, 1}, {-1, 1, 1}, {1, 1, 1}, {1, -1, 1}},     // +Z
                {{1, -1, -1}, {1, 1, -1}, {-1, 1, -1}, {-1, -1, -1}}, // -Z
        };
        // Vanilla's diffuse factors per facing, folded into a grass-top / dirt-side tint.
        // Per-face diffuse factor, the one vanilla's renderer bakes in: +Y 1.0, +/-Z 0.8, +/-X 0.6, -Y 0.5.
        float[] diffuse = {0.62f, 0.62f, 1.00f, 0.50f, 0.80f, 0.80f};

        int quadCount = corners.length;
        ByteBuffer quads = ByteBuffer.allocate(quadCount * 4 * TerrainVertexFormat.STRIDE)
                .order(ByteOrder.nativeOrder());
        for (int face = 0; face < quadCount; face++) {
            for (int corner = 0; corner < 4; corner++) {
                float[] position = corners[face][corner];
                quads.putFloat(position[0] + offsetX).putFloat(position[1] + offsetY).putFloat(position[2] + offsetZ);
                // R8G8B8A8_UNORM: the colour vanilla's putColorMultiplier would have left here.
                quads.put((byte) Math.round(tintR * diffuse[face] * 255.0f));
                quads.put((byte) Math.round(tintG * diffuse[face] * 255.0f));
                quads.put((byte) Math.round(tintB * diffuse[face] * 255.0f));
                quads.put((byte) 255);
                // R32G32_SFLOAT: a unit quad of the atlas, until the atlas is bound.
                quads.putFloat(corner == 1 || corner == 2 ? 1.0f : 0.0f);
                quads.putFloat(corner >= 2 ? 1.0f : 0.0f);
                // R16G16_UNORM: full daylight in both channels.
                quads.putShort((short) 0xFFFF).putShort((short) 0xFFFF);
            }
        }
        return TerrainMeshBuilder.build(quads.array(), quadCount);
    }
}
