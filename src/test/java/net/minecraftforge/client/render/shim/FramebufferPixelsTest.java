package net.minecraftforge.client.render.shim;

import net.minecraftforge.client.render.vertex.FrameDraws;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code glReadPixels}' byte layout: the window's BGRA becomes the caller's RGBA, and the rows come out bottom-up.
 *
 * <p>An icon built from a copy that skipped either conversion is blue and upside down, and nothing in the log says so
 * - which is why the conversion is a class of its own with its own tests rather than two loops inside the read-back.</p>
 */
class FramebufferPixelsTest {

    private static final int RGBA = 6408;
    private static final int BGRA = 32993;
    private static final int RGB = 6407;
    private static final int UNSIGNED_BYTE = 5121;

    /** A 2x1 framebuffer, top-down as copied: one red texel then one green texel. */
    private static final byte[] BGRA_TOP_DOWN = {
            (byte) 0, (byte) 0, (byte) 255, (byte) 255,   // red
            (byte) 0, (byte) 255, (byte) 0, (byte) 255};  // green

    @Test
    void bgraBecomesRgba() {
        ByteBuffer out = ByteBuffer.allocate(8);
        assertTrue(FramebufferPixels.write(BGRA_TOP_DOWN, 2, 1, request(RGBA, out)));
        assertArrayEquals(new byte[] {(byte) 255, 0, 0, (byte) 255, 0, (byte) 255, 0, (byte) 255}, bytes(out, 8),
                "red then green, in RGBA order");
    }

    @Test
    void bgraStaysBgra() {
        ByteBuffer out = ByteBuffer.allocate(8);
        assertTrue(FramebufferPixels.write(BGRA_TOP_DOWN, 2, 1, request(BGRA, out)));
        assertArrayEquals(BGRA_TOP_DOWN, bytes(out, 8), "a caller that asked for BGRA gets the copy as it is");
    }

    @Test
    void rowsComeOutBottomUp() {
        // A 1x2 framebuffer: the top row is red, the bottom row is green.
        byte[] topDown = {
                (byte) 0, (byte) 0, (byte) 255, (byte) 255,
                (byte) 0, (byte) 255, (byte) 0, (byte) 255};
        ByteBuffer out = ByteBuffer.allocate(8);
        assertTrue(FramebufferPixels.write(topDown, 1, 2, request(RGBA, out)));
        assertArrayEquals(new byte[] {0, (byte) 255, 0, (byte) 255, (byte) 255, 0, 0, (byte) 255}, bytes(out, 8),
                "GL's first row is the framebuffer's bottom one, which is the green texel");
    }

    @Test
    void rgbDropsAlpha() {
        ByteBuffer out = ByteBuffer.allocate(6);
        assertTrue(FramebufferPixels.write(BGRA_TOP_DOWN, 2, 1, request(RGB, out)));
        assertArrayEquals(new byte[] {(byte) 255, 0, 0, 0, (byte) 255, 0}, bytes(out, 6), "three bytes per texel");
    }

    @Test
    void theDestinationPositionIsRespected() {
        ByteBuffer out = ByteBuffer.allocate(16);
        out.position(8);
        assertTrue(FramebufferPixels.write(BGRA_TOP_DOWN, 2, 1, request(RGBA, out)));
        assertArrayEquals(new byte[] {(byte) 255, 0, 0, (byte) 255, 0, (byte) 255, 0, (byte) 255},
                bytes(out, 8, 8), "the pixels start where the caller's buffer points");
    }

    @Test
    void anUnsupportedPairIsRefused() {
        ByteBuffer out = ByteBuffer.allocate(64);
        assertFalse(FramebufferPixels.write(BGRA_TOP_DOWN, 2, 1, request(6403, out)), "GL_RED is not converted");
        assertFalse(FramebufferPixels.write(BGRA_TOP_DOWN, 2, 1,
                new FrameDraws.ReadPixels(0, 0, 2, 1, RGBA, 5126, out)), "GL_FLOAT is not converted");
    }

    @Test
    void aDestinationThatIsTooSmallIsAnError() {
        ByteBuffer out = ByteBuffer.allocate(4);
        assertThrows(IllegalArgumentException.class,
                () -> FramebufferPixels.write(BGRA_TOP_DOWN, 2, 1, request(RGBA, out)));
    }

    private static FrameDraws.ReadPixels request(int format, ByteBuffer out) {
        return new FrameDraws.ReadPixels(0, 0, 2, 1, format, UNSIGNED_BYTE, out);
    }

    private static byte[] bytes(ByteBuffer out, int length) {
        return bytes(out, 0, length);
    }

    private static byte[] bytes(ByteBuffer out, int from, int length) {
        byte[] result = new byte[length];
        for (int i = 0; i < length; i++) {
            result[i] = out.get(from + i);
        }
        return result;
    }
}
