package net.minecraftforge.client.render;

import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.ContextCapabilities;

/**
 * A render backend: something that can own the frame.
 *
 * <p><b>What a backend is not.</b> It is not a binding layer. Whether a renderer issues its own GL calls through
 * LWJGL2, LWJGL3 or a GLES facade is a different question with a different lifetime; that is a command-level
 * concern, this is a frame-level one.</p>
 *
 * <p><b>What a backend has to answer before the window exists.</b> Only {@link #sdlWindowFlags()}: the window is
 * created with {@code SDL_WINDOW_OPENGL} or {@code SDL_WINDOW_VULKAN} and those are mutually exclusive, so the
 * flags cannot be revisited. Everything else is asked afterwards.</p>
 *
 * <p><b>Why the vanilla backend is special.</b> It is not an implementation the loader ships; it is the
 * unmodified renderer that is already there. That is also why it is the fallback target: falling back means not
 * taking over at all, not switching to a second renderer the loader would have to maintain.</p>
 *
 * <p><b>A backend must cover every phase it owns.</b> There is no phase-level fallback: inside a
 * {@code SDL_WINDOW_VULKAN} window there is no GL context to draw a phase with. A backend that cannot cover
 * everything must not be selected.</p>
 */
public interface RenderBackend {

    /** Stable id, used in configuration, logs and {@link RenderBackends}. */
    String id();

    /**
     * The SDL window flags this backend needs.
     *
     * <p>Called before the window is created and only then. Returning {@code SDL_WINDOW_OPENGL} means the loader
     * will also apply the GL context attributes and create a GL context.</p>
     */
    long sdlWindowFlags();

    /**
     * Whether this backend draws through OpenGL, i.e. whether GL state and GL attributes are meaningful.
     *
     * <p><b>Contract, not a hint.</b> On the loader side this decides two things: whether
     * {@code Display.applyGlAttributes(...)} runs, and whether a GL context is created at all. A backend that
     * answers {@code true} must therefore also accept its context request through {@code ContextAttribs} — that
     * is the channel {@code Display} honours first, ahead of {@code ForgeEarlyConfig}. A renderer that needs to
     * shape the context request (a core profile, for instance) attaches it there and gets it, without having to
     * infer the loader's window backend from {@code Display}'s fields.</p>
     *
     * <p>Answering {@code false} means the window is created without GL support: no context, no GL attributes,
     * and no GL state to read through {@code GlStateView}.</p>
     */
    default boolean isOpenGl() {
        return false;
    }

    /**
     * Creates whatever context or surface this backend needs from the window.
     *
     * <p>Called once, after the window exists. The vanilla backend does nothing here: its context was created by
     * the loader's normal GL path.</p>
     *
     * @param window the SDL window handle
     */
    default void init(long window) {}

    /** Releases what {@link #init(long)} created. Called on shutdown, and on a failed selection. */
    default void close() {}

    /**
     * The backend's own native handle, for a caller that needs to record commands in it.
     *
     * <p>Deliberately untyped: a Vulkan command buffer, an SDL_GPU render pass and a GL context have nothing in
     * common, and pretending they do would produce an abstraction that fits none of them.</p>
     */
    default @Nullable Object nativeContext() {
        return null;
    }

    /**
     * The GL feature level this backend implements, for the game's capability branches.
     *
     * <p>Only consulted when {@link #isOpenGl()} is {@code false}, where there is no context to ask. It is not a
     * claim that a GL context exists: vanilla branches on these flags ({@code useVbo()}, {@code shadersSupported},
     * {@code framebufferSupported}), and those branches have to agree with what the backend actually implements,
     * or vanilla takes a path nobody built.</p>
     *
     * <p>Returning {@code null} means "use the loader's default contract", which is the one the shim promises:
     * GL 2.1 plus {@code ARB_framebuffer_object}. A backend that implements exactly that should return
     * {@code null} rather than restating it.</p>
     */
    default @Nullable ContextCapabilities glCapabilities() {
        return null;
    }

    /**
     * Requests a swap interval: {@code 1} for vsync, {@code 0} for uncapped.
     *
     * <p>A presentation property, not a rendering one. GL sets it with {@code SDL_GL_SetSwapInterval}, which fails
     * outright without a context; a Vulkan backend expresses the same request as a present mode. Only consulted when
     * {@link #isOpenGl()} is {@code false}.</p>
     *
     * <p>Throwing rather than ignoring by default: a backend that cannot change its present mode should say so at
     * the one moment the game asks, not leave the user with a frame rate they did not choose.</p>
     */
    default void setSwapInterval(int interval) {
        throw new UnsupportedOperationException(getClass().getName() + " cannot change its swap interval to "
                + interval + ": it has no OpenGL context, and it did not say what it does instead");
    }

    /**
     * The largest 2D texture edge this backend can create, or {@code 0} when GL should be asked.
     *
     * <p>A device capability, not a rendering operation: the game asks once during startup to decide atlas sizes,
     * and GL answers it by uploading to a proxy texture target and reading the resulting width - a trick that needs
     * a context. Only consulted when {@link #isOpenGl()} is {@code false}.</p>
     */
    default int maxTextureSize() {
        return 0;
    }

    /**
     * An identification string ({@code GL_VENDOR}, {@code GL_RENDERER}, {@code GL_VERSION}) for this backend.
     *
     * <p>Only consulted when {@link #isOpenGl()} is {@code false}. Returning {@code null} defers to the loader's
     * generic answer, which is sufficient for everything the game does with these strings - it prints them.</p>
     */
    default @Nullable String glString(int name) {
        return null;
    }

    /**
     * Presents one frame. Called where the GL backend would swap buffers, once per frame.
     *
     * <p>This is deliberately one method rather than a begin/end pair: the only thing the caller knows is "the
     * frame is over, show it", which is exactly what {@code SDL_GL_SwapWindow} means. Recording, submission and
     * presentation all happen inside it. A backend that needs to interleave CPU work with the frame will grow a
     * richer contract when the render graph exists, not before.</p>
     */
    default void present() {}

    /**
     * The unmodified vanilla renderer.
     *
     * <p>A singleton because there is exactly one: it is the renderer the game already runs. Its flags are the
     * ones the loader used before any of this existed.</p>
     */
    RenderBackend VANILLA = new RenderBackend() {
        @Override
        public String id() {
            return RenderBackends.VANILLA_GL_ID;
        }

        @Override
        public long sdlWindowFlags() {
            return org.lwjgl.sdl.SDLVideo.SDL_WINDOW_OPENGL;
        }

        @Override
        public boolean isOpenGl() {
            return true;
        }
    };
}
