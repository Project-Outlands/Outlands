package net.minecraftforge.client.render.vertex;

import java.nio.ByteBuffer;

import net.minecraft.client.renderer.vertex.VertexFormat;

/**
 * The buffer on a Vulkan backend.
 *
 * <p><b>Why this is not implemented yet, and why that is a throw rather than a no-op.</b> A draw has to be
 * recorded into the command buffer of the frame that is currently being recorded, and that concept does not exist
 * yet: {@code RenderGraphContext} carries a size and an untyped native handle, and no node has begun recording when
 * vanilla's {@code Tessellator.draw()} runs. Implementing {@link #draw} against a guessed shape would mean
 * inventing the recording context here, in the wrong class, from a caller that cannot supply it.</p>
 *
 * <p><b>Why the throw names the missing piece.</b> The alternative - drawing nothing - is the failure mode this
 * project keeps rejecting: it looks like a rendering bug rather than like a missing feature. A message that says
 * "there is no frame recording context" is a work item; a black rectangle is an investigation.</p>
 *
 * <p><b>What is already decided.</b> {@link #upload} can be implemented the moment a device is reachable: it copies
 * bytes and needs nothing else. {@link #draw} is the only part that needs the recorder, and it needs exactly two
 * things from it - the command buffer, and the pipeline for ({@code VertexFormat}, {@link PrimitiveShape},
 * current state). Both are the render graph's job, not this class's.</p>
 */
final class VertexBufferVk implements VertexBufferBackend {

    private final VertexFormat format;
    private byte[] vertices = new byte[0];
    private int vertexCount;

    VertexBufferVk(VertexFormat format) {
        this.format = format;
    }

    @Override
    public void create() {
        // Nothing to allocate until a device is reachable; storage follows the first upload.
    }

    @Override
    public void bind() {
        // Binding is a GL concept. A Vulkan draw names its buffer through the descriptor set or a push constant,
        // so there is no global binding state to set.
    }

    @Override
    public void upload(ByteBuffer data) {
        this.vertices = new byte[data.limit()];
        ByteBuffer view = data.duplicate();
        view.position(0);
        view.get(this.vertices);
        this.vertexCount = this.vertices.length / format.getSize();
    }

    @Override
    public void draw(int mode, int first, int count) {
        throw new UnsupportedOperationException(
                "cannot draw on the Vulkan backend yet: there is no frame recording context, so there is no command"
                        + " buffer to record into and no pipeline for this vertex layout. Needed: a recording"
                        + " context on the render graph node, and a pipeline cache keyed by (VertexFormat,"
                        + " PrimitiveShape, current state). Mode " + mode + ", " + count + " vertices.");
    }

    @Override
    public void unbind() {
        // See bind().
    }

    @Override
    public void delete() {
        this.vertices = new byte[0];
        this.vertexCount = 0;
    }

    @Override
    public VertexFormat format() {
        return format;
    }

    @Override
    public int vertexCount() {
        return vertexCount;
    }
}
