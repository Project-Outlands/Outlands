package net.minecraftforge.client.render.vertex;

import net.minecraftforge.client.render.RenderBackendSelection;

import org.jspecify.annotations.Nullable;

/**
 * Supplies the backend for drawing from client memory, when the renderer is not OpenGL.
 *
 * <p>See {@link VertexBufferBackends} for why {@code null} is the OpenGL answer: the walk over the vertex format,
 * the client-state calls and the draw all stay in {@code WorldVertexBufferUploader}, in place and reachable.</p>
 */
public final class VertexStreamBackends {

    private static final VertexStreamVk VK = new VertexStreamVk();

    private VertexStreamBackends() {}

    /** The backend for the active renderer, or {@code null} when the vanilla GL code path applies. */
    public static @Nullable VertexStreamBackend forNonGl() {
        return RenderBackendSelection.glPath() ? null : VK;
    }
}
