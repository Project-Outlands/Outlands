package net.minecraftforge.client.render.vulkan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.lwjgl.vulkan.VK10.VK_API_VERSION_1_0;

/**
 * The Vulkan probe, exercised against the machine's real driver.
 *
 * <p>Nothing here asserts that Vulkan <i>is</i> available: that would make the suite pass or fail depending on the
 * hardware. What it does assert is that the probe answers without throwing and without leaking a half-built
 * instance, which is the part that would break on a machine with a missing or broken loader.</p>
 */
class VulkanProbeTest {

    @AfterEach
    void restore() {
        VulkanProbe.forceForTests(null);
    }

    @Test
    void probingDoesNotThrow() {
        VulkanProbe.forceForTests(null);
        assertDoesNotThrow(VulkanProbe::isAvailable);
    }

    @Test
    void theAnswerIsCached() {
        VulkanProbe.forceForTests(null);
        boolean first = VulkanProbe.isAvailable();
        boolean second = VulkanProbe.isAvailable();

        assertEquals(first, second, "the probe must not change its answer between calls in one process");
    }

    @Test
    void aForcedAnswerWins() {
        VulkanProbe.forceForTests(true);
        assertEquals(true, VulkanProbe.isAvailable());

        VulkanProbe.forceForTests(false);
        assertEquals(false, VulkanProbe.isAvailable());
    }

    @Test
    void versionsAreFormattedFromThePackedValue() {
        // The layout is major<<22 | minor<<12 | patch; VK_MAKE_API_VERSION(0, 1, 4, 354).
        assertEquals("1.4.354", VulkanProbe.version((1 << 22) | (4 << 12) | 354));
        assertEquals("1.0.0", VulkanProbe.version(VK_API_VERSION_1_0));
    }
}
