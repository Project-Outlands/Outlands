package net.minecraftforge.client.render.vulkan;

import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * The attachments a frame is drawing into, and the ability to reopen them.
 *
 * <p><b>Why reopening is needed at all.</b> A frame is not one rendering instance. Vanilla interleaves operations
 * that Vulkan only allows outside one - the main menu's {@code glCopyTexSubImage2D} reads the framebuffer that the
 * instance is painting. The workaround is the standard one: end the instance, do the transfer, begin a new instance
 * with {@code loadOp = LOAD} so the contents survive. That needs the attachment descriptors again, which live with
 * the swapchain, so this hands them over rather than duplicating them here.</p>
 */
interface FrameRenderTarget {

    /** Begins a rendering instance over this frame's attachments, preserving what is already there. */
    void beginRendering(VkCommandBuffer commandBuffer);

    /**
     * Makes this target's attachments valid to draw into.
     *
     * <p>A target that has been sampled by a shader is in the read-only layout, and one that was just created is in
     * {@code UNDEFINED}; neither can be a colour attachment. Called before {@link #beginRendering} on every switch into
     * this target. The window's implementation is empty - its attachments are put in place once per frame.</p>
     */
    void prepareForRendering(VkCommandBuffer commandBuffer);

    /**
     * Makes this target's colour attachment valid to sample.
     *
     * <p>Called when the frame switches away from this target, because that is the last moment the image is still in
     * the attachment layout and the first moment its contents are finished. The window's implementation is empty: its
     * image is presented, not sampled.</p>
     */
    void prepareForSampling(VkCommandBuffer commandBuffer);

    /** The colour image being drawn into, in the layout the instance leaves it in. */
    long colorImage();

    /** The colour image's view, for the copy's subresource range. */
    long colorImageView();

    int colorFormat();

    int width();

    int height();
}
