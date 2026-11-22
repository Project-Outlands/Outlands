package org.lwjgl.opengl;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The names-based half of {@link ContextCapabilities}: what an external rendering integration advertises when there is
 * no GL context to ask.
 * <p>
 * The other half - that no capability flag is linked to a {@code GLCapabilities} field the oldest supported LWJGL
 * release does not have - is {@link CapabilityFieldCompatibilityTest}, which scans this build's bytecode against the
 * minimum release's field table. An earlier version of this class asserted the stricter "no compiled field read at
 * all", which the current design deliberately does not hold: stable flags are read directly, and only the ones whose
 * presence differs between releases go through {@code ContextCapabilities#supports}.
 */
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
