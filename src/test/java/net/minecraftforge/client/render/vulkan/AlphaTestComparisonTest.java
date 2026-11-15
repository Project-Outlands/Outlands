package net.minecraftforge.client.render.vulkan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.lwjgl.opengl.GL11.GL_ALWAYS;
import static org.lwjgl.opengl.GL11.GL_EQUAL;
import static org.lwjgl.opengl.GL11.GL_GEQUAL;
import static org.lwjgl.opengl.GL11.GL_GREATER;
import static org.lwjgl.opengl.GL11.GL_LEQUAL;
import static org.lwjgl.opengl.GL11.GL_LESS;
import static org.lwjgl.opengl.GL11.GL_NEVER;
import static org.lwjgl.opengl.GL11.GL_NOTEQUAL;

/**
 * All eight {@code glAlphaFunc} comparisons reach the shader.
 *
 * <p><b>Why this was a crash and not a gap.</b> The stream used to throw inside the frame for any comparison other
 * than {@code GL_GREATER} - the one vanilla sets - so a mod calling {@code glAlphaFunc(GL_ALWAYS, 0)} took the whole
 * frame with it, and a failed frame stops presentation. The eight values are a closed set that the shader can test
 * with three bits, so there was never a reason to refuse them.</p>
 */
class AlphaTestComparisonTest {

    @Test
    void everyComparisonHasItsOwnIndex() {
        assertEquals(0, VulkanStream.alphaComparisonIndex(GL_NEVER));
        assertEquals(1, VulkanStream.alphaComparisonIndex(GL_LESS));
        assertEquals(2, VulkanStream.alphaComparisonIndex(GL_EQUAL));
        assertEquals(3, VulkanStream.alphaComparisonIndex(GL_LEQUAL));
        assertEquals(4, VulkanStream.alphaComparisonIndex(GL_GREATER));
        assertEquals(5, VulkanStream.alphaComparisonIndex(GL_NOTEQUAL));
        assertEquals(6, VulkanStream.alphaComparisonIndex(GL_GEQUAL));
        assertEquals(7, VulkanStream.alphaComparisonIndex(GL_ALWAYS));
    }

    @Test
    void theIndexFitsThreeBits() {
        // The shader reads (flags >> 6) & 7, so anything above 7 would spill into the bits above the flags word.
        for (int comparison = 512; comparison <= 519; comparison++) {
            int index = VulkanStream.alphaComparisonIndex(comparison);
            assertEquals(comparison - 512, index);
            assertEquals(index, index & 7, "three bits hold " + index);
        }
    }

    @Test
    void aValueOutsideTheSetIsClampedRatherThanRefused() {
        assertEquals(0, VulkanStream.alphaComparisonIndex(0));
        assertEquals(7, VulkanStream.alphaComparisonIndex(9999));
    }
}
