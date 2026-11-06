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
 * <p>It owns the frame: instance, surface, device, swapchain, depth attachment and the frame loop. What it does
 * not have yet is anything to draw - every frame clears to black and presents. That is the whole of this
 * milestone: proving the window, the device and the presentation path work on a real machine before any renderer
 * depends on them.</p>
 *
 * <p><b>Why the probe may only answer yes when the frame really is owned.</b> Inside an {@code SDL_WINDOW_VULKAN}
 * window there is no GL context, so no phase can be drawn by the vanilla renderer instead. A backend that cannot
 * cover every phase it is responsible for must not be selected - see {@link RenderBackend}. Clearing the screen
 * counts as owning the frame; drawing terrain does not have to work yet.</p>
 *
 * <p><b>Failure is contained.</b> If setup fails, the window has already been created as a Vulkan window and
 * cannot be turned back into a GL one. So the backend reports the failure, logs it, and presents nothing: the
 * alternative would be a half-initialised state that is harder to diagnose than a black window with a stack
 * trace.</p>
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

    private VulkanContext context;
    private VulkanSwapchain swapchain;
    private long window;
    private boolean failed;

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
                    + "Vulkan, which currently clears the screen and draws nothing else. Revisit the default "
                    + "before merge.");
        } else {
            LOGGER.info("Vulkan backend registered as the preferred candidate, but it reports unavailable; the "
                    + "vanilla renderer will be used.");
        }
    }

    /**
     * Whether Vulkan can own the frame on this machine.
     *
     * <p>Answered by {@link VulkanProbe} before the window exists, and cached. The question is "can this own a
     * frame", not "is the renderer finished": the phases are still missing, and they are added behind the same
     * window rather than behind a second decision.</p>
     */
    static boolean isAvailable() {
        return VulkanProbe.isAvailable();
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

    @Override
    public void init(long window) {
        if (failed) {
            return;
        }
        this.window = window;
        try {
            context = VulkanContext.create(window);
            swapchain = VulkanSwapchain.create(context, window);
        } catch (RuntimeException | LinkageError e) {
            // The window is already a Vulkan window; there is no GL context to fall back to. Record the failure,
            // log it once, and present nothing rather than throwing from inside the render loop every frame.
            failed = true;
            LOGGER.error("Vulkan backend setup failed; the window will stay black", e);
            close();
        }
    }

    @Override
    public void present() {
        if (failed || swapchain == null) {
            return;
        }
        try {
            swapchain.drawFrame(window);
        } catch (RuntimeException e) {
            failed = true;
            LOGGER.error("Vulkan frame failed; stopping presentation", e);
            close();
        }
    }

    @Override
    public Object nativeContext() {
        return context;
    }

    @Override
    public void close() {
        if (swapchain != null) {
            swapchain.close();
            swapchain = null;
        }
        if (context != null) {
            context.close();
            context = null;
        }
    }

    /**
     * Test hook: pins the probe answer so tests do not depend on the machine's driver. {@code null} restores real
     * probing. Public only because the selection tests live in {@code net.minecraftforge.client.render}, next to
     * the other backend tests; production code has no reason to call it.
     */
    public static void forceAvailabilityForTests(Boolean value) {
        VulkanProbe.forceForTests(value);
    }
}
