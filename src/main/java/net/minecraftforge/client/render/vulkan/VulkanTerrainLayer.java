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

    /** Draws per frame, so the cost of the packing choice is observable rather than assumed. */
    private int drawCount;
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f view = new Matrix4f();
    private final Matrix4f modelViewProjection = new Matrix4f();

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
        if (!(context.nativeContext() instanceof VkCommandBuffer commandBuffer)) {
            return;
        }
        if (slices.isEmpty()) {
            // Nothing captured yet: draw nothing rather than scaffolding. The node stays registered because the graph
            // requires the phase to exist, but an empty frame from it is the correct output.
            drawCount = 0;
            return;
        }

        // A fixed camera looking at the block from slightly above and to the side, so three faces are visible and
        // the depth test has something to order.
        projection.identity()
                .perspective((float) Math.toRadians(60.0), (float) context.width() / context.height(), 0.1f, 200.0f);
        view.identity()
                .lookAt(6.0f, 5.0f, 6.0f, 1.0f, 0.0f, 1.0f, 0.0f, 1.0f, 0.0f);
        projection.mul(view, modelViewProjection);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // The full push constant block, not just the matrix: the block now also carries the alpha test's two
            // numbers, and pushing a shorter range leaves them undefined for the fragment stage. Undefined there
            // means an arbitrary threshold, which discards arbitrary fragments.
            ByteBuffer matrix = stack.malloc(VulkanPipeline.PUSH_CONSTANT_BYTES);
            matrix.order(ByteOrder.nativeOrder());
            modelViewProjection.get(matrix);
            matrix.putFloat(16 * Float.BYTES, 0.0F);   // alpha reference, unused when the test is off
            matrix.putFloat(16 * Float.BYTES + 4, 0.0F); // alpha test off: the terrain draws every fragment
            matrix.putFloat(16 * Float.BYTES + 8, 0.0F); // no u-flip: the block atlas is uploaded, not copied
            // Same clip-space Y flip the streamed draws apply: this node's matrix is GL-style too, and Vulkan's NDC
            // Y points the other way. Column-major, so row 1 is at 1, 5, 9 and 13.
            java.nio.FloatBuffer flipped = matrix.asFloatBuffer();
            for (int index = 1; index < 16; index += 4) {
                flipped.put(index, -flipped.get(index));
            }

            vkCmdPushConstants(commandBuffer, pipeline.layout(),
                    VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, matrix);
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());

            // The atlas set, resolved before the rendering instance opened. Resolving it here would record a copy
            // inside a render pass, which Vulkan does not allow - the validation layers named that one exactly.
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout(), 0,
                    stack.longs(resolvedTexture), null);

            // Bound once for the whole terrain: this is what packing buys.
            LongBuffer buffer = stack.longs(vertices.handle());
            vkCmdBindVertexBuffers(commandBuffer, 0, buffer, stack.mallocLong(1).put(0, 0L));
            vkCmdBindIndexBuffer(commandBuffer, indices.handle(), 0L, indexType);

            // One draw per chunk. The draw count is unchanged by packing - reducing it needs indirect draws, which
            // should wait until there is real terrain to measure.
            for (TerrainMeshPacker.Slice slice : slices) {
                vkCmdDrawIndexed(commandBuffer, slice.indexCount(), 1, slice.firstIndex(), slice.vertexOffset(), 0);
            }
            drawCount = slices.size();
        }
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
        java.util.List<TerrainMeshBuilder.Mesh> meshes = new java.util.ArrayList<>(captured.size());
        int quads = 0;
        for (ChunkMeshCapture.Captured chunk : captured) {
            // The capture is vanilla's own BLOCK bytes; the builder only splits quads into triangles and adds
            // indices, so this is where the two halves meet.
            TerrainMeshBuilder.Mesh mesh = TerrainMeshBuilder.build(chunk.vertices(), chunk.quadCount());
            meshes.add(mesh);
            quads += chunk.quadCount();
        }

        TerrainMeshPacker.Pack pack = TerrainMeshPacker.pack(meshes);
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
