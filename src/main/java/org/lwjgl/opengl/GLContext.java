package org.lwjgl.opengl;

import org.jspecify.annotations.Nullable;

public class GLContext {

    /**
     * Cached table for a real GL context.
     *
     * <p>Deliberately not built in a static initializer: {@code new ContextCapabilities()} calls
     * {@code GL.getCapabilities()}, which throws when the thread has no context. On a non-GL backend that is the
     * normal case, and an eager initializer would turn it into a class-initialisation failure instead of a
     * question with an answer.</p>
     */
    private static @Nullable ContextCapabilities contextCapabilities;

    public static ContextCapabilities getCapabilities() {
        // A non-GL backend has no context to describe, but it still has a feature level, and vanilla branches on
        // it. Ask the backend instead of handing out the all-false table, which would select display lists and
        // "no shaders" - paths no backend implements.
        if (!net.minecraftforge.client.render.RenderBackendSelection.get().isOpenGl()) {
            return net.minecraftforge.client.render.GlAnswers.capabilities();
        }
        // On the GL path the table follows the current context, so it is (re)built on first use and rebuilt by
        // refreshCapabilities whenever the context changes.
        if (contextCapabilities == null) {
            refreshCapabilities();
        }
        return contextCapabilities;
    }

    /**
     * Rebuilds the capability table from the current GL context.
     * <p>
     * The table is built lazily, so anything that touches this class before a context is current gets the
     * backend's answer instead. Call this once the context exists.
     */
    public static void refreshCapabilities() {
        contextCapabilities = new ContextCapabilities();
    }
}
