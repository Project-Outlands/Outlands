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
        return RenderBackendSelection.glPath() ? null : new FramebufferVk(false);
    }

    /**
     * The backend for the game's main framebuffer, whose colour attachment is the window's image.
     *
     * <p>Separate from {@link #forNonGl()} because the difference is a fact about which framebuffer this is, and there
     * is exactly one place that knows it: {@code Minecraft.init} creates the main framebuffer and nothing else does.
     * A backend that guessed - by size, by creation order - would guess wrong for a mod that creates a framebuffer of
     * the same size in preInit, and the failure would be a frame that renders into an image nobody shows.</p>
     */
    public static @Nullable FramebufferBackend forNonGlWindow() {
        return RenderBackendSelection.glPath() ? null : new FramebufferVk(true);
    }
}
