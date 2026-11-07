package net.minecraftforge.client.render.vulkan;

import net.minecraftforge.client.render.vertex.FrameDraws;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TILING_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TYPE_2D;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_2D;
import static org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_1_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.vkAllocateMemory;
import static org.lwjgl.vulkan.VK10.vkBindImageMemory;
import static org.lwjgl.vulkan.VK10.vkCreateImage;
import static org.lwjgl.vulkan.VK10.vkCreateImageView;
import static org.lwjgl.vulkan.VK10.vkDestroyImage;
import static org.lwjgl.vulkan.VK10.vkDestroyImageView;
import static org.lwjgl.vulkan.VK10.vkFreeMemory;
import static org.lwjgl.vulkan.VK10.vkGetImageMemoryRequirements;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceMemoryProperties;
import static org.lwjgl.vulkan.VK13.vkCmdPipelineBarrier2;

/**
 * One sampled 2D image: device-local, optimally tiled, single mip level.
 *
 * <p><b>Format {@code B8G8R8A8_UNORM} is the pixel contract, not a choice.</b> The texture backend holds
 * {@code 0xAARRGGBB} ints, which on a little-endian machine is the byte order B, G, R, A - the same bytes a GL
 * {@code GL_BGRA} upload describes. Naming that byte order in the image format means the staging copy is a
 * {@code memcpy} with no channel swizzle, and neither side has to remember which order the other meant.</p>
 *
 * <p><b>Rows are flipped at upload, not at sample.</b> GL's texture origin is the bottom-left and Vulkan's is the
 * top-left, so the same vertex coordinates would sample a mirrored image. Flipping here keeps every sampler
 * coordinate in the game's own convention - the one vanilla computed and the one a mod passes to
 * {@code glTexCoord2f} - instead of making the shader the only place that knows the image is upside down.</p>
 *
 * <p><b>Uploads are recorded into the caller's command buffer.</b> There is no separate submit: a texture is created
 * in the frame that first samples it, and that frame is already recording. The consequence is that the staging
 * buffer has to outlive the recording, so its owner keeps it until the frame's fence has been waited on - the same
 * rule the stream's vertex buffer follows.</p>
 */
final class VulkanImage implements AutoCloseable {

    private final VkDevice device;
    private final long image;
    private final long memory;
    private final long view;
    private final int width;
    private final int height;
    private final int format;
    /** Whether this image has been in a layout it can be copied into and sampled from. */
    private boolean initialised;

    private VulkanImage(VkDevice device, long image, long memory, long view, int width, int height, int format) {
        this.device = device;
        this.image = image;
        this.memory = memory;
        this.view = view;
        this.width = width;
        this.height = height;
        this.format = format;
    }

    int format() {
        return format;
    }

    long view() {
        return view;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    static VulkanImage create(VkDevice device, VulkanContext context, int width, int height) {
        return create(device, context, width, height, VK_FORMAT_B8G8R8A8_UNORM);
    }

    /**
     * Creates an image with an explicit format.
     *
     * <p>Used for textures that receive a framebuffer copy: the framebuffer holds sRGB-encoded values, so the image
     * has to say so for sampling to decode them. Uploaded file data stays on the plain UNORM format, because that is
     * the convention every other uploaded texture already follows.</p>
     */
    static VulkanImage create(VkDevice device, VulkanContext context, int width, int height, int format) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("an image needs a positive size, got " + width + "x" + height);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo createInfo = VkImageCreateInfo.calloc(stack)
                    .sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(format)
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    // TRANSFER_SRC as well, so the upload verification can read the image back. Without it the copy
                    // is invalid - the validation layers named it as VUID-vkCmdCopyImageToBuffer-srcImage-00186 - and
                    // an invalid copy that happens to work is not a verification.
                    .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                            | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    // Left UNDEFINED so the first upload's barrier does not have to claim contents it never had.
                    .initialLayout(org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED);
            createInfo.extent().width(width).height(height).depth(1);
            LongBuffer imageOut = stack.mallocLong(1);
            VkCheck.check(vkCreateImage(device, createInfo, null, imageOut), "vkCreateImage");
            long image = imageOut.get(0);

            long memory = 0L;
            long view = 0L;
            try {
                VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
                vkGetImageMemoryRequirements(device, image, requirements);
                VkMemoryAllocateInfo allocateInfo = VkMemoryAllocateInfo.calloc(stack)
                        .sType$Default()
                        .allocationSize(requirements.size())
                        .memoryTypeIndex(deviceLocal(context, requirements.memoryTypeBits(), stack));
                LongBuffer memoryOut = stack.mallocLong(1);
                VkCheck.check(vkAllocateMemory(device, allocateInfo, null, memoryOut), "vkAllocateMemory");
                memory = memoryOut.get(0);
                VkCheck.check(vkBindImageMemory(device, image, memory, 0L), "vkBindImageMemory");

                view = createView(device, image, stack, format);
                return new VulkanImage(device, image, memory, view, width, height, format);
            } catch (RuntimeException e) {
                if (view != 0L) {
                    vkDestroyImageView(device, view, null);
                }
                if (memory != 0L) {
                    vkFreeMemory(device, memory, null);
                }
                vkDestroyImage(device, image, null);
                throw e;
            }
        }
    }

    private static long createView(VkDevice device, long image, MemoryStack stack, int format) {
        org.lwjgl.vulkan.VkImageViewCreateInfo viewInfo = org.lwjgl.vulkan.VkImageViewCreateInfo.calloc(stack)
                .sType$Default()
                .image(image)
                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                .format(format);
        VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack)
                .aspectMask(org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1);
        viewInfo.subresourceRange(range);
        LongBuffer viewOut = stack.mallocLong(1);
        VkCheck.check(vkCreateImageView(device, viewInfo, null, viewOut), "vkCreateImageView");
        return viewOut.get(0);
    }

    /**
     * Copies {@code staging} (already flipped into Vulkan's top-down order) into this image and leaves it sampleable.
     *
     * @param staging a buffer of {@code width * height * 4} bytes in {@code B8G8R8A8}
     */
    void upload(VkCommandBuffer commandBuffer, long staging) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            transition(commandBuffer, stack,
                    initialised ? org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                            : org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED,
                    org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                    org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);

            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0)
                    .bufferOffset(0L)
                    // Zero means "tightly packed at the image's width", which is how the staging data is built.
                    .bufferRowLength(0)
                    .bufferImageHeight(0);
            region.get(0).imageOffset().x(0).y(0).z(0);
            region.get(0).imageExtent().width(width).height(height).depth(1);
            region.get(0).imageSubresource().aspectMask(org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
            org.lwjgl.vulkan.VK10.vkCmdCopyBufferToImage(commandBuffer, staging, image,
                    org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);

            transition(commandBuffer, stack,
                    org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COPY_BIT,
                    org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT);
            initialised = true;
        }
    }

    /**
     * Records a copy of this image into {@code destination}, leaving the image sampleable again.
     *
     * <p>For the upload verification, and deliberately part of the same command buffer as the upload: it reads
     * exactly what the upload wrote, with no second submission to reason about.</p>
     *
     * @param destination a host-visible buffer of at least {@code width * height * 4} bytes
     */
    void copyTo(VkCommandBuffer commandBuffer, long destination) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            transition(commandBuffer, stack,
                    org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                    org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);

            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0).bufferOffset(0L).bufferRowLength(0).bufferImageHeight(0);
            region.get(0).imageOffset().x(0).y(0).z(0);
            region.get(0).imageExtent().width(width).height(height).depth(1);
            region.get(0).imageSubresource().aspectMask(org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
            org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer(commandBuffer, image,
                    org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, destination, region);

            transition(commandBuffer, stack,
                    org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COPY_BIT,
                    org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_READ_BIT);
        }
    }

    /**
     * Copies a rectangle of the frame's framebuffer into this image, through {@code staging}.
     *
     * @param staging host-visible memory of at least {@code copy.width() * copy.height() * 4} bytes
     */
    void copyFromFramebuffer(VkCommandBuffer commandBuffer, FrameDraws.FramebufferCopy copy,
                             FrameRenderTarget target, long staging) {
        if (Boolean.getBoolean("outlands.render.fullBlur")) {
            blitWholeFramebuffer(commandBuffer, target);
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int frameWidth = target.width();
            int frameHeight = target.height();

            // The instance has just been ended, so the source is free to leave its attachment layout.
            transitionExternal(commandBuffer, stack, target.colorImage(),
                    org.lwjgl.vulkan.VK13.VK_IMAGE_LAYOUT_ATTACHMENT_OPTIMAL,
                    org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                    org.lwjgl.vulkan.VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT, frameWidth, frameHeight);

            // GL's framebuffer origin is the bottom-left; Vulkan's is the top-left. Converting here keeps the caller
            // speaking the game's coordinates, which is what the patch that calls this already does.
            int topDownY = frameHeight - copy.y() - copy.height();
            VkBufferImageCopy.Buffer toBuffer = VkBufferImageCopy.calloc(1, stack);
            toBuffer.get(0).bufferOffset(0L).bufferRowLength(0).bufferImageHeight(0);
            toBuffer.get(0).imageOffset().x(copy.x()).y(topDownY).z(0);
            toBuffer.get(0).imageExtent().width(copy.width()).height(copy.height()).depth(1);
            toBuffer.get(0).imageSubresource().aspectMask(org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
            org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer(commandBuffer, target.colorImage(),
                    org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, staging, toBuffer);

            transitionExternal(commandBuffer, stack, target.colorImage(),
                    org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    org.lwjgl.vulkan.VK13.VK_IMAGE_LAYOUT_ATTACHMENT_OPTIMAL,
                    org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COPY_BIT,
                    org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_READ_BIT, frameWidth, frameHeight);

            // The staging buffer has just been written by the copy above and is read by the copy below. Two transfer
            // operations on one buffer are a write-then-read hazard, and without this barrier the second copy is
            // free to read what the buffer held before the first one ran - which is zeros. Measured: the staging
            // buffer held the correct panorama while the texture stayed empty, so the draws sampling it blended
            // nothing and the background looked like it was never drawn.
            org.lwjgl.vulkan.VkMemoryBarrier2.Buffer bufferBarrier = org.lwjgl.vulkan.VkMemoryBarrier2.calloc(1, stack);
            bufferBarrier.get(0)
                    .sType$Default()
                    .srcStageMask(org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COPY_BIT)
                    .srcAccessMask(org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT)
                    .dstStageMask(org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COPY_BIT)
                    .dstAccessMask(org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_READ_BIT);
            org.lwjgl.vulkan.VkDependencyInfo bufferDependency = org.lwjgl.vulkan.VkDependencyInfo.calloc(stack)
                    .sType$Default()
                    .pMemoryBarriers(bufferBarrier);
            vkCmdPipelineBarrier2(commandBuffer, bufferDependency);

            // This image is where the copy lands, so it goes to TRANSFER_DST and back.
            //
            // The old layout is only SHADER_READ_ONLY once the image has been written at least once. A texture that
            // is never uploaded and only ever filled by this copy - the main menu's backdrop is exactly that - is
            // still in UNDEFINED on its first pass, and declaring a layout it is not in leaves the contents
            // undefined: the copy landed in the staging buffer and the texture stayed empty, so every draw that
            // sampled it blended nothing. upload() already had this distinction; the two copy paths did not.
            transition(commandBuffer, stack,
                    initialised ? org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                            : org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED,
                    org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                    org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);

            // Straight through, no row reversal.
            //
            // GL's glCopyTexSubImage2D puts the framebuffer's bottom row at the destination's texel row 0, but this
            // framebuffer's row 0 is the top of the screen, and the quad sampling the copy puts v = 0 at the top of
            // the screen as well - so a straight copy reproduces the picture the sharp panorama already showed.
            // Reversing the rows to imitate GL's wording instead produced an upside-down background, which is how
            // this was decided: the observable behaviour of the original, not its description.
            VkBufferImageCopy.Buffer toImage = VkBufferImageCopy.calloc(1, stack);
            toImage.get(0).bufferOffset(0L).bufferRowLength(0).bufferImageHeight(0);
            toImage.get(0).imageOffset().x(copy.xOffset()).y(copy.yOffset()).z(0);
            toImage.get(0).imageExtent().width(copy.width()).height(copy.height()).depth(1);
            toImage.get(0).imageSubresource().aspectMask(org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(copy.level()).baseArrayLayer(0).layerCount(1);
            org.lwjgl.vulkan.VK10.vkCmdCopyBufferToImage(commandBuffer, staging, image,
                    org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, toImage);

            if (Boolean.getBoolean("outlands.render.paintCopies")) {
                // Isolation switch: paint the destination a solid colour after the copy, in the layout it is already
                // in. Visible magenta means the draws sampling this texture are reaching the screen, which is the
                // question a screenshot cannot answer when the picture merely looks "wrong".
                org.lwjgl.vulkan.VkClearColorValue colour = org.lwjgl.vulkan.VkClearColorValue.calloc(stack);
                colour.float32(0, 1.0F).float32(1, 0.0F).float32(2, 1.0F).float32(3, 1.0F);
                VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack)
                        .aspectMask(org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
                org.lwjgl.vulkan.VK10.vkCmdClearColorImage(commandBuffer, image,
                        org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, colour, range);
            }

            transition(commandBuffer, stack,
                    org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COPY_BIT,
                    org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT);
            initialised = true;
        }
    }

    /**
     * Copies the entire framebuffer into this image, scaled to fit.
     *
     * <p>What the menu's blur actually wants. GL's {@code glCopyTexSubImage2D} takes a 256x256 rectangle from the
     * framebuffer's corner, which at any resolution wider than 256 pixels is a crop of one corner - stretched back
     * over the whole screen it is a heavily zoomed smear whose "orientation" is whatever that corner happened to
     * contain. Blitting the whole frame keeps the picture's orientation, which is the property that matters
     * visually.</p>
     */
    void blitWholeFramebuffer(VkCommandBuffer commandBuffer, FrameRenderTarget target) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int frameWidth = target.width();
            int frameHeight = target.height();

            transitionExternal(commandBuffer, stack, target.colorImage(),
                    org.lwjgl.vulkan.VK13.VK_IMAGE_LAYOUT_ATTACHMENT_OPTIMAL,
                    org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                    org.lwjgl.vulkan.VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT, frameWidth, frameHeight);
            transition(commandBuffer, stack,
                    initialised ? org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                            : org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED,
                    org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                    org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);

            org.lwjgl.vulkan.VkImageBlit.Buffer blit = org.lwjgl.vulkan.VkImageBlit.calloc(1, stack);
            blit.get(0).srcSubresource().aspectMask(org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
            blit.get(0).srcOffsets(0).set(0, 0, 0);
            blit.get(0).srcOffsets(1).set(frameWidth, frameHeight, 1);
            blit.get(0).dstSubresource().aspectMask(org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
            blit.get(0).dstOffsets(0).set(0, 0, 0);
            blit.get(0).dstOffsets(1).set(width, height, 1);
            org.lwjgl.vulkan.VK10.vkCmdBlitImage(commandBuffer, target.colorImage(),
                    org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, image,
                    org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit,
                    org.lwjgl.vulkan.VK10.VK_FILTER_LINEAR);

            transitionExternal(commandBuffer, stack, target.colorImage(),
                    org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    org.lwjgl.vulkan.VK13.VK_IMAGE_LAYOUT_ATTACHMENT_OPTIMAL,
                    org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COPY_BIT,
                    org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_READ_BIT, frameWidth, frameHeight);
            transition(commandBuffer, stack,
                    org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COPY_BIT,
                    org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT);
            initialised = true;
        }
    }

    /** A layout transition for an image this object does not own, such as the frame's colour attachment. */
    private static void transitionExternal(VkCommandBuffer commandBuffer, MemoryStack stack, long image,
                                           int oldLayout, int newLayout, long sourceStage, long sourceAccess,
                                           int width, int height) {
        VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(1, stack);
        barrier.get(0)
                .sType$Default()
                .srcStageMask(sourceStage)
                .srcAccessMask(sourceAccess)
                .dstStageMask(org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COPY_BIT
                        | org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT)
                .dstAccessMask(org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_READ_BIT
                        | org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT
                        | org.lwjgl.vulkan.VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT)
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .image(image);
        barrier.get(0).subresourceRange()
                .aspectMask(org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        vkCmdPipelineBarrier2(commandBuffer, org.lwjgl.vulkan.VkDependencyInfo.calloc(stack)
                .sType$Default()
                .pImageMemoryBarriers(barrier));
    }

    private void transition(VkCommandBuffer commandBuffer, MemoryStack stack, int oldLayout, int newLayout,
                           long sourceStage, long sourceAccess) {
        VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(1, stack);
        barrier.get(0)
                .sType$Default()
                .srcStageMask(sourceStage)
                .srcAccessMask(sourceAccess)
                .dstStageMask(org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COPY_BIT
                        | org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT)
                .dstAccessMask(org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT
                        | org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT)
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .image(image);
        barrier.get(0).subresourceRange()
                .aspectMask(org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        vkCmdPipelineBarrier2(commandBuffer, org.lwjgl.vulkan.VkDependencyInfo.calloc(stack)
                .sType$Default()
                .pImageMemoryBarriers(barrier));
    }

    /**
     * Picks device-local memory.
     *
     * <p>Unlike the vertex buffers, this must <i>not</i> prefer host-visible: an optimally tiled image cannot be
     * written from the host at all, so asking for host-visible memory would either fail or silently get a type the
     * copy engine cannot read efficiently. The staging buffer is the host-visible half.</p>
     */
    private static int deviceLocal(VulkanContext context, int allowedBits, MemoryStack stack) {
        VkPhysicalDeviceMemoryProperties properties = VkPhysicalDeviceMemoryProperties.malloc(stack);
        vkGetPhysicalDeviceMemoryProperties(context.physicalDevice(), properties);
        for (int i = 0; i < properties.memoryTypeCount(); i++) {
            if ((allowedBits & (1 << i)) == 0) {
                continue;
            }
            if ((properties.memoryTypes(i).propertyFlags()
                    & org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0) {
                return i;
            }
        }
        throw new VkCheck.VulkanException("no device-local memory type for a sampled image");
    }

    @Override
    public void close() {
        vkDestroyImageView(device, view, null);
        vkDestroyImage(device, image, null);
        vkFreeMemory(device, memory, null);
    }
}
