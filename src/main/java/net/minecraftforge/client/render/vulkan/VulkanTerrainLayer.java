package net.minecraftforge.client.render.vulkan;

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

import static org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT16;
import static org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT32;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_GRAPHICS;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdBindIndexBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdBindPipeline;
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

    private final VulkanPipeline pipeline;
    private final VulkanBuffer vertices;
    private final VulkanBuffer indices;
    private final int indexCount;
    private final int indexType;
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f view = new Matrix4f();
    private final Matrix4f modelViewProjection = new Matrix4f();

    private VulkanTerrainLayer(VulkanPipeline pipeline, VulkanBuffer vertices, VulkanBuffer indices,
                               TerrainMeshBuilder.Mesh mesh) {
        this.pipeline = pipeline;
        this.vertices = vertices;
        this.indices = indices;
        this.indexCount = mesh.indexCount();
        this.indexType = mesh.index32() ? VK_INDEX_TYPE_UINT32 : VK_INDEX_TYPE_UINT16;
    }

    static VulkanTerrainLayer create(VulkanContext context, int colorFormat, int depthFormat) {
        VkDevice device = context.device();
        VulkanShader vertex = VulkanShader.load(device, "/assets/outlands/shaders/terrain.vert.spv");
        VulkanShader fragment = VulkanShader.load(device, "/assets/outlands/shaders/terrain.frag.spv");
        VulkanPipeline pipeline;
        try {
            pipeline = VulkanPipeline.create(device, vertex, fragment, colorFormat, depthFormat);
        } finally {
            // The module is only needed to build the pipeline; the pipeline owns the compiled code from then on.
            vertex.close();
            fragment.close();
        }

        TerrainMeshBuilder.Mesh mesh = geometry();
        VulkanBuffer vertices;
        VulkanBuffer indices;
        try {
            vertices = VulkanBuffer.create(device, context, mesh.vertices());
            indices = VulkanBuffer.create(device, context, mesh.indices());
        } catch (RuntimeException e) {
            pipeline.close();
            throw e;
        }
        return new VulkanTerrainLayer(pipeline, vertices, indices, mesh);
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

        // A fixed camera looking at the block from slightly above and to the side, so three faces are visible and
        // the depth test has something to order.
        projection.identity()
                .perspective((float) Math.toRadians(60.0), (float) context.width() / context.height(), 0.1f, 100.0f);
        view.identity()
                .lookAt(2.4f, 1.8f, 2.4f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f);
        projection.mul(view, modelViewProjection);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer matrix = stack.malloc(16 * Float.BYTES);
            matrix.order(ByteOrder.nativeOrder());
            modelViewProjection.get(matrix);

            vkCmdPushConstants(commandBuffer, pipeline.layout(), VK_SHADER_STAGE_VERTEX_BIT, 0, matrix);
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());

            LongBuffer buffer = stack.longs(vertices.handle());
            vkCmdBindVertexBuffers(commandBuffer, 0, buffer, stack.mallocLong(1).put(0, 0L));
            vkCmdBindIndexBuffer(commandBuffer, indices.handle(), 0L, indexType);
            vkCmdDrawIndexed(commandBuffer, indexCount, 1, 0, 0, 0);
        }
    }

    @Override
    public void close() {
        indices.close();
        vertices.close();
        pipeline.close();
    }

    /**
     * A cube of six quads, written in vanilla's format.
     *
     * <p>The colours are the shade vanilla's diffuse term would have baked in for each facing, so the picture is
     * what vanilla's own lighting would produce for a block lit from above. The lightmap is full daylight in both
     * channels, which leaves the vertex colour as the only thing varying - so a wrong colour offset or a wrong
     * stride shows up immediately.</p>
     */
    private static TerrainMeshBuilder.Mesh geometry() {
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
        float[][] tint = {
                {0.55f * 0.62f, 0.42f * 0.62f, 0.30f * 0.62f},
                {0.55f * 0.62f, 0.42f * 0.62f, 0.30f * 0.62f},
                {0.35f * 1.00f, 0.62f * 1.00f, 0.28f * 1.00f},
                {0.42f * 0.50f, 0.32f * 0.50f, 0.22f * 0.50f},
                {0.55f * 0.80f, 0.42f * 0.80f, 0.30f * 0.80f},
                {0.55f * 0.80f, 0.42f * 0.80f, 0.30f * 0.80f},
        };

        int quadCount = corners.length;
        ByteBuffer quads = ByteBuffer.allocate(quadCount * 4 * TerrainVertexFormat.STRIDE)
                .order(ByteOrder.nativeOrder());
        for (int face = 0; face < quadCount; face++) {
            for (int corner = 0; corner < 4; corner++) {
                float[] position = corners[face][corner];
                quads.putFloat(position[0]).putFloat(position[1]).putFloat(position[2]);
                // R8G8B8A8_UNORM: the colour vanilla's putColorMultiplier would have left here.
                quads.put((byte) Math.round(tint[face][0] * 255.0f));
                quads.put((byte) Math.round(tint[face][1] * 255.0f));
                quads.put((byte) Math.round(tint[face][2] * 255.0f));
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
