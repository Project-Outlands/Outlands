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
import static org.lwjgl.vulkan.VK10.vkCmdBindIndexBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdBindPipeline;
import static org.lwjgl.vulkan.VK10.vkCmdBindVertexBuffers;
import static org.lwjgl.vulkan.VK10.vkCmdDrawIndexed;
import static org.lwjgl.vulkan.VK10.vkCmdPushConstants;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_GRAPHICS;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;

/**
 * The {@code terrain.layer} phase, drawing placeholder geometry.
 *
 * <p><b>What this proves.</b> That a SPIR-V shader loaded from the classpath, a pipeline built from it, a vertex
 * buffer filled from Java, and a pushed matrix produce the expected picture inside the frame the backend owns.
 * The geometry is a cube because the point is the path, not the content: a wrong projection, a reversed winding
 * or a missing depth attachment all show up immediately on a cube and would be invisible on a fullscreen quad.</p>
 *
 * <p><b>What this is not.</b> Chunk data. Vanilla's {@code BufferBuilder} output is a different problem - format,
 * upload strategy, per-layer batching - and pretending a hardcoded cube answers it would hide the work rather
 * than do it. When the real geometry arrives it replaces {@link #geometry()}; nothing else here changes.</p>
 */
final class VulkanTerrainLayer implements RenderGraphNode, AutoCloseable {

    private final VkDevice device;
    private final VulkanPipeline pipeline;
    private final VulkanBuffer vertices;
    private final VulkanBuffer indices;
    private final int indexCount;
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f view = new Matrix4f();
    private final Matrix4f modelViewProjection = new Matrix4f();

    private VulkanTerrainLayer(VkDevice device, VulkanPipeline pipeline, VulkanBuffer vertices, VulkanBuffer indices,
                               int indexCount) {
        this.device = device;
        this.pipeline = pipeline;
        this.vertices = vertices;
        this.indices = indices;
        this.indexCount = indexCount;
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

        Geometry geometry = geometry();
        VulkanBuffer vertices = VulkanBuffer.create(device, context, geometry.vertices());
        VulkanBuffer indices;
        try {
            indices = VulkanBuffer.create(device, context, geometry.indices());
        } catch (RuntimeException e) {
            vertices.close();
            throw e;
        }
        return new VulkanTerrainLayer(device, pipeline, vertices, indices, geometry.indexCount());
    }

    @Override
    public String label() {
        return RenderPhases.TERRAIN_LAYER;
    }

    @Override
    public void run(RenderGraphContext context) {
        Object nativeContext = context.nativeContext();
        if (!(nativeContext instanceof VkCommandBuffer commandBuffer)) {
            return;
        }

        // A fixed camera looking at the cube from slightly above and to the side, so three faces are visible and
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
            vkCmdBindIndexBuffer(commandBuffer, indices.handle(), 0L, VK_INDEX_TYPE_UINT16);
            vkCmdDrawIndexed(commandBuffer, indexCount, 1, 0, 0, 0);
        }
    }

    @Override
    public void close() {
        indices.close();
        vertices.close();
        pipeline.close();
    }

    /** position(3f) + normal(3f) + colour(3f) per vertex, and 16-bit indices. */
    private record Geometry(byte[] vertices, byte[] indices, int indexCount) {}

    /**
     * A unit cube with one colour per face.
     *
     * <p>Counter-clockwise when seen from outside, because that is what the pipeline declares as front-facing.
     * Each face has its own vertices so the normals are per-face: sharing them would average across the edges and
     * make a cube look like a sphere.</p>
     */
    private static Geometry geometry() {
        float[][] faces = {
                // +X
                {1, 0, 0, 1, -1, -1, 1, -1, 1, 1, 1, 1, 1, 1, -1},
                // -X
                {-1, 0, 0, -1, 1, -1, -1, 1, 1, -1, -1, 1, -1, -1, -1},
                // +Y
                {0, 1, 0, -1, 1, -1, -1, 1, 1, 1, 1, 1, 1, 1, -1},
                // -Y
                {0, -1, 0, -1, -1, 1, 1, -1, 1, 1, -1, -1, -1, -1, -1},
                // +Z
                {0, 0, 1, -1, -1, 1, 1, -1, 1, 1, 1, 1, -1, 1, 1},
                // -Z
                {0, 0, -1, 1, -1, -1, -1, -1, -1, -1, 1, -1, 1, 1, -1},
        };
        float[][] colors = {
                {0.85f, 0.35f, 0.30f}, {0.30f, 0.70f, 0.85f}, {0.55f, 0.85f, 0.40f},
                {0.80f, 0.75f, 0.30f}, {0.60f, 0.45f, 0.85f}, {0.85f, 0.55f, 0.20f},
        };
        // Quad corner order per face: (x,y,z) triples, counter-clockwise from outside.
        int[][] corners = {{0, 1, 2, 3}, {0, 1, 2, 3}, {0, 1, 2, 3}, {0, 1, 2, 3}, {0, 1, 2, 3}, {0, 1, 2, 3}};

        ByteBuffer vertexData = ByteBuffer.allocate(6 * 4 * VulkanPipeline.VERTEX_STRIDE)
                .order(ByteOrder.nativeOrder());
        ByteBuffer indexData = ByteBuffer.allocate(6 * 6 * Short.BYTES).order(ByteOrder.nativeOrder());

        for (int face = 0; face < faces.length; face++) {
            float[] data = faces[face];
            // data is {nx,ny,nz, x0,y0,z0, x1,y1,z1, x2,y2,z2, x3,y3,z3}
            for (int corner : corners[face]) {
                int base = 3 + corner * 3;
                vertexData.putFloat(data[base]).putFloat(data[base + 1]).putFloat(data[base + 2]);
                vertexData.putFloat(data[0]).putFloat(data[1]).putFloat(data[2]);
                vertexData.putFloat(colors[face][0]).putFloat(colors[face][1]).putFloat(colors[face][2]);
            }
            short first = (short) (face * 4);
            // Two triangles per quad, counter-clockwise.
            indexData.putShort(first).putShort((short) (first + 1)).putShort((short) (first + 2));
            indexData.putShort(first).putShort((short) (first + 2)).putShort((short) (first + 3));
        }

        return new Geometry(vertexData.array(), indexData.array(), 36);
    }
}
