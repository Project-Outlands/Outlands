package net.minecraftforge.client.render.target;

import net.minecraftforge.client.render.RenderBackendSelection;

import org.jspecify.annotations.Nullable;

/**
 * Supplies the render-target backend for a renderer that is not OpenGL.
 *
 * <p>{@code null} is the OpenGL answer: {@code Framebuffer}'s own bodies stay in place and stay reachable.</p>
 */
public final class FramebufferBackends {

    private FramebufferBackends() {}

    /** The backend for the active renderer, or {@code null} when the vanilla GL code path applies. */
    public static @Nullable FramebufferBackend forNonGl() {
        // A fresh instance per framebuffer: the state it tracks (size, colour, filter) belongs to one target.
        return RenderBackendSelection.glPath() ? null : new FramebufferVk();
    }
}
