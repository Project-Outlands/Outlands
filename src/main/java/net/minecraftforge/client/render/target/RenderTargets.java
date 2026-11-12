package net.minecraftforge.client.render.target;

import org.jspecify.annotations.Nullable;

/**
 * The renderer's factory for {@link RenderTarget}s, installed by whichever backend owns the device.
 *
 * <p><b>Why a service and not a field on the backend.</b> {@code Framebuffer} is constructed deep inside the game - and
 * by mods - while the device belongs to the renderer. A target has to be allocated by whoever owns the device, so the
 * backend installs this once and the game's framebuffer objects call into it. On the GL path nothing is installed and
 * {@code Framebuffer}'s own bodies run instead.</p>
 *
 * <p><b>No target for the window.</b> The game's main framebuffer is the swapchain's colour image: the frame is already
 * painting it, so giving it a second image would mean copying the finished frame into the window every time. That is
 * why {@code Framebuffer} asks for a target only when it is not the window's - the distinction is passed at
 * construction, by the one place that creates it ({@code Minecraft.init}), rather than guessed from the order
 * framebuffers happen to be created in.</p>
 */
public final class RenderTargets {

    /** What a backend provides: a way to allocate a target from its device. */
    public interface Factory {

        /**
         * Allocates a target with its own colour and, when asked, depth attachment.
         *
         * @throws IllegalStateException when the renderer cannot allocate one, which is a backend that failed to
         *                               initialise rather than a problem with the request
         */
        RenderTarget create(int width, int height, boolean useDepth);
    }

    private static volatile @Nullable Factory current;

    private RenderTargets() {}

    /** Installed by a backend once its device exists. */
    public static void install(@Nullable Factory factory) {
        current = factory;
    }

    /** The installed factory, or {@code null} on the GL path. */
    public static @Nullable Factory current() {
        return current;
    }

    /**
     * Clears the installation, but only if it is still {@code expected}.
     *
     * <p>The identity check is what makes a second renderer's install survive a first one's shutdown: a backend that
     * closes after another has taken over must not clear the newcomer's factory.</p>
     */
    public static void uninstall(Factory expected) {
        if (current == expected) {
            current = null;
        }
    }
}
