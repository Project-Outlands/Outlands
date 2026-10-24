package org.lwjgl.opengl;

public class GLContext {

    private static ContextCapabilities contextCapabilities = new ContextCapabilities();

    public static ContextCapabilities getCapabilities() {
        return contextCapabilities;
    }

    /**
     * Rebuilds the capability table from the current GL context.
     * <p>
     * The table is built in a static initializer, so anything that touches this class before a context is
     * current caches an all-false table forever. Call this once the context exists.
     */
    public static void refreshCapabilities() {
        contextCapabilities = new ContextCapabilities();
    }
}
