package net.minecraftforge.client.render.vulkan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The GL to Vulkan stencil enum tables.
 *
 * <p><b>Why these are worth pinning.</b> A wrong entry is not an error anywhere: the pipeline is valid, the
 * comparison is simply a different one, and the picture is a mask in the wrong place. The surveyed caller is
 * Immersive Engineering's bottling machine, which uses ALWAYS/REPLACE/255 to write the mask and then EQUAL against 0
 * and 1 to draw the two halves of an item.</p>
 */
class StencilEnumTest {

    @Test
    void glStencilOperationsMapToVulkanOperations() {
        assertEquals(org.lwjgl.vulkan.VK10.VK_STENCIL_OP_ZERO, VulkanPipeline.stencilOp(0));
        assertEquals(org.lwjgl.vulkan.VK10.VK_STENCIL_OP_KEEP, VulkanPipeline.stencilOp(7680));
        assertEquals(org.lwjgl.vulkan.VK10.VK_STENCIL_OP_REPLACE, VulkanPipeline.stencilOp(7681));
        assertEquals(org.lwjgl.vulkan.VK10.VK_STENCIL_OP_INCREMENT_AND_CLAMP, VulkanPipeline.stencilOp(7682));
        assertEquals(org.lwjgl.vulkan.VK10.VK_STENCIL_OP_DECREMENT_AND_CLAMP, VulkanPipeline.stencilOp(7683));
        assertEquals(org.lwjgl.vulkan.VK10.VK_STENCIL_OP_INVERT, VulkanPipeline.stencilOp(5386));
        assertEquals(org.lwjgl.vulkan.VK10.VK_STENCIL_OP_INCREMENT_AND_WRAP, VulkanPipeline.stencilOp(34055));
        assertEquals(org.lwjgl.vulkan.VK10.VK_STENCIL_OP_DECREMENT_AND_WRAP, VulkanPipeline.stencilOp(34056));
    }

    @Test
    void anUnknownStencilOperationIsRejectedRatherThanBecomingKeep() {
        // A mod's constant this backend does not know must not turn into "leave the buffer alone": that is a mask
        // that does not mask, with no error to notice.
        assertThrows(IllegalArgumentException.class, () -> VulkanPipeline.stencilOp(12345));
    }

    @Test
    void glStencilComparisonsMapToVulkanComparisons() {
        assertEquals(org.lwjgl.vulkan.VK10.VK_COMPARE_OP_NEVER, VulkanPipeline.stencilCompareOp(512));
        assertEquals(org.lwjgl.vulkan.VK10.VK_COMPARE_OP_EQUAL, VulkanPipeline.stencilCompareOp(514));
        assertEquals(org.lwjgl.vulkan.VK10.VK_COMPARE_OP_NOT_EQUAL, VulkanPipeline.stencilCompareOp(517));
        assertEquals(org.lwjgl.vulkan.VK10.VK_COMPARE_OP_ALWAYS, VulkanPipeline.stencilCompareOp(519));
        assertThrows(IllegalArgumentException.class, () -> VulkanPipeline.stencilCompareOp(1515));
    }
}
