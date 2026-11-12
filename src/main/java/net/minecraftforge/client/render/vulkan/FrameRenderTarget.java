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

    /**
     * Whether drawing into this target needs the viewport's y inverted.
     *
     * <p><b>Why a target differs from the window.</b> The game's projection puts the top of the screen at NDC
     * {@code -1}, which is Vulkan's own convention and lands at row 0 of a framebuffer - correct for the window, whose
     * row 0 is the top of the display. A render target is not displayed: its image is <i>sampled</i>, and GL's texture
     * convention has v=0 at the bottom, which is what every one of vanilla's post-processing shaders assumes - the
     * entity outline's vertex stages all end with {@code texCoord.y = 1.0 - y}. Without the flip the image comes out
     * vertically mirrored relative to what those shaders expect, which a symmetric edge detector hides and the final
     * composite shows as an outline offset from the thing it outlines.</p>
     *
     * <p>Measured, before this existed: a quad drawn at the top of a 256x256 target came back at the bottom of the
     * composited image, and a GL client puts it at the top.</p>
     */
    boolean flipsVertically();

    /** The colour image being drawn into, in the layout the instance leaves it in. */
    long colorImage();

    /**
     * Records a copy of the colour attachment into host memory, for the diagnostics.
     *
     * <p>Only a real target implements it: the window's image is presented and read back by the screenshot path,
     * which copies the swapchain image rather than going through this interface.</p>
     *
     * @param destination host-visible memory of at least {@code width * height * 4} bytes
     */
    void copyTo(VkCommandBuffer commandBuffer, long destination);

    /** The colour image's view, for the copy's subresource range. */
    long colorImageView();

    int colorFormat();

    int width();

    int height();
}
