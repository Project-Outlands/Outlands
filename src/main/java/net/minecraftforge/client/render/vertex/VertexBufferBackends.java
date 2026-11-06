package net.minecraftforge.client.render.vertex;

import net.minecraft.client.renderer.vertex.VertexFormat;

import net.minecraftforge.client.render.RenderBackendSelection;

import org.jspecify.annotations.Nullable;

/**
 * Supplies the backend for a renderer that is not OpenGL.
 *
 * <p><b>{@code null} is the OpenGL answer, and it means "the code that is already there".</b> On the GL path
 * {@code VertexBuffer}'s original bodies stay in place and stay reachable - nothing is duplicated into the loader,
 * and upstream changes to those bodies still take effect. The loader only gains an implementation for the path that
 * has none. Returning a GL implementation from here would put a second copy of vanilla's logic in the tree, with
 * the MC copy left dead.</p>
 */
public final class VertexBufferBackends {

    private VertexBufferBackends() {}

    /**
     * The backend for the active renderer, or {@code null} when the vanilla GL code path applies.
     *
     * <p>The choice comes from {@link RenderBackendSelection}, which is fixed before the window exists, so a buffer
     * created during startup and one created later cannot disagree about which renderer they belong to.</p>
     */
    public static @Nullable VertexBufferBackend forNonGl(VertexFormat format) {
        if (RenderBackendSelection.glPath()) {
            return null;
        }
        VertexBufferBackend backend = new VertexBufferVk(format);
        backend.create();
        return backend;
    }
}
