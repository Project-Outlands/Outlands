package net.minecraftforge.client.render.vulkan;

import net.minecraftforge.client.render.target.RenderTarget;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;

import static org.lwjgl.vulkan.VK13.vkCmdPipelineBarrier2;

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
final class VulkanRenderTarget implements RenderTarget, FrameRenderTarget {

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("Outlands|render");

    private final VulkanContext context;
    private final VulkanTextures textures;
    /** Who registers this target by handle, so a released one can be forgotten. */
    private VulkanRenderTargets owner;
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
    /** Whether the depth image has been moved into the attachment layout yet. It is never sampled, so once is enough. */
    private boolean depthInAttachmentLayout;

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
        VulkanImage newDepth;
        try {
            // Allocated even when the game asked for no depth, because every pipeline in this renderer declares depth
            // state: rendering into a target without a depth attachment with such a pipeline is a validation error.
            // The depth is cleared on bind, so a target that asked for none behaves the way GL's does - a depth test
            // against a cleared buffer always passes.
            newDepth = VulkanImage.createDepth(context.device(), context, width, height, depthFormat);
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
        this.depthInAttachmentLayout = false;
        textures.attachExternal(textureId, color.view(), width, height, textures.renderTargetSampler(glFilter));
        // The image handle is in the line because the validation layers report images, not handles: correlating a
        // "this image is in the wrong layout" message with a target needs both numbers in the same place.
        LOGGER.info("render target {}x{} (depth={}) -> texture {}, image 0x{}, format {}",
                width, height, useDepth, textureId, Long.toHexString(color.image()), colorFormat);
    }

    /**
     * Releases the attachments, after making sure nothing is still reading them.
     *
     * <p><b>Why the wait is here and not somewhere cheaper.</b> The frame's command buffers reference these images,
     * and a target is destroyed from the game's side - {@code deleteFramebuffer}, or a resize - at a moment that has
     * nothing to do with the frames in flight. Measured: without the wait, the validation layers reported
     * {@code vkDestroyImageView ... currently in use by VkCommandBuffer} on shutdown, meaning the images were freed
     * while a recorded frame still named them.</p>
     *
     * <p>The cost is bounded by how often a target appears or disappears: a shader group is built once and rebuilt on
     * a window resize, which is nothing per frame. A retirement queue - hand the images to the frame that follows the
     * one in flight - is the right answer if a path ever destroys targets per frame; the terrain arena already does
     * that and this can borrow it when it matters.</p>
     */
    private void closeImages() {
        if (color == null && depth == null) {
            return;
        }
        org.lwjgl.vulkan.VK10.vkDeviceWaitIdle(context.device());
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

    @Override
    public void prepareForRendering(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int barriers = depthInAttachmentLayout ? 1 : 2;
            VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(barriers, stack);

            // The colour image goes from wherever it was - UNDEFINED when new, SHADER_READ_ONLY once a pass has read it
            // and the frame is drawing into it again - to the attachment layout. The old layout is named rather than
            // UNDEFINED on purpose: claiming UNDEFINED would tell the driver the contents do not matter, and a second
            // pass into the same target does rely on them.
            barrier.get(0)
                    .sType$Default()
                    .srcStageMask(org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT
                            | org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .srcAccessMask(org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT
                            | org.lwjgl.vulkan.VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT)
                    .dstStageMask(org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .dstAccessMask(org.lwjgl.vulkan.VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT
                            | org.lwjgl.vulkan.VK13.VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT)
                    .oldLayout(currentLayout)
                    .newLayout(org.lwjgl.vulkan.VK13.VK_IMAGE_LAYOUT_ATTACHMENT_OPTIMAL)
                    .image(color.image())
                    .subresourceRange(range(stack, org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT));

            if (barriers == 2) {
                barrier.get(1)
                        .sType$Default()
                        .srcStageMask(org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT
                                | org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT)
                        .srcAccessMask(org.lwjgl.vulkan.VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                        .dstStageMask(org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT)
                        .dstAccessMask(org.lwjgl.vulkan.VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT
                                | org.lwjgl.vulkan.VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT)
                        .oldLayout(org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED)
                        .newLayout(org.lwjgl.vulkan.VK13.VK_IMAGE_LAYOUT_ATTACHMENT_OPTIMAL)
                        .image(depth.image())
                        // Both aspects, because the depth format carries stencil too and this device does not enable
                        // separateDepthStencilLayouts: naming depth alone is what the validation layers reported as
                        // "has depth/stencil format ... but its aspectMask does not include both".
                        .subresourceRange(range(stack, org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_DEPTH_BIT
                                | org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_STENCIL_BIT));
                depthInAttachmentLayout = true;
            }
            vkCmdPipelineBarrier2(commandBuffer,
                    org.lwjgl.vulkan.VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barrier));
        }
        currentLayout = org.lwjgl.vulkan.VK13.VK_IMAGE_LAYOUT_ATTACHMENT_OPTIMAL;
    }

    @Override
    public void prepareForSampling(VkCommandBuffer commandBuffer) {
        if (currentLayout == org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(1, stack);
            barrier.get(0)
                    .sType$Default()
                    .srcStageMask(org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .srcAccessMask(org.lwjgl.vulkan.VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT)
                    .dstStageMask(org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT)
                    .dstAccessMask(org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT)
                    .oldLayout(currentLayout)
                    .newLayout(org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .image(color.image())
                    .subresourceRange(range(stack, org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT));
            vkCmdPipelineBarrier2(commandBuffer,
                    org.lwjgl.vulkan.VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barrier));
        }
        currentLayout = org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    }

    /** Yes: this image is sampled by vanilla's post-processing shaders, which assume GL's bottom-up texture origin. */
    @Override
    public boolean flipsVertically() {
        return true;
    }

    @Override
    public void beginRendering(VkCommandBuffer commandBuffer) {
        // LOAD both attachments: the game cleared the target when it wanted it cleared, and a switch into a target
        // that already holds a pass's output is a continuation, not a new image.
        RenderInstance.begin(commandBuffer, color.view(), depth.view(), width, height,
                org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_LOAD, null, false,
                org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_LOAD, 1.0F);
    }

    @Override
    public long colorImageView() {
        return color == null ? 0L : color.view();
    }

    private static org.lwjgl.vulkan.VkImageSubresourceRange range(MemoryStack stack, int aspect) {
        return org.lwjgl.vulkan.VkImageSubresourceRange.calloc(stack)
                .aspectMask(aspect)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1);
    }

    /** The colour image, for the frame recorder that will draw into it. */
    @Override
    public long colorImage() {
        return color == null ? 0L : color.image();
    }

    /** Told who owns the registry entry, so releasing the target removes it. */
    void ownedBy(VulkanRenderTargets owner) {
        this.owner = owner;
    }

    /** The depth image, for a caller that wants to inspect the target's attachments. */
    VulkanImage depthImage() {
        return depth;
    }

    @Override
    public int colorFormat() {
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
        if (owner != null) {
            owner.forget(textureId);
        }
    }
}
