package net.minecraftforge.client.render.vulkan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The GL min-filter to Vulkan (filter, mipmap mode) decode.
 *
 * <p><b>Why this is worth pinning.</b> GL names a minification filter as a pair of choices - how to filter inside a
 * level, and how to combine two levels - and Vulkan asks the same two questions as two separate enums. Four GL names
 * are the four combinations, and getting one wrong is silent: the pipeline is valid, the texture is simply filtered
 * differently, and the difference is a blur or a shimmer at distance rather than an error.</p>
 *
 * <p>The pair that matters in practice is the block atlas, which {@code EntityRenderer} sets to
 * {@code GL_NEAREST_MIPMAP_LINEAR} (9986): nearest inside a level, linear between two, which is what makes a distant
 * grass block fade between mip levels instead of popping.</p>
 */
class SamplerFilterTest {

    @Test
    void theFourMipmapFiltersDecodeIntoTheirTwoHalves() {
        // GL_NEAREST_MIPMAP_NEAREST: nearest inside, one level chosen.
        assertEquals(org.lwjgl.vulkan.VK10.VK_FILTER_NEAREST, VulkanTextures.vkFilter(9984));
        assertTrue(VulkanTextures.mipmapped(9984));
        assertFalse(VulkanTextures.interLevelLinear(9984));

        // GL_LINEAR_MIPMAP_NEAREST: linear inside, one level chosen.
        assertEquals(org.lwjgl.vulkan.VK10.VK_FILTER_LINEAR, VulkanTextures.vkFilter(9985));
        assertTrue(VulkanTextures.mipmapped(9985));
        assertFalse(VulkanTextures.interLevelLinear(9985));

        // GL_NEAREST_MIPMAP_LINEAR: nearest inside, two levels blended. The atlas's filter.
        assertEquals(org.lwjgl.vulkan.VK10.VK_FILTER_NEAREST, VulkanTextures.vkFilter(9986));
        assertTrue(VulkanTextures.mipmapped(9986));
        assertTrue(VulkanTextures.interLevelLinear(9986));

        // GL_LINEAR_MIPMAP_LINEAR: linear inside, two blended. The trilinear case.
        assertEquals(org.lwjgl.vulkan.VK10.VK_FILTER_LINEAR, VulkanTextures.vkFilter(9987));
        assertTrue(VulkanTextures.mipmapped(9987));
        assertTrue(VulkanTextures.interLevelLinear(9987));
    }

    @Test
    void theLevel0FiltersReadNoChain() {
        assertEquals(org.lwjgl.vulkan.VK10.VK_FILTER_NEAREST, VulkanTextures.vkFilter(9728));
        assertFalse(VulkanTextures.mipmapped(9728));
        assertEquals(org.lwjgl.vulkan.VK10.VK_FILTER_LINEAR, VulkanTextures.vkFilter(9729));
        assertFalse(VulkanTextures.mipmapped(9729));
    }

    @Test
    void anUnsetFilterIsNeitherOfThose() {
        // UNSET is 0, which is not a GL enum: the sampler resolves it to GL's default before decoding, so the decode
        // itself must not claim it is mipmapped. Asserting it so that adding 0 to the mipmap range by accident - the
        // range test is a comparison - cannot quietly turn every unset texture trilinear.
        assertFalse(VulkanTextures.mipmapped(net.minecraftforge.client.render.texture.TextureImage.UNSET));
    }
}
