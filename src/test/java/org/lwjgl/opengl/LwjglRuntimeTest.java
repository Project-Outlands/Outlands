package org.lwjgl.opengl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the parsing {@link LwjglRuntime#verify} bases its accept/warn/refuse decision on. */
class LwjglRuntimeTest {
    @Test
    void qualifiersAreIgnored() {
        assertTrue(LwjglRuntime.compareVersions("3.4.1-snapshot", "3.4.3") < 0);
        assertEquals(0, LwjglRuntime.compareVersions("3.4.3", "3.4.3"));
    }

    @Test
    void componentsAreComparedAsNumbers() {
        assertTrue(LwjglRuntime.compareVersions("3.4.10", "3.4.9") > 0);
        assertTrue(LwjglRuntime.compareVersions("4.0.0", "3.4.3") > 0);
        assertEquals(0, LwjglRuntime.compareVersions("3.4", "3.4.0"));
        assertEquals(0, LwjglRuntime.compareVersions("3.4.1+build-7", "3.4.1"));
    }

    @Test
    void anUnreadableVersionHasNoOpinion() {
        assertEquals(0, LwjglRuntime.compareVersions("unknown", "3.4.3"));
        assertEquals(0, LwjglRuntime.compareVersions(null, "3.4.3"));
    }
}
