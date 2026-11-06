package net.minecraftforge.client.render.vertex;

import java.nio.ByteBuffer;

/**
 * Drawing from client memory, which is how every non-terrain draw in vanilla works.
 *
 * <p><b>What this replaces.</b> {@code WorldVertexBufferUploader} walks the vertex format, calls
 * {@code glVertexPointer}/{@code glColorPointer}/{@code glTexCoordPointer} for each element with a pointer into a
 * {@code ByteBuffer}, enables the matching client states, and then issues {@code glDrawArrays}. The pointer is
 * transient: the driver copies what it needs during the draw call. So the whole operation is "here are the bytes,
 * here is the layout, draw this many vertices in this mode" - and that is the contract below.</p>
 *
 * <p><b>Why one method rather than one per element.</b> An interface shaped like the GL calls
 * ({@code vertexPointer(...)}, {@code enableClientState(...)}) would force every implementation to reproduce GL's
 * client-state machine, including the enable/disable ordering and the fact that the pointer is implicit state read
 * later by an unrelated call. A backend that receives the bytes and the layout together can copy them into a
 * stream buffer and record a draw; there is no state to reproduce.</p>
 *
 * <p><b>There is no GL implementation of this interface.</b> The GL path is {@code WorldVertexBufferUploader}'s own
 * body - the element walk, the client-state calls, the draw - left in place and still reachable, exactly as it was
 * before this seam existed. {@link VertexStreamBackends#forNonGl()} returns {@code null} for it. A {@code
 * VertexStreamGl} in the loader would be a second copy of code that already exists in the tree.</p>
 *
 * <p><b>{@link VertexLayout} rather than {@code VertexFormat}.</b> The layout is the translated form - normalisation
 * decided, padding included in the stride, offsets resolved - so a backend does not have to re-derive GL's
 * rules. See {@link VertexLayout}.</p>
 */
public interface VertexStreamBackend {

    /**
     * Draws {@code vertexCount} vertices from {@code data}.
     *
     * @param data        the vertex bytes, starting at position 0
     * @param layout      the layout those bytes are in
     * @param mode        a GL primitive constant; converting it is the backend's job
     * @param vertexCount how many vertices to draw
     */
    void draw(ByteBuffer data, VertexLayout layout, int mode, int vertexCount);
}
