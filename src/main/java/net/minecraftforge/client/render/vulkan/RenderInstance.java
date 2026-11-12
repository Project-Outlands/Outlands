package net.minecraftforge.client.render.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkViewport;

import static org.lwjgl.vulkan.VK13.VK_IMAGE_LAYOUT_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK13.vkCmdBeginRendering;

/**
 * Opens and closes a rendering instance over a pair of attachments.
 *
 * <p><b>Why this is separate from the swapchain.</b> A frame is not one instance: a copy or a target switch ends it and
 * opens another over different attachments. The window's attachments come from the swapchain and an offscreen
 * target's from its own images, but the instance itself is the same object in both cases, so the code that builds it
 * lives here rather than being copied per caller. Getting the two copies subtly different is exactly the failure that
 * shows up as "works until the first target switch".</p>
 *
 * <p><b>Dynamic viewport and scissor.</b> Both are set here on every open, and that is not redundancy: a reopened
 * instance starts with them undefined, and dynamic state is what lets a resize avoid rebuilding every pipeline.</p>
 */
final class RenderInstance {

    private RenderInstance() {}

    /**
     * Opens a rendering instance.
     *
     * @param colorLoadOp   {@code CLEAR} or {@code LOAD}: whether the colour starts empty or keeps what was there
     * @param clearColor    the colour {@code CLEAR} uses, or {@code null} to clear to transparent black
     * @param opaqueAlpha   whether to force alpha to 1 in the clear. The window does; an offscreen target must not,
     *                      because its alpha is what the entity outline's edge detection reads
     * @param depthLoadOp   the same for depth, which is separate because a target switch keeps the depth already
     *                      written while clearing the colour it just threw away
     */
    static void begin(VkCommandBuffer commandBuffer, long colorView, long depthView, int width, int height,
                      int colorLoadOp, float[] clearColor, boolean opaqueAlpha, int depthLoadOp, float clearDepth) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkClearValue clearColorValue = VkClearValue.calloc(stack);
            float red = clearColor != null ? clearColor[0] : 0.0F;
            float green = clearColor != null ? clearColor[1] : 0.0F;
            float blue = clearColor != null ? clearColor[2] : 0.0F;
            float alpha = clearColor != null ? clearColor[3] : 0.0F;
            clearColorValue.color().float32(0, red).float32(1, green).float32(2, blue)
                    .float32(3, opaqueAlpha ? 1.0F : alpha);

            VkRenderingAttachmentInfo.Buffer colorAttachment = VkRenderingAttachmentInfo.calloc(1, stack);
            colorAttachment.get(0)
                    .sType$Default()
                    .imageView(colorView)
                    .imageLayout(VK_IMAGE_LAYOUT_ATTACHMENT_OPTIMAL)
                    .loadOp(colorLoadOp)
                    .storeOp(org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE)
                    .clearValue(clearColorValue);

            VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack)
                    .sType$Default()
                    .layerCount(1)
                    .pColorAttachments(colorAttachment);
            VkRect2D renderArea = VkRect2D.calloc(stack);
            renderArea.offset().x(0).y(0);
            renderArea.extent().width(width).height(height);
            renderingInfo.renderArea(renderArea);

            if (depthView != 0L) {
                VkClearValue clearDepthValue = VkClearValue.calloc(stack);
                clearDepthValue.depthStencil().depth(clearDepth).stencil(0);
                VkRenderingAttachmentInfo depthAttachment = VkRenderingAttachmentInfo.calloc(stack)
                        .sType$Default()
                        .imageView(depthView)
                        .imageLayout(VK_IMAGE_LAYOUT_ATTACHMENT_OPTIMAL)
                        .loadOp(depthLoadOp)
                        // STOREd, not discarded: a frame is not one instance, and a reopened instance that loads depth
                        // nobody wrote leaves the contents up to the driver. The main menu's background flickered
                        // between a sharp panorama and a flat colour from identical code before this was STORE.
                        .storeOp(org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE)
                        .clearValue(clearDepthValue);
                renderingInfo.pDepthAttachment(depthAttachment);
            }

            vkCmdBeginRendering(commandBuffer, renderingInfo);

            VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
            viewport.get(0).x(0.0f).y(0.0f).width(width).height(height).minDepth(0.0f).maxDepth(1.0f);
            org.lwjgl.vulkan.VK10.vkCmdSetViewport(commandBuffer, 0, viewport);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.get(0).offset().x(0).y(0);
            scissor.get(0).extent().width(width).height(height);
            org.lwjgl.vulkan.VK10.vkCmdSetScissor(commandBuffer, 0, scissor);
        }
    }

    /** Closes the instance that is open. Called before a copy, a target switch and at the end of the frame. */
    static void end(VkCommandBuffer commandBuffer) {
        org.lwjgl.vulkan.VK13.vkCmdEndRendering(commandBuffer);
    }
}
