package net.minecraftforge.client.render;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The backend table holds exactly what was registered.
 *
 * <p>The point of the table over an enum is the negative assertion: a backend nobody registered must not be
 * reachable, because a predeclared constant with nothing behind it would appear in configuration, logs and
 * {@code values()} as if it were real.</p>
 */
class RenderBackendsTest {

    private static RenderBackend backend(String id, boolean openGl) {
        return new RenderBackend() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public long sdlWindowFlags() {
                return openGl ? 0x0000_0002L : 0x0010_0000L;
            }

            @Override
            public boolean isOpenGl() {
                return openGl;
            }
        };
    }

    @BeforeEach
    void reset() {
        RenderBackends.resetForTests();
        RenderBackendSelection.resetForTests();
    }

    @Test
    void onlyVanillaIsBuiltIn() {
        assertNull(RenderBackends.get("vulkan"), "a backend nobody registered must not exist");
        assertNull(RenderBackends.get("sdl-gpu"));
        assertSame(RenderBackend.VANILLA, RenderBackends.vanilla());
        assertTrue(RenderBackends.isVanilla(RenderBackends.VANILLA_GL_ID));
        assertEquals(0, RenderBackends.ids().indexOf(RenderBackends.VANILLA_GL_ID));
    }

    @Test
    void registeredBackendIsRetrievable() {
        RenderBackend vulkan = backend("vulkan", false);
        RenderBackends.register(vulkan);

        assertSame(vulkan, RenderBackends.get("vulkan"));
        assertFalse(RenderBackends.isVanilla("vulkan"));
    }

    @Test
    void duplicateIdIsRejected() {
        RenderBackends.register(backend("vulkan", false));
        assertThrows(IllegalArgumentException.class, () -> RenderBackends.register(backend("vulkan", false)));
    }

    @Test
    void selectionUsesRegisteredBackendAndFreezesTheTable() {
        RenderBackend vulkan = backend("vulkan", false);
        RenderBackends.register(vulkan);
        RenderBackendSelection.register(new RenderBackendSelection.Candidate(vulkan, () -> true, 10));

        assertSame(vulkan, RenderBackendSelection.select());
        assertTrue(RenderBackends.isFrozen());
    }

    @Test
    void unavailableProbeFallsBackToVanilla() {
        RenderBackend vulkan = backend("vulkan", false);
        RenderBackends.register(vulkan);
        RenderBackendSelection.register(new RenderBackendSelection.Candidate(vulkan, () -> false, 10));

        assertSame(RenderBackend.VANILLA, RenderBackendSelection.select());
    }

    @Test
    void throwingProbeFallsBackToVanilla() {
        RenderBackend vulkan = backend("vulkan", false);
        RenderBackends.register(vulkan);
        RenderBackendSelection.register(new RenderBackendSelection.Candidate(vulkan, () -> {
            throw new IllegalStateException("no driver");
        }, 10));

        assertSame(RenderBackend.VANILLA, RenderBackendSelection.select());
    }

    @Test
    void higherPriorityWins() {
        RenderBackend low = backend("low", false);
        RenderBackend high = backend("high", false);
        RenderBackends.register(low);
        RenderBackends.register(high);
        RenderBackendSelection.register(new RenderBackendSelection.Candidate(low, () -> true, 1));
        RenderBackendSelection.register(new RenderBackendSelection.Candidate(high, () -> true, 2));

        assertSame(high, RenderBackendSelection.select());
    }
}
