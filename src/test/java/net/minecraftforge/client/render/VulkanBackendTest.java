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
 * The Vulkan backend is registered and named, but cannot be selected yet.
 *
 * <p>This covers the plumbing rather than the renderer: a backend that is registered, named through the system
 * property, probed, refused and falls back. The first test is also a tripwire - it asserts that the branch
 * default cannot actually take effect, so flipping the probe has to be a deliberate act that revisits
 * {@link VulkanBackend#BRANCH_DEVELOPMENT_PRIORITY} and the shipped default at the same time.</p>
 */
class VulkanBackendTest {

    @BeforeEach
    void reset() {
        RenderBackends.resetForTests();
        RenderBackendSelection.resetForTests();
        System.clearProperty(RenderBackendSelection.PROPERTY);
    }

    @AfterEach
    void clearProperty() {
        System.clearProperty(RenderBackendSelection.PROPERTY);
    }

    @Test
    void installRegistersTheBackendButItCannotBeSelectedYet() {
        VulkanBackend.install();

        assertNotNull(RenderBackends.get(VulkanBackend.ID), "install() must register the backend");
        assertFalse(RenderBackends.isVanilla(VulkanBackend.ID));
        assertSame(RenderBackend.VANILLA, RenderBackendSelection.select(),
                "a backend that cannot own the frame must not be selected");
    }

    @Test
    void installIsIdempotent() {
        VulkanBackend.install();
        VulkanBackend.install();

        assertEquals(1, RenderBackends.ids().stream().filter(VulkanBackend.ID::equals).count(),
                "installing twice must not register twice");
    }

    @Test
    void forcingAnUnavailableBackendFallsBackToVanilla() {
        VulkanBackend.install();
        System.setProperty(RenderBackendSelection.PROPERTY, VulkanBackend.ID);

        // Being named explicitly does not bypass the probe: otherwise the window would be created with flags
        // that nothing in the process can draw to.
        assertSame(RenderBackend.VANILLA, RenderBackendSelection.select());
    }

    @Test
    void installAfterSelectionIsIgnored() {
        RenderBackendSelection.select();

        VulkanBackend.install();

        assertNull(RenderBackends.get(VulkanBackend.ID),
                "a registration after the choice was made could not affect the window flags");
    }

    @Test
    void backendAsksForAVulkanWindowAndNoGlContext() {
        VulkanBackend.install();
        RenderBackend backend = RenderBackends.get(VulkanBackend.ID);

        assertEquals(VulkanBackend.ID, backend.id());
        assertEquals(SDLVideo.SDL_WINDOW_VULKAN, backend.sdlWindowFlags());
        assertFalse(backend.isOpenGl(), "a Vulkan window has no GL context, so GL state is not meaningful");
        assertNull(backend.nativeContext(), "there is no context to hand out yet");
    }
}
