package net.minecraftforge.client.render.state.backend;

import net.minecraftforge.client.render.RenderBackendSelection;

import org.jspecify.annotations.Nullable;

/** Supplies the frame-state backend for a renderer that is not OpenGL. */
public final class FrameStates {

    private static final FrameStateVk VK = new FrameStateVk();

    private FrameStates() {}

    /** The backend for the active renderer, or {@code null} when the vanilla GL code path applies. */
    public static @Nullable FrameState forNonGl() {
        return RenderBackendSelection.glPath() ? null : VK;
    }

    /** The Vulkan instance, for diagnostics and tests. {@code null} when the GL path is active. */
    public static @Nullable FrameState instance() {
        return forNonGl();
    }
}
