package net.minecraftforge.client.render.vulkan;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandBufferSubmitInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkExtent3D;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreSubmitInfo;
import org.lwjgl.vulkan.VkSubmitInfo2;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.lwjgl.vulkan.VkViewport;
import net.minecraftforge.client.render.graph.RenderGraph;
import net.minecraftforge.client.render.graph.RenderGraphContext;

import org.lwjgl.sdl.SDLVideo;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_SUBOPTIMAL_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkAcquireNextImageKHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkCreateSwapchainKHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkDestroySwapchainKHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkGetSwapchainImagesKHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkQueuePresentKHR;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_CLEAR;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_DONT_CARE;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_FENCE_CREATE_SIGNALED_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D24_UNORM_S8_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT_S8_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_STENCIL_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TILING_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TYPE_2D;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_2D;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
import static org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_1_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkAllocateCommandBuffers;
import static org.lwjgl.vulkan.VK10.vkAllocateMemory;
import static org.lwjgl.vulkan.VK10.vkBeginCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkBindImageMemory;
import static org.lwjgl.vulkan.VK10.vkCmdSetScissor;
import static org.lwjgl.vulkan.VK10.vkCmdSetViewport;
import static org.lwjgl.vulkan.VK10.vkCreateCommandPool;
import static org.lwjgl.vulkan.VK10.vkCreateFence;
import static org.lwjgl.vulkan.VK10.vkCreateImage;
import static org.lwjgl.vulkan.VK10.vkCreateImageView;
import static org.lwjgl.vulkan.VK10.vkCreateSemaphore;
import static org.lwjgl.vulkan.VK10.vkDestroyCommandPool;
import static org.lwjgl.vulkan.VK10.vkDestroyFence;
import static org.lwjgl.vulkan.VK10.vkDestroyImage;
import static org.lwjgl.vulkan.VK10.vkDestroyImageView;
import static org.lwjgl.vulkan.VK10.vkDestroySemaphore;
import static org.lwjgl.vulkan.VK10.vkDeviceWaitIdle;
import static org.lwjgl.vulkan.VK10.vkEndCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkFreeMemory;
import static org.lwjgl.vulkan.VK10.vkGetImageMemoryRequirements;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceFormatProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceMemoryProperties;
import static org.lwjgl.vulkan.VK13.vkQueueSubmit2;
import static org.lwjgl.vulkan.VK10.vkResetCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkResetFences;
import static org.lwjgl.vulkan.VK10.vkWaitForFences;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK13.vkCmdCopyImageToBuffer;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_LAYOUT_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT;
import static org.lwjgl.vulkan.VK13.vkCmdBeginRendering;
import static org.lwjgl.vulkan.VK13.vkCmdEndRendering;
import static org.lwjgl.vulkan.VK13.vkCmdPipelineBarrier2;

/**
 * The swapchain, its images, the depth attachment, and everything that belongs to one frame in flight.
 *
 * <p>Rebuilt when the window changes size or the surface goes out of date. The {@link VulkanContext} survives that,
 * because the device does not care what size the window is.</p>
 *
 * <p><b>Frame structure.</b> Two frames in flight: one fence and one image-acquired semaphore each, because those
 * are what the CPU waits on and touches. The render-complete semaphore is per <i>swapchain image</i> instead:
 * {@code vkQueuePresentKHR} cannot signal a semaphore without an extension that is not universally available, so
 * their count follows {@code imageCount}, not the frame count. That also means rebuilding the swapchain has to
 * rebuild them.</p>
 *
 * <p><b>Image layouts.</b> Vulkan 1.3 collapsed the per-purpose attachment layouts into
 * {@code VK_IMAGE_LAYOUT_ATTACHMENT_OPTIMAL}, so every transition here names two states instead of walking a
 * matrix: undefined to attachment to draw, attachment to present to show.</p>
 */
final class VulkanSwapchain {

    private static final Logger LOGGER = LogManager.getLogger("Outlands|render");

    /** Two frames in flight. More adds latency; fewer leaves the CPU waiting on the GPU. */
    static final int FRAMES_IN_FLIGHT = 2;

    private final VulkanContext context;
    private final VkDevice device;

    private long swapchain;
    private int imageFormat;
    /** 1 means vsync, the GL default and what the game asks for initially. */
    private int swapInterval = 1;
    /** Drawn into this frame's rendering instance; emptied as it is replayed. */
    private java.util.List<net.minecraftforge.client.render.vertex.FrameDraws.Operation> pendingDraws = java.util.List.of();
    /** Replays the draws the game issued while the frame was being built. */
    private VulkanStream stream;
    private VulkanTextures textures;
    /** What the game asked of this frame; {@code null} before a backend installs one. */
    private net.minecraftforge.client.render.target.@org.jspecify.annotations.Nullable FrameRecording recording;
    private int depthFormat;
    private int extentWidth;

    /** The screenshot the game asked for, taken at the start of the frame that will satisfy it. */
    private net.minecraftforge.client.render.screenshot.Screenshots.Request pendingScreenshot;
    /** The screenshot the frame being recorded is copying out, until its fence has been waited on. */
    private net.minecraftforge.client.render.screenshot.Screenshots.Request screenshotRequest;
    private VulkanBuffer screenshotBuffer;
    private int screenshotWidth;
    private int screenshotHeight;

    private int extentHeight;

    private long[] images = new long[0];
    private long[] imageViews = new long[0];
    private long depthImage;
    private long depthImageMemory;
    private long depthImageView;

    private long commandPool;
    private final VkCommandBuffer[] commandBuffers = new VkCommandBuffer[FRAMES_IN_FLIGHT];
    private final long[] fences = new long[FRAMES_IN_FLIGHT];
    private final long[] imageAcquiredSemaphores = new long[FRAMES_IN_FLIGHT];
    private long[] renderCompleteSemaphores = new long[0];

    private int frameIndex;
    private boolean outOfDate;

    /** The frozen graph. Held here because the frame is what runs it. */
    private RenderGraph graph;

    /** Reused every frame: the render path must not allocate a context per frame. */
    private final FrameContext frameContext = new FrameContext();

    private final class FrameContext implements RenderGraphContext, FrameRenderTarget {
        VkCommandBuffer commandBuffer;
        int slot;
        int imageIndex;

        @Override
        public void beginRendering(VkCommandBuffer commandBuffer) {
            // LOAD, not CLEAR: this is a second instance over the same attachments, and its whole purpose is to keep
            // what the first one painted.
            beginRenderingInstance(commandBuffer, imageIndex,
                    org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_LOAD);
        }

        @Override
        public long colorImage() {
            return images[imageIndex];
        }

        @Override
        public long colorImageView() {
            return imageViews[imageIndex];
        }

        @Override
        public int colorFormat() {
            return imageFormat;
        }

        @Override
        public int width() {
            return extentWidth;
        }

        @Override
        public int height() {
            return extentHeight;
        }

        @Override
        public Object nativeContext() {
            return commandBuffer;
        }

        @Override
        public int frameSlot() {
            return slot;
        }
    }

    private VulkanSwapchain(VulkanContext context) {
        this.context = context;
        this.device = context.device();
    }

    static VulkanSwapchain create(VulkanContext context, long window, RenderGraph graph) {
        VulkanSwapchain swapchain = new VulkanSwapchain(context);
        // Already frozen by the backend, after it registered the nodes it implements.
        swapchain.graph = graph;
        swapchain.createCommandPool();
        swapchain.createSyncObjects();
        swapchain.rebuild(window);
        return swapchain;
    }

    int extentWidth() {
        return extentWidth;
    }

    int extentHeight() {
        return extentHeight;
    }

    /** The swapchain image format, chosen in {@link #rebuild}. */
    int imageFormat() {
        return imageFormat;
    }

    /** The depth attachment format, chosen in {@link #rebuild}. */
    int depthFormat() {
        return depthFormat;
    }

    /**
     * Draws one frame and presents it.
     *
     * <p>An out-of-date surface is a resize, not a failure: the swapchain is rebuilt and the next call draws. The
     * first frame after a rebuild is skipped, because the swapchain is rebuilt at the end of a frame rather than
     * in the middle of one.</p>
     */
    /** Hands the draws collected since the last frame to the next recording. */
    void setPendingDraws(java.util.List<net.minecraftforge.client.render.vertex.FrameDraws.Operation> draws) {
        this.pendingDraws = draws;
    }

    void drawFrame(long window) {
        if (!outOfDate && windowSizeChanged(window)) {
            // A size change does not have to surface as VK_ERROR_OUT_OF_DATE_KHR - on Wayland it does not - so the
            // window is compared against the swapchain rather than waiting for the driver to complain.
            outOfDate = true;
        }
        if (outOfDate) {
            rebuild(window);
            if (outOfDate) {
                // Still out of date: a minimised window has no usable extent. Nothing to draw.
                return;
            }
        }

        final int frame = frameIndex;
        VkCheck.check(vkWaitForFences(device, fences[frame], true, Long.MAX_VALUE), "vkWaitForFences");
        // This slot's work has finished, so the staging buffers its recorded texture copies read from are free.
        // Released here rather than inside the stream's replay because the graph's nodes run before it and upload
        // through the same cache: freeing them at replay time would free the copy the node recorded moments earlier.
        if (textures != null) {
            textures.releaseSlot(frame);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer imageIndexOut = stack.mallocInt(1);
            int acquire = vkAcquireNextImageKHR(device, swapchain, Long.MAX_VALUE,
                    imageAcquiredSemaphores[frame], 0L, imageIndexOut);
            if (acquire == VK_ERROR_OUT_OF_DATE_KHR) {
                outOfDate = true;
                return;
            }
            if (acquire != VK_SUCCESS && acquire != VK_SUBOPTIMAL_KHR) {
                throw new VkCheck.VulkanException("vkAcquireNextImageKHR returned " + acquire);
            }
            final int imageIndex = imageIndexOut.get(0);
            if (acquire == VK_SUBOPTIMAL_KHR) {
                // Presentable, but stretched or otherwise not matching the surface. Rebuild after this frame.
                outOfDate = true;
            }

            VkCheck.check(vkResetFences(device, fences[frame]), "vkResetFences");
            record(commandBuffers[frame], imageIndex, frame, stack);
            submit(frame, imageIndex, stack);
            present(imageIndex, stack);
            // The frame's fence is the only thing that says the copy has landed; the image is already on screen, so
            // waiting here costs the player nothing.
            finishScreenshot(frame);
        }

        frameIndex = (frameIndex + 1) % FRAMES_IN_FLIGHT;
    }

    /**
     * Opens a rendering instance over the frame's attachments.
     *
     * <p>Extracted because a frame is not one instance: the main menu's framebuffer copy has to happen with no pass
     * open, so the sequence is begin, draw the panorama, end, copy, begin with {@code LOAD}, draw the blur. The load
     * op is the one thing that differs between the two calls, and it is a parameter rather than a flag so the
     * difference is visible at both sites.</p>
     *
     * <p>{@code CLEAR} for the first instance: each image starts the frame at {@code LAYOUT_UNDEFINED}, so its
     * contents are by definition undefined and loading them would read whatever the previous frame left in a
     * different layout. {@code LOAD} for a resume, whose whole purpose is keeping what is already there.</p>
     */
    /**
     * Points descriptor set 1 at the game's lightmap and binds it for the frame.
     *
     * <p>Bound once rather than carried by every draw: the lightmap is one image the whole frame reads, and it changes
     * when the sun moves or a torch flickers nearby, not per draw. The descriptor set is rewritten only when the image
     * it points at actually changes, because {@code vkUpdateDescriptorSets} is not free.</p>
     */
    private void bindLightmap(VkCommandBuffer commandBuffer, int slot) {
        stream.bindLightmap(commandBuffer, slot);
    }

    /**
     * Copies the finished image into host memory, so {@link #finishScreenshot} can write it out.
     *
     * <p>The image is in {@code ATTACHMENT_OPTIMAL} here, which no copy may read; it moves to
     * {@code TRANSFER_SRC_OPTIMAL} for the copy and the present barrier takes it from there. Rows are copied as they
     * are - the window's format is BGRA on every driver measured - and the swizzle and the flip happen on the CPU,
     * where a screenshot is not on any hot path.</p>
     */
    private void recordScreenshotCopy(VkCommandBuffer commandBuffer, int imageIndex,
                                      net.minecraftforge.client.render.screenshot.Screenshots.Request request,
                                      MemoryStack stack) {
        int width = Math.min(request.width(), extentWidth);
        int height = Math.min(request.height(), extentHeight);
        long bytes = (long) width * height * 4L;
        if (bytes <= 0 || bytes > Integer.MAX_VALUE) {
            net.minecraftforge.client.render.screenshot.Screenshots.failed(request,
                    "a " + width + "x" + height + " picture is not a size this backend can read back");
            return;
        }
        screenshotWidth = width;
        screenshotHeight = height;
        screenshotRequest = request;
        screenshotBuffer = VulkanBuffer.createReadback(device, context, (int) bytes);

        VkImageMemoryBarrier2.Buffer toTransfer = VkImageMemoryBarrier2.calloc(1, stack);
        toTransfer.get(0)
                .sType$Default()
                .srcStageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT)
                .srcAccessMask(VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_TRANSFER_BIT)
                .dstAccessMask(VK_ACCESS_2_TRANSFER_READ_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_ATTACHMENT_OPTIMAL)
                .newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                .image(images[imageIndex])
                .subresourceRange(colorRange(stack));
        vkCmdPipelineBarrier2(commandBuffer, VkDependencyInfo.calloc(stack)
                .sType$Default().pImageMemoryBarriers(toTransfer));

        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
        region.get(0).bufferOffset(0L).bufferRowLength(0).bufferImageHeight(0);
        region.get(0).imageSubresource()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).imageOffset().set(0, 0, 0);
        region.get(0).imageExtent().set(width, height, 1);
        vkCmdCopyImageToBuffer(commandBuffer, images[imageIndex], VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                screenshotBuffer.handle(), region);
    }

    /** Writes out the screenshot whose copy the submitted frame contains, if there is one. */
    private void finishScreenshot(int frame) {
        if (screenshotRequest == null || screenshotBuffer == null) {
            return;
        }
        net.minecraftforge.client.render.screenshot.Screenshots.Request request = screenshotRequest;
        screenshotRequest = null;
        try {
            VkCheck.check(vkWaitForFences(device, fences[frame], true, Long.MAX_VALUE), "vkWaitForFences");
            byte[] bgra = screenshotBuffer.read(screenshotWidth * screenshotHeight * 4);
            byte[] rgba = net.minecraftforge.client.render.screenshot.Screenshots.swizzleBgra(
                    java.nio.ByteBuffer.wrap(bgra), screenshotWidth, screenshotHeight);
            net.minecraftforge.client.render.screenshot.Screenshots.deliver(request, rgba, screenshotWidth,
                    screenshotHeight);
        } finally {
            screenshotBuffer.close();
            screenshotBuffer = null;
        }
    }

    private void beginRenderingInstance(VkCommandBuffer commandBuffer, int imageIndex, int colorLoadOp) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // The colour comes from what the game asked for, not from a constant: GlStateManager.clear(16640) and
            // Framebuffer.setFramebufferColor both happen before Display.update() hands the frame over. A resumed
            // instance ignores it, because a load op of LOAD does not consult the clear value.
            float[] requested = recording != null
                    ? recording.pendingClearColor() : new float[] {0.0F, 0.0F, 0.0F, 1.0F};
            VkClearValue clearColor = VkClearValue.calloc(stack);
            // Alpha is forced to 1, whatever the game asked for.
            //
            // The window is opaque, so the framebuffer's alpha is composited nowhere - except by the one thing that
            // reads it back: the main menu's blur copies a region of the framebuffer into a texture and draws it with
            // SRC_ALPHA blending. With a destination alpha of 0 every blurred layer is fully transparent and the
            // sharp panorama underneath shows instead, which is exactly what this renderer was doing while the GL
            // client showed a blurred background. Measured: the copied region's alpha was 0 in every frame.
            //
            // GL reaches alpha 1 by a different route - its default framebuffer reports opaque - so writing 1 here
            // reproduces that, which is what makes the blur visible.
            clearColor.color().float32(0, requested[0]).float32(1, requested[1]).float32(2, requested[2])
                    .float32(3, 1.0F);
            VkRenderingAttachmentInfo.Buffer colorAttachment = VkRenderingAttachmentInfo.calloc(1, stack);
            colorAttachment.get(0)
                    .sType$Default()
                    .imageView(imageViews[imageIndex])
                    .imageLayout(VK_IMAGE_LAYOUT_ATTACHMENT_OPTIMAL)
                    .loadOp(colorLoadOp)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .clearValue(clearColor);

            VkClearValue clearDepth = VkClearValue.calloc(stack);
            clearDepth.depthStencil()
                    .depth((float) (recording != null ? recording.pendingClearDepth() : 1.0)).stencil(0);
            VkRenderingAttachmentInfo depthAttachment = VkRenderingAttachmentInfo.calloc(stack)
                    .sType$Default()
                    .imageView(depthImageView)
                    .imageLayout(VK_IMAGE_LAYOUT_ATTACHMENT_OPTIMAL)
                    // LOADed on resume as well: the blur reads colour, but a depth clear mid-frame would discard the
                    // occlusion the earlier draws established for anything drawn afterwards.
                    .loadOp(colorLoadOp)
                    // STOREd, not discarded, for the same reason. A frame is not one instance: each framebuffer copy
                    // in the main menu's blur closes the instance and opens another with LOAD. DONT_CARE leaves the
                    // depth contents undefined when the instance ends, so the reopened instance loads depth nobody
                    // wrote - and whether the panorama drawn into the corner survives that is up to the driver. The
                    // symptom was a menu background that was a sharp off-screen panorama on one run and a blurred
                    // flat colour on the next, from identical code.
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .clearValue(clearDepth);

            VkRect2D renderArea = VkRect2D.calloc(stack);
            renderArea.offset().x(0).y(0);
            renderArea.extent().width(extentWidth).height(extentHeight);

            VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack)
                    .sType$Default()
                    .renderArea(renderArea)
                    .layerCount(1)
                    .pColorAttachments(colorAttachment)
                    .pDepthAttachment(depthAttachment);
            vkCmdBeginRendering(commandBuffer, renderingInfo);

            // Viewport and scissor are dynamic state, so a resize never needs a new pipeline - and a resumed
            // instance starts with them undefined, so they are set here rather than once per frame.
            VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
            viewport.get(0).x(0.0f).y(0.0f).width(extentWidth).height(extentHeight).minDepth(0.0f).maxDepth(1.0f);
            vkCmdSetViewport(commandBuffer, 0, viewport);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.get(0).offset().x(0).y(0);
            scissor.get(0).extent().width(extentWidth).height(extentHeight);
            vkCmdSetScissor(commandBuffer, 0, scissor);
        }
    }

    private void record(VkCommandBuffer commandBuffer, int imageIndex, int slot, MemoryStack stack) {
        // Whatever the game asked for while building this frame belongs to it: the frame in flight is the picture the
        // player was looking at when they pressed the key.
        pendingScreenshot = net.minecraftforge.client.render.screenshot.Screenshots.take();
        VkCheck.check(vkResetCommandBuffer(commandBuffer, 0), "vkResetCommandBuffer");
        VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                .sType$Default()
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
        VkCheck.check(vkBeginCommandBuffer(commandBuffer, beginInfo), "vkBeginCommandBuffer");

        // Textures first, while nothing is rendering: a first-use upload is a buffer-to-image copy plus two layout
        // transitions, all of which Vulkan forbids inside a render pass. Done here it is recorded once, before the
        // instance opens, and the replay below only binds.
        if (stream != null) {
            // Every node's declared texture, not just the terrain's: a node that samples something has the same
            // ordering requirement, and the graph is where the node list already lives.
            java.util.List<net.minecraftforge.client.render.graph.RenderGraphNode> nodes = graph.nodes();
            int[] nodeTextures = new int[nodes.size()];
            for (int i = 0; i < nodes.size(); i++) {
                nodeTextures[i] = nodes.get(i).requestedTexture();
            }
            stream.prepareTextures(commandBuffer, pendingDraws, slot, nodeTextures);
            for (net.minecraftforge.client.render.graph.RenderGraphNode node : nodes) {
                node.bindTextures(stream.descriptorSetOf(node.requestedTexture()));
            }
        }

        // Both attachments start undefined: nothing from a previous frame is needed, so the barriers only move
        // layouts and do not have to make anything available.
        VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(2, stack);
        barriers.get(0)
                .sType$Default()
                .srcStageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT)
                .srcAccessMask(0)
                .dstStageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT)
                .dstAccessMask(VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .newLayout(VK_IMAGE_LAYOUT_ATTACHMENT_OPTIMAL)
                .image(images[imageIndex])
                .subresourceRange(colorRange(stack));
        barriers.get(1)
                .sType$Default()
                .srcStageMask(VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT)
                .srcAccessMask(VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT)
                .dstAccessMask(VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .newLayout(VK_IMAGE_LAYOUT_ATTACHMENT_OPTIMAL)
                .image(depthImage)
                .subresourceRange(depthBarrierRange(stack));

        VkDependencyInfo dependencyInfo = VkDependencyInfo.calloc(stack)
                .sType$Default()
                .pImageMemoryBarriers(barriers);
        vkCmdPipelineBarrier2(commandBuffer, dependencyInfo);

        beginRenderingInstance(commandBuffer, imageIndex,
                org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_CLEAR);

        // The phases run inside the rendering instance because they draw into these attachments. Each one is a
        // no-op for now; the graph is what makes "replace terrain.layer" a thing a mod can do.
        frameContext.commandBuffer = commandBuffer;
        frameContext.slot = slot;
        frameContext.imageIndex = imageIndex;
        graph.run(frameContext);
        frameContext.commandBuffer = null;

        // The game's own draws come after the graph, because they are what vanilla issues after it establishes the
        // world: the world itself, then the GUI on top. They were collected while the frame was being built -
        // vanilla issues them before Display.update() hands the frame over - so this is where they become commands.
        if (stream != null && !pendingDraws.isEmpty()) {
            bindLightmap(commandBuffer, slot);
            stream.replay(commandBuffer, pendingDraws, slot, extentWidth, extentHeight, frameContext);
            pendingDraws = java.util.List.of();
        }

        vkCmdEndRendering(commandBuffer);

        // A screenshot asks for the frame the player is looking at, which is this one: it is taken here, after the
        // last draw and before the image becomes presentable. The copy has to be outside the rendering instance,
        // which is why the request is satisfied at this point rather than where the game asked.
        net.minecraftforge.client.render.screenshot.Screenshots.Request screenshot = pendingScreenshot;
        pendingScreenshot = null;
        if (screenshot != null) {
            recordScreenshotCopy(commandBuffer, imageIndex, screenshot, stack);
        }

        VkImageMemoryBarrier2.Buffer presentBarrier = VkImageMemoryBarrier2.calloc(1, stack);
        presentBarrier.get(0)
                .sType$Default()
                .srcStageMask(screenshot != null ? VK_PIPELINE_STAGE_2_TRANSFER_BIT
                        : VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT)
                .srcAccessMask(screenshot != null ? VK_ACCESS_2_TRANSFER_READ_BIT
                        : VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT)
                .dstAccessMask(0)
                .oldLayout(screenshot != null ? VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
                        : VK_IMAGE_LAYOUT_ATTACHMENT_OPTIMAL)
                .newLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
                .image(images[imageIndex])
                .subresourceRange(colorRange(stack));
        VkDependencyInfo presentDependency = VkDependencyInfo.calloc(stack)
                .sType$Default()
                .pImageMemoryBarriers(presentBarrier);
        vkCmdPipelineBarrier2(commandBuffer, presentDependency);

        VkCheck.check(vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer");
    }

    /**
     * Copies the finished swapchain image out for the first few frames, so the picture that reaches the screen can be
     * looked at directly.
     *
     * <p>Every other diagnostic in this renderer reads an intermediate: the copied region, the atlas, the vertex
     * data. All of those have been correct while the screen was wrong, which is exactly the case the presented image
     * settles and they cannot.</p>
     */
    private void submit(int frame, int imageIndex, MemoryStack stack) {
        VkSemaphoreSubmitInfo.Buffer wait = VkSemaphoreSubmitInfo.calloc(1, stack);
        wait.get(0)
                .sType$Default()
                .semaphore(imageAcquiredSemaphores[frame])
                .stageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT);
        VkSemaphoreSubmitInfo.Buffer signal = VkSemaphoreSubmitInfo.calloc(1, stack);
        signal.get(0)
                .sType$Default()
                .semaphore(renderCompleteSemaphores[imageIndex])
                .stageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT);
        VkCommandBufferSubmitInfo.Buffer commandBufferInfo = VkCommandBufferSubmitInfo.calloc(1, stack);
        commandBufferInfo.get(0).sType$Default().commandBuffer(commandBuffers[frame]);

        VkSubmitInfo2.Buffer submitInfo = VkSubmitInfo2.calloc(1, stack);
        submitInfo.get(0)
                .sType$Default()
                .pWaitSemaphoreInfos(wait)
                .pCommandBufferInfos(commandBufferInfo)
                .pSignalSemaphoreInfos(signal);
        VkCheck.check(vkQueueSubmit2(context.graphicsQueue(), submitInfo, fences[frame]), "vkQueueSubmit2");
    }

    private void present(int imageIndex, MemoryStack stack) {
        VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc(stack)
                .sType$Default()
                .pWaitSemaphores(stack.longs(renderCompleteSemaphores[imageIndex]))
                .swapchainCount(1)
                .pSwapchains(stack.longs(swapchain))
                .pImageIndices(stack.ints(imageIndex));
        int result = vkQueuePresentKHR(context.graphicsQueue(), presentInfo);
        if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_SUBOPTIMAL_KHR) {
            outOfDate = true;
        } else if (result != VK_SUCCESS) {
            throw new VkCheck.VulkanException("vkQueuePresentKHR returned " + result);
        }
    }

    /** Whether the window's pixel size differs from the swapchain's. A minimised window reports 0 and is ignored. */
    private boolean windowSizeChanged(long window) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            SDLVideo.SDL_GetWindowSizeInPixels(window, width, height);
            int w = width.get(0);
            int h = height.get(0);
            if (w <= 0 || h <= 0) {
                return false;
            }
            return w != extentWidth || h != extentHeight;
        }
    }

    private static VkImageSubresourceRange colorRange(MemoryStack stack) {
        return VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .levelCount(1)
                .layerCount(1);
    }

    /**
     * The subresource range for barriers on the depth attachment.
     *
     * <p>The chosen format has both a depth and a stencil aspect, and {@code separateDepthStencilLayouts} is not
     * enabled, so the spec requires both bits here even though only depth is written. The image <i>view</i> still
     * asks for depth alone: a view selects what is sampled, not what is transitioned.</p>
     */
    private static VkImageSubresourceRange depthBarrierRange(MemoryStack stack) {
        return VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT)
                .levelCount(1)
                .layerCount(1);
    }

    private static VkImageSubresourceRange depthViewRange(MemoryStack stack) {
        return VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                .levelCount(1)
                .layerCount(1);
    }

    private void createCommandPool() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo createInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    // Command buffers are reset explicitly before every recording.
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(context.graphicsFamily());
            LongBuffer poolOut = stack.mallocLong(1);
            VkCheck.check(vkCreateCommandPool(device, createInfo, null, poolOut), "vkCreateCommandPool");
            commandPool = poolOut.get(0);

            VkCommandBufferAllocateInfo allocateInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType$Default()
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(FRAMES_IN_FLIGHT);
            PointerBuffer buffers = stack.mallocPointer(FRAMES_IN_FLIGHT);
            VkCheck.check(vkAllocateCommandBuffers(device, allocateInfo, buffers), "vkAllocateCommandBuffers");
            for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
                commandBuffers[i] = new VkCommandBuffer(buffers.get(i), device);
            }
        }
    }

    private void createSyncObjects() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFenceCreateInfo fenceCreateInfo = VkFenceCreateInfo.calloc(stack)
                    .sType$Default()
                    // Signalled, or the very first wait would block until its timeout.
                    .flags(VK_FENCE_CREATE_SIGNALED_BIT);
            VkSemaphoreCreateInfo semaphoreCreateInfo = VkSemaphoreCreateInfo.calloc(stack).sType$Default();

            LongBuffer handleOut = stack.mallocLong(1);
            for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
                VkCheck.check(vkCreateFence(device, fenceCreateInfo, null, handleOut), "vkCreateFence");
                fences[i] = handleOut.get(0);
                VkCheck.check(vkCreateSemaphore(device, semaphoreCreateInfo, null, handleOut), "vkCreateSemaphore");
                imageAcquiredSemaphores[i] = handleOut.get(0);
            }
        }
    }

    /**
     * Requests a swap interval, rebuilding so it takes effect.
     *
     * <p>Mapped to a present mode rather than stored: {@code 1} is FIFO, which is vsync; {@code 0} is mailbox when
     * the surface offers it, because that is uncapped without tearing, and immediate otherwise. FIFO is the only
     * mode every implementation guarantees, so wanting it off can end in it being on - that fallback is logged,
     * since otherwise the setting silently does nothing.</p>
     */
    /** Installs the replay path for the game's own draws. */
    void setTextures(VulkanTextures textures) {
        this.textures = textures;
    }

    void setStream(VulkanStream stream) {
        this.stream = stream;
    }

    /** Installs the holder for the game's per-frame requests. */
    void setFrameRecording(net.minecraftforge.client.render.target.FrameRecording recording) {
        this.recording = recording;
    }

    void setSwapInterval(int interval, long window) {
        if (interval == swapInterval) {
            return;
        }
        swapInterval = interval;
        rebuild(window);
    }

    /** (Re)creates the swapchain and everything sized by the window. {@code oldSwapchain} is 0 on the first call. */
    private void rebuild(long window) {
        vkDeviceWaitIdle(device);
        destroySizedResources();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSurfaceCapabilitiesKHR capabilities = VkSurfaceCapabilitiesKHR.malloc(stack);
            VkCheck.check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(context.physicalDevice(), context.surface(),
                    capabilities), "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");

            imageFormat = chooseSurfaceFormat(stack);
            int presentMode = choosePresentMode(stack);
            depthFormat = chooseDepthFormat(stack);

            // 0xFFFFFFFF means "the surface decides", which is what Wayland reports; asking the window is the only
            // way to get a usable extent there.
            if (capabilities.currentExtent().width() != 0xFFFFFFFF) {
                extentWidth = capabilities.currentExtent().width();
                extentHeight = capabilities.currentExtent().height();
            } else {
                IntBuffer width = stack.mallocInt(1);
                IntBuffer height = stack.mallocInt(1);
                SDLVideo.SDL_GetWindowSizeInPixels(window, width, height);
                extentWidth = clamp(width.get(0), capabilities.minImageExtent().width(),
                        capabilities.maxImageExtent().width());
                extentHeight = clamp(height.get(0), capabilities.minImageExtent().height(),
                        capabilities.maxImageExtent().height());
            }
            if (extentWidth <= 0 || extentHeight <= 0) {
                // A minimised window has no surface to draw into. Stay out of date until it comes back.
                outOfDate = true;
                return;
            }

            int imageCount = capabilities.minImageCount() + 1;
            if (capabilities.maxImageCount() > 0 && imageCount > capabilities.maxImageCount()) {
                imageCount = capabilities.maxImageCount();
            }

            VkSwapchainCreateInfoKHR createInfo = VkSwapchainCreateInfoKHR.calloc(stack)
                    .sType$Default()
                    .surface(context.surface())
                    .minImageCount(imageCount)
                    .imageFormat(imageFormat)
                    .imageColorSpace(VK_COLOR_SPACE_SRGB_NONLINEAR_KHR);
            VkExtent2D imageExtent = VkExtent2D.calloc(stack);
            imageExtent.width(extentWidth).height(extentHeight);
            createInfo
                    .imageExtent(imageExtent)
                    .imageArrayLayers(1)
                    // TRANSFER_SRC so the main menu's blur can read the framebuffer it is painting into.
                    .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                    .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .preTransform(capabilities.currentTransform())
                    .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                    .presentMode(presentMode)
                    .clipped(true)
                    // 0, not the previous handle: it was destroyed above, and a destroyed handle is not a
                    // valid oldSwapchain. Reusing it would only save the driver some work anyway.
                    .oldSwapchain(0L);

            LongBuffer swapchainOut = stack.mallocLong(1);
            VkCheck.check(vkCreateSwapchainKHR(device, createInfo, null, swapchainOut), "vkCreateSwapchainKHR");
            swapchain = swapchainOut.get(0);

            IntBuffer countOut = stack.mallocInt(1);
            VkCheck.check(vkGetSwapchainImagesKHR(device, swapchain, countOut, null), "vkGetSwapchainImagesKHR");
            LongBuffer imagesOut = stack.mallocLong(countOut.get(0));
            VkCheck.check(vkGetSwapchainImagesKHR(device, swapchain, countOut, imagesOut), "vkGetSwapchainImagesKHR");

            images = new long[imagesOut.capacity()];
            imageViews = new long[imagesOut.capacity()];
            for (int i = 0; i < imagesOut.capacity(); i++) {
                images[i] = imagesOut.get(i);
                imageViews[i] = createImageView(images[i], imageFormat, VK_IMAGE_ASPECT_COLOR_BIT, stack);
            }

            createDepthResources(stack);
            createRenderCompleteSemaphores(images.length);

            outOfDate = false;
            LOGGER.info("Vulkan swapchain: {}x{}, {} images, {}, present mode {}, depth {}", extentWidth, extentHeight,
                    images.length, formatName(imageFormat), presentMode, formatName(depthFormat));
        }
    }

    private void createDepthResources(MemoryStack stack) {
        VkImageCreateInfo imageCreateInfo = VkImageCreateInfo.calloc(stack)
                .sType$Default()
                .imageType(VK_IMAGE_TYPE_2D)
                .format(depthFormat);
        VkExtent3D depthExtent = VkExtent3D.calloc(stack);
        depthExtent.width(extentWidth).height(extentHeight).depth(1);
        imageCreateInfo
                .extent(depthExtent)
                .mipLevels(1)
                .arrayLayers(1)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);

        LongBuffer imageOut = stack.mallocLong(1);
        VkCheck.check(vkCreateImage(device, imageCreateInfo, null, imageOut), "vkCreateImage");
        depthImage = imageOut.get(0);

        // Depth is device-local and never read by the CPU. A real allocator (VMA) comes later; M1 has exactly one
        // image and nothing to suballocate, so it allocates one block and says so.
        VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
        vkGetImageMemoryRequirements(device, depthImage, requirements);
        VkMemoryAllocateInfo allocateInfo = VkMemoryAllocateInfo.calloc(stack)
                .sType$Default()
                .allocationSize(requirements.size())
                .memoryTypeIndex(memoryType(requirements.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, stack));
        LongBuffer memoryOut = stack.mallocLong(1);
        VkCheck.check(vkAllocateMemory(device, allocateInfo, null, memoryOut), "vkAllocateMemory");
        depthImageMemory = memoryOut.get(0);
        VkCheck.check(vkBindImageMemory(device, depthImage, depthImageMemory, 0L), "vkBindImageMemory");

        depthImageView = createImageView(depthImage, depthFormat, VK_IMAGE_ASPECT_DEPTH_BIT, stack);
    }

    private int memoryType(int allowedBits, int required, MemoryStack stack) {
        VkPhysicalDeviceMemoryProperties memoryProperties = VkPhysicalDeviceMemoryProperties.malloc(stack);
        vkGetPhysicalDeviceMemoryProperties(context.physicalDevice(), memoryProperties);
        for (int i = 0; i < memoryProperties.memoryTypeCount(); i++) {
            boolean allowed = (allowedBits & (1 << i)) != 0;
            boolean matches = (memoryProperties.memoryTypes(i).propertyFlags() & required) == required;
            if (allowed && matches) {
                return i;
            }
        }
        throw new VkCheck.VulkanException("no memory type for depth image (bits 0x" + Integer.toHexString(allowedBits) + ")");
    }

    private long createImageView(long image, int format, int aspect, MemoryStack stack) {
        VkImageViewCreateInfo createInfo = VkImageViewCreateInfo.calloc(stack)
                .sType$Default()
                .image(image)
                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                .format(format)
                .subresourceRange(aspect == VK_IMAGE_ASPECT_DEPTH_BIT ? depthViewRange(stack) : colorRange(stack));
        LongBuffer viewOut = stack.mallocLong(1);
        VkCheck.check(vkCreateImageView(device, createInfo, null, viewOut), "vkCreateImageView");
        return viewOut.get(0);
    }

    private void createRenderCompleteSemaphores(int count) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreCreateInfo createInfo = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            LongBuffer handleOut = stack.mallocLong(1);
            renderCompleteSemaphores = new long[count];
            for (int i = 0; i < count; i++) {
                VkCheck.check(vkCreateSemaphore(device, createInfo, null, handleOut), "vkCreateSemaphore");
                renderCompleteSemaphores[i] = handleOut.get(0);
            }
        }
    }

    private int chooseSurfaceFormat(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        VkCheck.check(vkGetPhysicalDeviceSurfaceFormatsKHR(context.physicalDevice(), context.surface(), count, null),
                "vkGetPhysicalDeviceSurfaceFormatsKHR");
        VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.malloc(count.get(0), stack);
        VkCheck.check(vkGetPhysicalDeviceSurfaceFormatsKHR(context.physicalDevice(), context.surface(), count, formats),
                "vkGetPhysicalDeviceSurfaceFormatsKHR");

        // B8G8R8A8_UNORM, deliberately, not the SRGB variant that is the "obviously correct" choice.
        //
        // The game was authored against GL without an sRGB framebuffer: every byte it writes is a gamma-space value,
        // every colour it sets is gamma-space, and all of its blending maths assumes that. Rendering into an sRGB
        // attachment would encode each stored fragment again, and the parts that cannot be decoded on the way in -
        // vertex colours, which arrive as plain numbers - come out visibly wrong: the font's 25% shadow at 0x3F
        // renders as 0x88, which reads as a pale ghost behind every string rather than a dark shadow.
        //
        // Matching the original's encoding is the faithful choice here, not the physically tidy one. A colour-managed
        // pipeline would have to convert colours at every one of the game's own call sites, which is not a renderer
        // change but a rewrite of how the game specifies colour.
        for (int i = 0; i < formats.capacity(); i++) {
            VkSurfaceFormatKHR format = formats.get(i);
            if (format.format() == VK_FORMAT_B8G8R8A8_UNORM
                    && format.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                return format.format();
            }
        }
        for (int i = 0; i < formats.capacity(); i++) {
            VkSurfaceFormatKHR format = formats.get(i);
            if (format.format() == VK_FORMAT_B8G8R8A8_UNORM) {
                return format.format();
            }
        }
        return formats.get(0).format();
    }

    private int choosePresentMode(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        VkCheck.check(vkGetPhysicalDeviceSurfacePresentModesKHR(context.physicalDevice(), context.surface(), count, null),
                "vkGetPhysicalDeviceSurfacePresentModesKHR");
        IntBuffer modes = stack.mallocInt(count.get(0));
        VkCheck.check(vkGetPhysicalDeviceSurfacePresentModesKHR(context.physicalDevice(), context.surface(), count, modes),
                "vkGetPhysicalDeviceSurfacePresentModesKHR");
        // Interval > 0 means vsync, and FIFO is the only mode guaranteed to exist that means it.
        if (swapInterval > 0) {
            for (int i = 0; i < modes.capacity(); i++) {
                if (modes.get(i) == VK_PRESENT_MODE_FIFO_KHR) {
                    return VK_PRESENT_MODE_FIFO_KHR;
                }
            }
            return modes.get(0);
        }
        // Interval 0 wants uncapped: mailbox first because it does not tear, immediate second.
        for (int wanted : new int[] {VK_PRESENT_MODE_MAILBOX_KHR, VK_PRESENT_MODE_IMMEDIATE_KHR}) {
            for (int i = 0; i < modes.capacity(); i++) {
                if (modes.get(i) == wanted) {
                    return wanted;
                }
            }
        }
        LOGGER.warn("vsync was turned off, but this surface offers neither mailbox nor immediate presentation; "
                + "staying on FIFO, so the frame rate remains capped");
        return VK_PRESENT_MODE_FIFO_KHR;
    }

    private int chooseDepthFormat(MemoryStack stack) {
        // The spec guarantees at least one of these two is usable as a depth attachment.
        int[] candidates = {VK_FORMAT_D32_SFLOAT_S8_UINT, VK_FORMAT_D24_UNORM_S8_UINT};
        VkFormatProperties properties = VkFormatProperties.malloc(stack);
        for (int candidate : candidates) {
            vkGetPhysicalDeviceFormatProperties(context.physicalDevice(), candidate, properties);
            if ((properties.optimalTilingFeatures() & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0) {
                return candidate;
            }
        }
        throw new VkCheck.VulkanException("no depth format usable as a depth attachment");
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String formatName(int format) {
        return switch (format) {
            case VK_FORMAT_B8G8R8A8_SRGB -> "B8G8R8A8_SRGB";
            case VK_FORMAT_D32_SFLOAT_S8_UINT -> "D32_SFLOAT_S8_UINT";
            case VK_FORMAT_D24_UNORM_S8_UINT -> "D24_UNORM_S8_UINT";
            case VK_FORMAT_UNDEFINED -> "UNDEFINED";
            default -> "format#" + format;
        };
    }

    private void destroySizedResources() {
        if (depthImageView != 0L) {
            vkDestroyImageView(device, depthImageView, null);
            depthImageView = 0L;
        }
        if (depthImage != 0L) {
            vkDestroyImage(device, depthImage, null);
            depthImage = 0L;
        }
        if (depthImageMemory != 0L) {
            vkFreeMemory(device, depthImageMemory, null);
            depthImageMemory = 0L;
        }
        for (long view : imageViews) {
            if (view != 0L) {
                vkDestroyImageView(device, view, null);
            }
        }
        imageViews = new long[0];
        images = new long[0];
        for (long semaphore : renderCompleteSemaphores) {
            vkDestroySemaphore(device, semaphore, null);
        }
        renderCompleteSemaphores = new long[0];
        if (swapchain != 0L) {
            vkDestroySwapchainKHR(device, swapchain, null);
            swapchain = 0L;
        }
    }

    void close() {
        vkDeviceWaitIdle(device);
        destroySizedResources();
        for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
            vkDestroyFence(device, fences[i], null);
            vkDestroySemaphore(device, imageAcquiredSemaphores[i], null);
        }
        vkDestroyCommandPool(device, commandPool, null);
    }
}
