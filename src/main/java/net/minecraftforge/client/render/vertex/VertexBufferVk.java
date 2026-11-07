package net.minecraftforge.client.render.vertex;

import java.nio.ByteBuffer;

import net.minecraft.client.renderer.vertex.VertexFormat;

/**
 * The buffer on a Vulkan backend.
 *
 * <p><b>What a draw of a stored buffer is here.</b> Nothing special: the bytes are already the shape vanilla wrote
 * them in, so a draw joins the frame's list like any client-memory draw does - the same {@link FrameDraws.Draw}, the
 * same layout translation, the same replay inside the frame. That is deliberate rather than convenient. GL's only
 * difference between the two paths is where the bytes live at draw time (a buffer object versus a heap array), and
 * this backend copies both into the frame's own storage, so a second mechanism would buy nothing and cost another
 * path to keep correct.</p>
 *
 * <p><b>Why it is worth saying that.</b> The bytes are copied per draw per frame, which for a world of a thousand
 * chunks is a real cost - one the terrain layer avoids by keeping meshes resident and drawing them from the capture
 * queue ({@code VulkanTerrainLayer}). So this path is what makes every VBO vanilla owns work (the sky, the stars, the
 * cloud plane, one-off vertex buffers), and the chunk path is expected to go through the layer instead. Recording it
 * here as "the general answer" would hide that difference.</p>
 *
 * <p><b>What is owed to a caller that draws before the backend is up.</b> A draw outside a frame gets the same answer
 * as the client-memory path: a throw that names the missing recorder, rather than nothing on screen.</p>
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
        if (count == 0) {
            // GL issues nothing for an empty range, and a chunk layer that compiled to nothing reaches here.
            return;
        }
        VertexLayout layout = VertexLayout.of(format);
        int from = first * layout.stride();
        int to = from + count * layout.stride();
        if (to > vertices.length) {
            throw new IllegalArgumentException("a draw of " + count + " vertices from " + first + " needs bytes up to "
                    + to + ", the buffer holds " + vertices.length);
        }
        // Byte order is not settled here: VertexConverter reads the copy as native order, the same way it reads a
        // client-memory draw. Wrapping is therefore enough, and a slice keeps the vertex range the game asked for.
        ByteBuffer view = ByteBuffer.wrap(vertices, from, to - from).slice();
        if (FrameDraws.record(layout, mode, count, view, DrawState.current())) {
            return;
        }
        throw new UnsupportedOperationException(
                "cannot draw a vertex buffer on the Vulkan backend yet: there is no frame recording context, so there"
                        + " is no frame to replay this draw inside. Needed: the backend to have begun collecting the"
                        + " frame, which happens once the device exists. Layout stride " + layout.stride() + ", mode "
                        + mode + ", " + count + " vertices.");
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
