package net.minecraftforge.client.render.vulkan;

import net.minecraftforge.client.render.texture.TextureImage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a sampler is a function of, and therefore what invalidates a texture's descriptor set.
 *
 * <p><b>Why this is worth pinning.</b> The cache in {@code VulkanTextures} used to decide "up to date" from the pixel
 * version and the image size alone, so a change to the filter left the old sampler in place. Nothing calls
 * {@code glTexParameteri} on a texture's filter in the middle of a frame - except vanilla, which does it four times a
 * frame on the block atlas: {@code EntityRenderer} draws {@code CUTOUT_MIPPED} with mipmaps and {@code CUTOUT} without
 * them, and switches the atlas between the two filters around each layer. A cache that misses that renders flowers
 * mipmapped and plants un-mipmapped (or the other way round) with no error anywhere.</p>
 */
class SamplerCacheKeyTest {

    private static final int GL_NEAREST_MIPMAP_LINEAR = 9986;
    private static final int GL_NEAREST = 9728;
    private static final int GL_LINEAR = 9729;

    /** A texture with a four-level chain, which is what the atlas has. */
    private static TextureImage chained(int minFilter, int magFilter) {
        int[][] levels = {new int[16 * 16], new int[8 * 8], new int[4 * 4], new int[2 * 2]};
        return new TextureImage(1, 16, 16, levels, minFilter, magFilter,
                TextureImage.UNSET, TextureImage.UNSET, 7L);
    }

    @Test
    void aFilterChangeMakesItADifferentSampler() {
        // The regression this test exists for: same pixels, same size, same version, different filter.
        assertNotEquals(VulkanTextures.samplerKeyFor(chained(GL_NEAREST_MIPMAP_LINEAR, GL_NEAREST)),
                VulkanTextures.samplerKeyFor(chained(GL_NEAREST, GL_NEAREST)),
                "mipmapped and not-mipmapped asks are two different samplers even when the pixels did not move");
    }

    @Test
    void theSameParametersAreTheSameSampler() {
        assertEquals(VulkanTextures.samplerKeyFor(chained(GL_NEAREST_MIPMAP_LINEAR, GL_NEAREST)),
                VulkanTextures.samplerKeyFor(chained(GL_NEAREST_MIPMAP_LINEAR, GL_NEAREST)),
                "a per-draw lookup must not rebuild the sampler for a texture that did not change");
    }

    @Test
    void aFilterWithNoChainRangesOverNoLevels() {
        // GL_NEAREST reads level 0 only, so the LOD range must not offer levels a GL_NEAREST texture would never
        // sample - which is what keeps this key honest for the dozens of textures that never get a chain.
        assertEquals(0, VulkanTextures.samplerKeyFor(chained(GL_NEAREST, GL_NEAREST)).maxLevel());
        assertEquals(3, VulkanTextures.samplerKeyFor(chained(GL_NEAREST_MIPMAP_LINEAR, GL_NEAREST)).maxLevel(),
                "the top of the chain is levelCount - 1");
    }

    @Test
    void anUnsetFilterResolvesToGLsDefaults() {
        // GL's defaults are GL_NEAREST_MIPMAP_LINEAR for minification and GL_LINEAR for magnification, and they are
        // part of the identity: a texture that never called glTexParameteri must not share a sampler with one that
        // asked for something else.
        VulkanTextures.SamplerKey defaults = VulkanTextures.samplerKeyFor(chained(TextureImage.UNSET, TextureImage.UNSET));
        assertEquals(GL_NEAREST_MIPMAP_LINEAR, defaults.minFilter());
        assertEquals(GL_LINEAR, defaults.magFilter());
        assertTrue(defaults.maxLevel() > 0, "the default min filter is a mipmap filter, so it reads the chain");
    }
}
