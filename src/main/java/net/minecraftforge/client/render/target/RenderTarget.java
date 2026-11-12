package net.minecraftforge.client.render.target;

/**
 * One render target, as the game's {@code Framebuffer} sees it.
 *
 * <p><b>What the game needs from a framebuffer.</b> A size, a colour attachment it can sample under a texture handle,
 * an optional depth attachment, and the ability to be resized and destroyed. That is the whole contract: everything
 * else the GL class does - FBO names, renderbuffer names, completeness checks, layout transitions - is machinery for a
 * GL driver, not a fact the game uses.</p>
 *
 * <p><b>Why a texture handle at all.</b> The game does not sample a framebuffer through an accessor: {@code
 * ShaderManager.addSamplerTexture} reads {@code Framebuffer.framebufferTexture} and binds that number. So a target has
 * to hand out a handle from the same namespace the game already binds textures in, and the renderer resolves it to the
 * image the same way it resolves an uploaded texture.</p>
 *
 * <p><b>The window is not one of these.</b> The game's main framebuffer is the swapchain's image, which the frame is
 * already painting, so it has no target object and no sampleable handle of its own - see
 * {@link net.minecraftforge.client.render.target.FramebufferBackends#forNonGlWindow()}.</p>
 *
 * <p><b>Threading.</b> Render thread only, like everything else in this package.</p>
 */
public interface RenderTarget extends AutoCloseable {

    /**
     * The texture handle the game binds to sample this target's colour attachment.
     *
     * <p>Stable for the life of the target: a resize replaces the image behind it, and the handle still means this
     * target. That is GL's rule for a framebuffer attachment and it is what lets the game cache the number.</p>
     *
     * @return the handle, or {@code 0} when the target currently has no sampleable colour (nothing allocated yet)
     */
    int textureId();

    int width();

    int height();

    boolean useDepth();

    /**
     * Replaces the attachments with new ones of this size.
     *
     * <p>GL allows it ({@code createBindFramebuffer} is called on resize), and an image cannot be resized in place in
     * Vulkan, so this is a destroy-and-recreate behind a stable handle.</p>
     */
    void resize(int width, int height, boolean useDepth);

    /** {@code glTexParameteri(GL_TEXTURE_MIN_FILTER/GL_TEXTURE_MAG_FILTER)} on the target's colour texture. */
    void setFilter(int glFilter);

    /** Releases the attachments. The handle is not reused afterwards. */
    @Override
    void close();
}
