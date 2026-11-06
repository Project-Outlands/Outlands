package net.minecraftforge.client.render.vertex;

import net.minecraftforge.client.render.RenderBackendSelection;

/**
 * Chooses the implementation for drawing from client memory.
 *
 * <p>Resolved from {@link RenderBackendSelection}, which is fixed before the window exists, so every draw in a
 * process belongs to the same backend.</p>
 */
public final class VertexStreamBackends {

    private static final VertexStreamBackend GL = new VertexStreamGl();
    private static final VertexStreamBackend VK = new VertexStreamVk();

    private VertexStreamBackends() {}

    /** The backend for the active renderer. */
    public static VertexStreamBackend get() {
        return RenderBackendSelection.get().isOpenGl() ? GL : VK;
    }
}
