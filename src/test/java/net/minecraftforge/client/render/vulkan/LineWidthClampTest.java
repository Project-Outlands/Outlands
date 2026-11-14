package net.minecraftforge.client.render.vulkan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The line width: GL's value in, a value this device will rasterise out.
 *
 * <p>GL clamps too, so the clamp is not a deviation - but two things make it load-bearing here. The range is a device
 * limit, and without {@code wideLines} the only legal value for {@code vkCmdSetLineWidth} is 1.0, which the spec
 * states as a validation rule instead of clamping for us. Passing 4.0 through on such a device is a validation error,
 * not a thicker line.</p>
 */
class LineWidthClampTest {

    @Test
    void aWidthInsideTheRangeIsKept() {
        assertEquals(4.0F, VulkanContext.clampLineWidth(4.0F, 1.0F, 10.0F));
        assertEquals(1.0F, VulkanContext.clampLineWidth(1.0F, 1.0F, 10.0F));
    }

    @Test
    void aWidthOutsideTheRangeIsClampedNotRejected() {
        assertEquals(10.0F, VulkanContext.clampLineWidth(64.0F, 1.0F, 10.0F), "GL clamps too");
        assertEquals(1.0F, VulkanContext.clampLineWidth(0.25F, 1.0F, 10.0F));
    }

    @Test
    void withoutWideLinesEveryWidthBecomesOne() {
        // The device reports lineWidthRange = [1, 1] when wideLines is not enabled, so the same arithmetic covers the
        // degraded case: Immersive Engineering's 4- and 8-pixel lines become 1-pixel lines instead of a validation
        // error on every draw.
        assertEquals(1.0F, VulkanContext.clampLineWidth(8.0F, 1.0F, 1.0F));
        assertEquals(1.0F, VulkanContext.clampLineWidth(1.0F, 1.0F, 1.0F));
    }

}
