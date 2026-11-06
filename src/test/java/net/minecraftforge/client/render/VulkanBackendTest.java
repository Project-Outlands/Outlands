package net.minecraftforge.client.render;

import net.minecraftforge.client.render.vulkan.VulkanBackend;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.sdl.SDLVideo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The Vulkan backend's registration, and what selection does with it.
 *
 * <p>The probe answer is pinned in every test: the real one asks the machine's driver, and a test that asserts a
 * fixed outcome from it would pass or fail depending on what GPU the machine has. Pinning it also keeps the
 * selection tests honest - they are about what selection does with an answer, not about producing one.</p>
 */
class VulkanBackendTest {

    @BeforeEach
    void reset() {
        RenderBackends.resetForTests();
        RenderBackendSelection.resetForTests();
        System.clearProperty(RenderBackendSelection.PROPERTY);
    }

    @AfterEach
    void restore() {
        System.clearProperty(RenderBackendSelection.PROPERTY);
        VulkanBackend.forceAvailabilityForTests(null);
    }

    @Test
    void installRegistersTheBackend() {
        VulkanBackend.forceAvailabilityForTests(false);
        VulkanBackend.install();

        assertNotNull(RenderBackends.get(VulkanBackend.ID), "install() must register the backend");
        assertFalse(RenderBackends.isVanilla(VulkanBackend.ID));
    }

    @Test
    void anUnavailableBackendIsNotSelected() {
        VulkanBackend.forceAvailabilityForTests(false);
        VulkanBackend.install();

        assertSame(RenderBackend.VANILLA, RenderBackendSelection.select(),
                "a backend whose probe says no must not own the frame");
    }

    @Test
    void anAvailableBackendIsSelectedOverTheVanillaFallback() {
        VulkanBackend.forceAvailabilityForTests(true);
        VulkanBackend.install();

        // vanilla-gl is not a candidate - it is the fallback target - so being available is enough to win.
        assertSame(RenderBackends.get(VulkanBackend.ID), RenderBackendSelection.select());
    }

    @Test
    void forcingAnUnavailableBackendFallsBackToVanilla() {
        VulkanBackend.forceAvailabilityForTests(false);
        VulkanBackend.install();
        System.setProperty(RenderBackendSelection.PROPERTY, VulkanBackend.ID);

        // Being named explicitly does not bypass the probe: otherwise the window would be created with flags that
        // nothing in the process can draw to.
        assertSame(RenderBackend.VANILLA, RenderBackendSelection.select());
    }

    @Test
    void installIsIdempotent() {
        VulkanBackend.forceAvailabilityForTests(false);
        VulkanBackend.install();
        VulkanBackend.install();

        assertEquals(1, RenderBackends.ids().stream().filter(VulkanBackend.ID::equals).count(),
                "installing twice must not register twice");
    }

    @Test
    void installAfterSelectionIsIgnored() {
        VulkanBackend.forceAvailabilityForTests(false);
        RenderBackendSelection.select();

        VulkanBackend.install();

        assertNull(RenderBackends.get(VulkanBackend.ID),
                "a registration after the choice was made could not affect the window flags");
    }

    @Test
    void backendAsksForAVulkanWindowAndNoGlContext() {
        VulkanBackend.forceAvailabilityForTests(false);
        VulkanBackend.install();
        RenderBackend backend = RenderBackends.get(VulkanBackend.ID);

        assertEquals(VulkanBackend.ID, backend.id());
        assertEquals(SDLVideo.SDL_WINDOW_VULKAN, backend.sdlWindowFlags());
        assertFalse(backend.isOpenGl(), "a Vulkan window has no GL context, so GL state is not meaningful");
        assertNull(backend.nativeContext(), "no context exists before init() is called");
    }

    @Test
    void priorityIsHighEnoughToWinByDefault() {
        // The branch registers Vulkan above every other candidate so the branch boots into it without a property.
        // A candidate at a normal priority must not outrank it.
        RenderBackend other = new RenderBackend() {
            @Override
            public String id() {
                return "other";
            }

            @Override
            public long sdlWindowFlags() {
                return SDLVideo.SDL_WINDOW_OPENGL;
            }
        };
        RenderBackends.register(other);
        RenderBackendSelection.register(new RenderBackendSelection.Candidate(other, () -> true, 100));

        VulkanBackend.forceAvailabilityForTests(true);
        VulkanBackend.install();

        assertSame(RenderBackends.get(VulkanBackend.ID), RenderBackendSelection.select());
    }
}
