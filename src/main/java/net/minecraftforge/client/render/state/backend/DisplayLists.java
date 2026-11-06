package net.minecraftforge.client.render.state.backend;

import net.minecraftforge.client.render.RenderBackendSelection;
import net.minecraftforge.client.render.vertex.VertexLayout;

import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;

/**
 * Supplies the display-list backend for a renderer that is not OpenGL, and gives the stream path a way to ask
 * whether a draw should be recorded instead of submitted.
 */
public final class DisplayLists {

    private static final DisplayListVk VK = new DisplayListVk();

    private DisplayLists() {}

    /** The backend for the active renderer, or {@code null} when the vanilla GL code path applies. */
    public static @Nullable DisplayListBackend forNonGl() {
        return RenderBackendSelection.glPath() ? null : VK;
    }

    /**
     * Offers one draw to the list being recorded.
     *
     * <p>Called from the stream backend before it would otherwise submit. Returning {@code true} means the draw
     * became part of a display list and must not be submitted; {@code false} means nothing is being recorded and the
     * caller decides what to do with it.</p>
     */
    public static boolean capture(VertexLayout layout, int mode, int vertexCount, ByteBuffer data) {
        return !RenderBackendSelection.glPath() && VK.capture(layout, mode, vertexCount, data);
    }

    /** The Vulkan instance, for diagnostics and tests. */
    public static DisplayListBackend instance() {
        return VK;
    }
}
