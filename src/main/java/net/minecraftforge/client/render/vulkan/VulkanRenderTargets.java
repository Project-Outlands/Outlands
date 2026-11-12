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

    /**
     * The live targets, by the texture handle the game samples them with.
     *
     * <p>This is how a recorded target switch finds its attachments again: the frame's command stream carries the
     * handle (a number, so the list stays backend-neutral) and the recorder resolves it here. A closed target is
     * removed, so a switch to one is a missing entry and reported as such rather than drawing into freed memory.</p>
     */
    private final java.util.Map<Integer, VulkanRenderTarget> byTexture = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public RenderTarget create(int width, int height, boolean useDepth) {
        VulkanRenderTarget target =
                new VulkanRenderTarget(context, textures, colorFormat, depthFormat, width, height, useDepth);
        target.ownedBy(this);
        byTexture.put(target.textureId(), target);
        return target;
    }

    /** The target a handle refers to, or {@code null} when no live target has it. */
    VulkanRenderTarget byTextureId(int textureId) {
        return byTexture.get(textureId);
    }

    /** The layout a target's colour image is in, or -1 when the handle is not a target. */
    int layoutOf(int textureId) {
        VulkanRenderTarget target = byTexture.get(textureId);
        return target == null ? -1 : target.currentLayout();
    }

    /** Called by a target when it is released. */
    void forget(int textureId) {
        byTexture.remove(textureId);
    }
}
