package net.minecraftforge.client.render.vertex;

import java.nio.ByteBuffer;

/**
 * The client-memory draw path on a Vulkan backend.
 *
 * <p><b>Why this throws.</b> Same missing piece as {@code VertexBufferVk.draw}: a draw has to be recorded into the
 * command buffer of the frame currently being recorded, and no node has begun recording when vanilla's
 * {@code Tessellator.draw()} runs. This is now the second consumer asking for that context, which is the signal
 * that the next step is to define it rather than to keep writing stubs.</p>
 *
 * <p><b>What a real implementation needs, stated precisely.</b> Three things, none of which belong in this class:</p>
 * <ol>
 *   <li>A command buffer for the frame in flight.</li>
 *   <li>A pipeline for ({@link VertexLayout}, {@link PrimitiveShape}, current render state) - the layout is a
 *       pipeline input, so the cache key is the layout, not the format object.</li>
 *   <li>A place to put the bytes. Copying per draw would be the obvious translation of client memory, but vanilla
 *       calls this thousands of times per frame for GUI text; the bytes belong in a per-frame ring buffer written
 *       sequentially, with the draw reading an offset into it.</li>
 * </ol>
 *
 * <p><b>What is already decided and does not need the recorder.</b> {@link VertexLayout} - the format translation,
 * including the normalisation rule that GL applies per attribute kind. That is done and tested.</p>
 */
final class VertexStreamVk implements VertexStreamBackend {

    @Override
    public void draw(ByteBuffer data, VertexLayout layout, int mode, int vertexCount) {
        // Recording a display list does not draw: the bytes are kept so a later glCallList can submit them. That is
        // what lets the sky and cloud lists be built during startup, when there is no frame to draw into.
        if (net.minecraftforge.client.render.state.backend.DisplayLists.capture(layout, mode, vertexCount, data)) {
            return;
        }
        throw new UnsupportedOperationException(
                "cannot draw from client memory on the Vulkan backend yet: there is no frame recording context, so"
                        + " there is no command buffer to record into and no pipeline for this vertex layout."
                        + " Needed: a recording context on the render graph node, a pipeline cache keyed by"
                        + " (VertexLayout, PrimitiveShape, state), and a per-frame stream buffer for the bytes."
                        + " Layout stride " + layout.stride() + ", mode " + mode + ", " + vertexCount
                        + " vertices.");
    }
}
