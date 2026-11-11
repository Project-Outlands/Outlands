package org.lwjgl.opengl;

import java.util.Set;

/** Capability names supplied by a rendering integration when the window has no native OpenGL context. */
public final class GLCapabilitiesOverride {
    private static volatile Set<String> capabilities = Set.of();

    private GLCapabilitiesOverride() {}

    /** Copies the supplied names; an existing non-GL display refreshes its legacy capability table immediately. */
    public static void set(Set<String> names) {
        capabilities = Set.copyOf(names);
        if (Display.isCreated() && !Display.hasGLContext()) GLContext.refreshCapabilities();
    }

    /** Returns the immutable advertised set without initializing Display or a native GL provider. */
    public static Set<String> get() { return capabilities; }
}
