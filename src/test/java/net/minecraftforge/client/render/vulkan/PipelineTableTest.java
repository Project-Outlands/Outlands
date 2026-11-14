package net.minecraftforge.client.render.vulkan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The enumerant tables at the pipeline end: what {@code glBlendEquation} and {@code glPolygonMode} become.
 *
 * <p>The pipeline used to hard-code {@code VK_BLEND_OP_ADD} and {@code VK_POLYGON_MODE_FILL} while the state recorded
 * the caller's values, so both were accepted and then silently dropped. These are the guards that keep the two ends
 * agreeing.</p>
 */
class PipelineTableTest {

    private static final int GL_FUNC_ADD = 32774;
    private static final int GL_MIN = 32775;
    private static final int GL_MAX = 32776;
    private static final int GL_FUNC_SUBTRACT = 32778;
    private static final int GL_FUNC_REVERSE_SUBTRACT = 32779;
    private static final int GL_POINT = 6912;
    private static final int GL_LINE = 6913;
    private static final int GL_FILL = 6914;

    @Test
    void everyBlendEquationMapsToItsVulkanOperation() {
        assertEquals(org.lwjgl.vulkan.VK10.VK_BLEND_OP_ADD, VulkanPipeline.blendOp(GL_FUNC_ADD));
        assertEquals(org.lwjgl.vulkan.VK10.VK_BLEND_OP_MIN, VulkanPipeline.blendOp(GL_MIN));
        assertEquals(org.lwjgl.vulkan.VK10.VK_BLEND_OP_MAX, VulkanPipeline.blendOp(GL_MAX));
        assertEquals(org.lwjgl.vulkan.VK10.VK_BLEND_OP_SUBTRACT, VulkanPipeline.blendOp(GL_FUNC_SUBTRACT));
        assertEquals(org.lwjgl.vulkan.VK10.VK_BLEND_OP_REVERSE_SUBTRACT,
                VulkanPipeline.blendOp(GL_FUNC_REVERSE_SUBTRACT));

        assertThrows(IllegalArgumentException.class, () -> VulkanPipeline.blendOp(32777),
                "32777 is not a GL blend equation; it was listed as one until the table was corrected");
    }

    @Test
    void polygonModeClampsWhenTheDeviceCannotRasteriseLines() {
        VulkanPipeline.setNonSolidFill(false);
        try {
            assertEquals(org.lwjgl.vulkan.VK10.VK_POLYGON_MODE_FILL, VulkanPipeline.polygonMode(GL_LINE),
                    "without fillModeNonSolid a LINE pipeline fails validation, so the mode is clamped");
            assertEquals(org.lwjgl.vulkan.VK10.VK_POLYGON_MODE_FILL, VulkanPipeline.polygonMode(GL_POINT));
        } finally {
            VulkanPipeline.setNonSolidFill(true);
        }
        try {
            assertEquals(org.lwjgl.vulkan.VK10.VK_POLYGON_MODE_LINE, VulkanPipeline.polygonMode(GL_LINE));
            assertEquals(org.lwjgl.vulkan.VK10.VK_POLYGON_MODE_POINT, VulkanPipeline.polygonMode(GL_POINT));
            assertEquals(org.lwjgl.vulkan.VK10.VK_POLYGON_MODE_FILL, VulkanPipeline.polygonMode(GL_FILL));
            assertThrows(IllegalArgumentException.class, () -> VulkanPipeline.polygonMode(1234));
        } finally {
            VulkanPipeline.setNonSolidFill(false);
        }
    }
}
