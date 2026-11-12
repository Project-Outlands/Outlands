package net.minecraftforge.client.render.vulkan;

import net.minecraftforge.client.render.target.RenderTarget;
import net.minecraftforge.client.render.target.RenderTargets;

/**
 * Allocates render targets on the Vulkan device.
 *
 * <p>Installed by {@link VulkanBackend} once the swapchain exists, because a target's attachments have to agree with
 * the swapchain's formats: every pipeline in this renderer is built for one colour format, and a target in a different
 * format would need its own pipelines for no gain. That is a constraint worth stating rather than discovering - see
 * {@code docs/vulkan-backend-plan.md} §11.3 D1.</p>
 */
final class VulkanRenderTargets implements RenderTargets.Factory {

    private final VulkanContext context;
    private final VulkanTextures textures;
    private final int colorFormat;
    private final int depthFormat;

    VulkanRenderTargets(VulkanContext context, VulkanTextures textures, int colorFormat, int depthFormat) {
        this.context = context;
        this.textures = textures;
        this.colorFormat = colorFormat;
        this.depthFormat = depthFormat;
    }

    @Override
    public RenderTarget create(int width, int height, boolean useDepth) {
        return new VulkanRenderTarget(context, textures, colorFormat, depthFormat, width, height, useDepth);
    }
}
