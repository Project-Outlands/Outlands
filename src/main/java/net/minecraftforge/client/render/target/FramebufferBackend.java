package net.minecraftforge.client.render.target;

/**
 * A render target, as the game uses one.
 *
 * <p><b>What a framebuffer is to the game.</b> Something with a width and a height that can be drawn into, cleared,
 * and later read back as a texture; optionally with a depth buffer and a stencil. Vanilla expresses that with an
 * FBO, a colour texture and a renderbuffer. A backend that owns its own attachments expresses the same thing with
 * images it already has.</p>
 *
 * <p><b>There is no GL implementation of this interface.</b> The GL path is {@code Framebuffer}'s own bodies, left
 * in place and still reachable; {@link FramebufferBackends#forNonGl()} returns {@code null} for it. Same reason as
 * the vertex seams: a loader-side copy would be dead code that stops tracking upstream.</p>
 *
 * <p><b>Which of these can be answered without a frame.</b> {@link #setColor} and {@link #setFilter} are state and
 * are always answerable. {@link #clear}, {@link #bind} and {@link #render} are frame commands: they say what the
 * next recorded frame should do, so they cannot be answered before there is a frame to record into. Implementations
 * are expected to say which is which rather than silently succeed.</p>
 */
public interface FramebufferBackend {

    /**
     * Allocates the target.
     *
     * <p>Called before anything is drawn into it, so it must not depend on a frame being in flight.</p>
     */
    void create(int width, int height, boolean useDepth);

    /**
     * The texture handle the game binds to sample this target's colour attachment.
     *
     * <p>{@code ShaderManager} reads {@code Framebuffer.framebufferTexture} directly and binds that number, so the
     * handle has to come from the same namespace the game binds ordinary textures in. The window's target has none: its
     * colour is the swapchain image, which the frame is already painting.</p>
     *
     * @return the handle, or {@code 0} when this target has no sampleable colour of its own
     */
    int textureId();

    /** Releases the target. Safe to call when nothing was allocated. */
    void delete();

    /** Draws into this target until {@link #unbind()} is called. */
    void bind(boolean setViewport);

    /** Restores drawing into whatever was current before the matching {@link #bind}. */
    void unbind();

    /** Clears colour and, when the target has one, depth. */
    void clear();

    /**
     * Draws this target's colour attachment over the whole current target.
     *
     * @param width        destination width
     * @param height       destination height
     * @param disableBlend whether the draw should ignore blending
     */
    void render(int width, int height, boolean disableBlend);

    /** Sets the colour {@link #clear} uses. */
    void setColor(float red, float green, float blue, float alpha);

    /** Sets the texture filter used when the colour attachment is sampled. */
    void setFilter(int glFilter);

    /** Makes the colour attachment available for sampling. */
    void bindTexture();

    /** Releases {@link #bindTexture}. */
    void unbindTexture();

    /** Requests a depth-stencil buffer on the next {@link #create}. */
    void setStencil(boolean enabled);

    /** Throws if the target is not usable the way a GL framebuffer completeness check would. */
    void checkComplete();
}
