package net.minecraftforge.client.render.vulkan;

import net.minecraftforge.client.render.RenderBackend;
import net.minecraftforge.client.render.RenderBackendSelection;
import net.minecraftforge.client.render.RenderBackends;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.sdl.SDLVideo;

/**
 * The Vulkan render backend.
 *
 * <p><b>What this is today: a registration, not an implementation.</b> It exists so the selection path can be
 * exercised end to end - a backend that is registered, named in configuration, probed and refused - before there
 * is any Vulkan code behind it. It reports itself unavailable, so nothing about the running process changes.</p>
 *
 * <p><b>What it has to become.</b> A backend that owns the frame: instance, device, swapchain, and then every one
 * of the render phases the vanilla renderer used to run. Inside a {@code SDL_WINDOW_VULKAN} window there is no GL
 * context, so no phase can be drawn by the vanilla renderer instead - see {@link RenderBackend}. That is why the
 * probe answers "can this own a frame", and why it may only answer yes once it really can.</p>
 */
public final class VulkanBackend implements RenderBackend {

    /** Stable id, used in configuration, logs and {@link RenderBackends}. */
    public static final String ID = "vulkan";

    /**
     * The priority this backend is registered with on the {@code vulkan-backend} development branch.
     *
     * <p><b>Branch development only.</b> It is deliberately higher than any other candidate, so that the branch
     * boots into Vulkan without anyone having to pass {@code -Doutlands.render.backend}. Note that
     * {@code vanilla-gl} is not a candidate at all - it is the fallback target - so "above the vanilla renderer"
     * means "the highest-priority candidate there is".</p>
     *
     * <p><b>This value has to be revisited before the branch merges.</b> Whether the shipped default is Vulkan,
     * opt-in through the system property, or decided by a hardware probe is a separate decision that has not been
     * made. The install log in {@link #install()} exists so that forgetting this is visible rather than silent.</p>
     */
    public static final int BRANCH_DEVELOPMENT_PRIORITY = 1_000_000;

    private static final Logger LOGGER = LogManager.getLogger("Outlands|render");

    private static final VulkanBackend INSTANCE = new VulkanBackend();

    private VulkanBackend() {}

    /**
     * Registers this backend and its selection candidate.
     *
     * <p>Idempotent, and safe to call before anything else asks for a backend. A call after the selection has
     * already happened is ignored: the window flags are fixed by then, so a late registration could not change the
     * outcome, and accepting it would only make the registry disagree with the process.</p>
     */
    public static void install() {
        if (RenderBackends.isFrozen() || RenderBackends.get(ID) != null) {
            return;
        }
        RenderBackends.register(INSTANCE);
        RenderBackendSelection.register(
                new RenderBackendSelection.Candidate(INSTANCE, VulkanBackend::isAvailable,
                        BRANCH_DEVELOPMENT_PRIORITY));
        if (isAvailable()) {
            LOGGER.warn("Vulkan is the preferred render backend (branch development mode): this build boots into "
                    + "Vulkan. Revisit the default before merge.");
        } else {
            LOGGER.info("Vulkan backend registered as the preferred candidate, but it reports unavailable; the "
                    + "vanilla renderer will be used.");
        }
    }

    /**
     * Whether Vulkan can own the frame on this machine.
     *
     * <p><b>Deliberately {@code false} for now.</b> There is no Vulkan code behind this backend yet, so answering
     * yes would create a window that nothing can draw to. The question is "can this own a frame", not "is this
     * feature complete": the answer flips to a real check once instance, device, swapchain and clear work, even
     * though the render phases will still be missing at that point.</p>
     *
     * <p>A probe must not create driver objects. It is asked before the window exists, and its answer decides how
     * the window is created.</p>
     */
    static boolean isAvailable() {
        return false;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public long sdlWindowFlags() {
        return SDLVideo.SDL_WINDOW_VULKAN;
    }

    @Override
    public boolean isOpenGl() {
        // No GL context is created for this window, so there is no GL state for GlStateView to report either.
        return false;
    }
}
