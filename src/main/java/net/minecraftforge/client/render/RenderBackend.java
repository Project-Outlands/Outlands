package net.minecraftforge.client.render;

import org.jspecify.annotations.Nullable;

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
