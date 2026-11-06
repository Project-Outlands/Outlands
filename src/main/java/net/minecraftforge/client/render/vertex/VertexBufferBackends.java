package net.minecraftforge.client.render.vertex;

import net.minecraft.client.renderer.vertex.VertexFormat;

import net.minecraftforge.client.render.RenderBackendSelection;

/**
 * Chooses the implementation behind {@code VertexBuffer}.
 *
 * <p><b>One decision, made where the backend is already known.</b> The choice is read from
 * {@link RenderBackendSelection}, which is resolved before the window exists and never changes afterwards, so a
 * buffer created during startup and one created a thousand frames later cannot disagree about which backend they
 * belong to.</p>
 */
public final class VertexBufferBackends {

    private VertexBufferBackends() {}

    /** Creates the backend for the active renderer. */
    public static VertexBufferBackend create(VertexFormat format) {
        VertexBufferBackend backend = RenderBackendSelection.get().isOpenGl()
                ? new VertexBufferGl(format)
                : new VertexBufferVk(format);
        backend.create();
        return backend;
    }
}
