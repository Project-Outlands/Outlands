package net.minecraftforge.client.render.texture;

import net.minecraftforge.client.render.RenderBackendSelection;

import org.jspecify.annotations.Nullable;

/** Supplies the texture backend for a renderer that is not OpenGL. */
public final class TextureBackends {

    private static final TextureBackendVk VK = new TextureBackendVk();

    private TextureBackends() {}

    /** The backend for the active renderer, or {@code null} when the vanilla GL code path applies. */
    public static @Nullable TextureBackend forNonGl() {
        return RenderBackendSelection.glPath() ? null : VK;
    }

    /** The Vulkan backend instance, for diagnostics. {@code null} when the GL path is active. */
    public static @Nullable TextureBackend instance() {
        return forNonGl();
    }
}
