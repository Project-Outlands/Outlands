package top.outlands.render;

/**
 * The graphics backend the process runs on.
 *
 * <p>This is decided <b>once</b>, before the window exists, and never changes afterwards. The reason is
 * structural rather than stylistic: {@code SDL_WINDOW_OPENGL} and {@code SDL_WINDOW_VULKAN} are mutually
 * exclusive at window creation, so a window can only ever belong to one backend.</p>
 *
 * <p>Everything downstream reads the selected backend through a {@code static final} field, so no call site
 * needs a branch. See {@link RenderBackendSelection}.</p>
 */
public enum RenderBackendKind {

    /** The unmodified vanilla renderer. Always available; the default. */
    VANILLA_GL("vanilla-gl"),

    /** A Vulkan renderer owning the frame. */
    VULKAN("vulkan"),

    /** A renderer built on SDL_GPU, owning the frame. */
    SDL_GPU("sdl-gpu");

    private final String id;

    RenderBackendKind(String id) {
        this.id = id;
    }

    /**
     * Stable identifier used by rulesets, configuration and log output.
     *
     * <p>Never derive this from {@link #name()} or {@link #ordinal()}: the id is part of the on-disk
     * configuration format and of the shim ruleset contract.</p>
     */
    public String id() {
        return id;
    }

    /** Whether this backend draws through OpenGL, i.e. whether GL state and GL attributes are meaningful. */
    public boolean isOpenGl() {
        return this == VANILLA_GL;
    }
}
