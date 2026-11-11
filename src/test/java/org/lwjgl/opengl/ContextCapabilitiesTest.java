package org.lwjgl.opengl;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextCapabilitiesTest {
    @Test
    void explicitNamesEnableOnlyTheRequestedLegacyFlags() {
        ContextCapabilities capabilities = new ContextCapabilities(Set.of("OpenGL33", "GL_ARB_sync", "unknown_extension"));
        assertTrue(capabilities.OpenGL33);
        assertTrue(capabilities.GL_ARB_sync);
        assertFalse(capabilities.OpenGL46);
        assertFalse(capabilities.GL_ARB_compute_shader);

        ContextCapabilities empty = new ContextCapabilities(Set.of());
        assertFalse(empty.OpenGL33);
        assertFalse(empty.GL_ARB_sync);
    }
}
