package net.minecraftforge.client.render.vulkan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which way up a framebuffer copy lands in its destination texture.
 *
 * <p>GL's {@code glCopyTexImage2D} maps the source rectangle's <em>lower</em> left corner to texel row {@code yOffset},
 * so the framebuffer's bottom row becomes the destination's row 0. Both this framebuffer and the destination image
 * count rows from the top, so the copy has to reverse them - and a sign error there produces a picture that is upside
 * down and nothing else in the log. TurretMod's turret camera was that picture: its own draw rotates the texture 180
 * degrees, which is exactly the compensation GL's flip needs, and it showed the ground above the sky.</p>
 */
class FramebufferCopyOrientationTest {

    @Test
    void theSourcesTopRowLandsOnTheDestinationsLastRow() {
        // A three-row copy into a texture whose rows start at yOffset: 0 -> 2, 1 -> 1, 2 -> 0.
        assertEquals(2, VulkanImage.destinationRow(0, 3, 0), "the rectangle's top row goes to the bottom of the block");
        assertEquals(1, VulkanImage.destinationRow(1, 3, 0), "the middle row stays in the middle");
        assertEquals(0, VulkanImage.destinationRow(2, 3, 0), "the rectangle's bottom row goes to row 0");
    }

    @Test
    void theBlockStartsAtTheSubImageOffset() {
        // glCopyTexSubImage2D can write into the middle of a texture; the reversal is inside the block, not the image.
        assertEquals(10, VulkanImage.destinationRow(0, 3, 8));
        assertEquals(8, VulkanImage.destinationRow(2, 3, 8));
    }

    @Test
    void aSingleRowCopyIsItsOwnReverse() {
        assertEquals(5, VulkanImage.destinationRow(0, 1, 5));
    }
}
