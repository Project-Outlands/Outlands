package net.minecraftforge.client.render.vulkan;

import net.minecraftforge.client.render.target.RenderTarget;

/**
 * A render target that is not the window: its own colour image, its own depth image, its own texture handle.
 *
 * <p><b>Why both attachments live here.</b> A target is resized as a unit and destroyed as a unit, and the two images
 * must always have the same extent - a depth attachment one pixel short of its colour attachment is a validation
 * error. Keeping them together makes that impossible to get wrong.</p>
 *
 * <p><b>The texture handle is stable across a resize.</b> Vanilla re-creates its framebuffers when the window changes
 * size and keeps the same {@code Framebuffer} object, so the handle the game cached has to keep meaning this target.
 * The images behind it are replaced; the name is not.</p>
 *
 * <p><b>Layout tracking belongs here too, and is not used yet.</b> {@link #currentLayout} is maintained so that a later
 * step can record the two barriers a target needs - into the attachment layout before it is drawn into, into the
 * sampled layout before it is read - without having to guess what state the image was left in. The task this belongs to
 * is the same one that records target switches; see {@code docs/vulkan-backend-plan.md} §11.4.</p>
 */
final class VulkanRenderTarget implements RenderTarget {

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("Outlands|render");

    private final VulkanContext context;
    private final VulkanTextures textures;
    private final int colorFormat;
    private final int depthFormat;

    /** The handle the game binds to sample this target. Allocated once, kept across resizes. */
    private final int textureId;

    private VulkanImage color;
    private VulkanImage depth;
    private int width;
    private int height;
    private boolean useDepth;
    private int glFilter = 9729;
    /** The layout the colour image is in, for the barrier a target switch has to record. */
    private int currentLayout = org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;

    VulkanRenderTarget(VulkanContext context, VulkanTextures textures, int colorFormat, int depthFormat,
                       int width, int height, boolean useDepth) {
        this.context = context;
        this.textures = textures;
        this.colorFormat = colorFormat;
        this.depthFormat = depthFormat;
        this.textureId = textures.createExternal();
        allocate(width, height, useDepth);
    }

    private void allocate(int width, int height, boolean useDepth) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("a render target needs a positive size, got " + width + "x" + height);
        }
        VulkanImage newColor = VulkanImage.createColorAttachment(context.device(), context, width, height, colorFormat);
        VulkanImage newDepth = null;
        try {
            if (useDepth) {
                newDepth = VulkanImage.createDepth(context.device(), context, width, height, depthFormat);
            }
        } catch (RuntimeException e) {
            newColor.close();
            throw e;
        }
        closeImages();
        this.color = newColor;
        this.depth = newDepth;
        this.width = width;
        this.height = height;
        this.useDepth = useDepth;
        this.currentLayout = org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
        textures.attachExternal(textureId, color.view(), width, height, textures.renderTargetSampler(glFilter));
        LOGGER.info("render target {}x{} (depth={}) -> texture {}, format {}",
                width, height, useDepth, textureId, colorFormat);
    }

    private void closeImages() {
        if (color != null) {
            color.close();
            color = null;
        }
        if (depth != null) {
            depth.close();
            depth = null;
        }
    }

    @Override
    public int textureId() {
        return textureId;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public boolean useDepth() {
        return useDepth;
    }

    @Override
    public void resize(int width, int height, boolean useDepth) {
        if (width == this.width && height == this.height && useDepth == this.useDepth) {
            return;
        }
        allocate(width, height, useDepth);
    }

    @Override
    public void setFilter(int glFilter) {
        if (glFilter == this.glFilter) {
            return;
        }
        this.glFilter = glFilter;
        if (color != null) {
            textures.attachExternal(textureId, color.view(), width, height, textures.renderTargetSampler(glFilter));
        }
    }

    /** The colour image, for the frame recorder that will draw into it. */
    VulkanImage colorImage() {
        return color;
    }

    /** The depth image, or {@code null} when the target has none. */
    VulkanImage depthImage() {
        return depth;
    }

    int colorFormat() {
        return colorFormat;
    }

    /** The layout the colour image is currently in. Maintained by whoever records a transition. */
    int currentLayout() {
        return currentLayout;
    }

    void currentLayout(int layout) {
        this.currentLayout = layout;
    }

    @Override
    public void close() {
        closeImages();
        textures.releaseExternal(textureId);
    }
}
