package net.minecraftforge.client.render.shim;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code glGetTexImage}'s byte layout: GL's bottom-up rows, and the channel order the caller asked for.
 *
 * <p>Both are invisible in a picture and fatal to an exporter: a PNG written from a top-down copy is upside down, and
 * one written with the channels swapped is blue. Bookshelf, LibrarianLib's {@code TextureMapExporter}, Quark's
 * {@code TextureDump} and TurretMod all read textures this way.</p>
 */
class TexturePixelsTest {

    private static final int RGBA = 6408;
    private static final int BGRA = 32993;
    private static final int RGB = 6407;
    private static final int UNSIGNED_BYTE = 5121;

    /** A 2x2 texture, rows top-down: top row red/green, bottom row blue/white. */
    private static final int[] ARGB = {0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFFFF};

    @Test
    void rowsComeOutBottomUp() {
        ByteBuffer out = ByteBuffer.allocate(2 * 2 * 4);
        assertTrue(TexturePixels.write(ARGB, 2, 2, RGBA, UNSIGNED_BYTE, out, 0));

        // GL's first row is the texture's *last* row: blue then white.
        assertArrayEquals(new byte[] {0, 0, (byte) 255, (byte) 255, (byte) 255, (byte) 255, (byte) 255, (byte) 255},
                slice(out, 0, 8), "bottom row first");
        assertArrayEquals(new byte[] {(byte) 255, 0, 0, (byte) 255, 0, (byte) 255, 0, (byte) 255},
                slice(out, 8, 8), "then the top row");
    }

    @Test
    void bgraSwapsRedAndBlue() {
        ByteBuffer out = ByteBuffer.allocate(2 * 2 * 4);
        assertTrue(TexturePixels.write(ARGB, 2, 2, BGRA, UNSIGNED_BYTE, out, 0));
        // The top row is the *second* output row, and its first texel is red: B=0, G=0, R=255, A=255.
        assertArrayEquals(new byte[] {0, 0, (byte) 255, (byte) 255},
                slice(out, 8, 4), "the top-left red texel comes out as B,G,R,A");
    }

    @Test
    void rgbDropsAlphaAndTakesThreeBytesPerTexel() {
        ByteBuffer out = ByteBuffer.allocate(2 * 2 * 3);
        assertTrue(TexturePixels.write(ARGB, 2, 2, RGB, UNSIGNED_BYTE, out, 0));
        assertArrayEquals(new byte[] {0, 0, (byte) 255}, slice(out, 0, 3), "bottom-left is blue");
    }

    @Test
    void theDestinationPositionIsRespected() {
        ByteBuffer out = ByteBuffer.allocate(16 + 4);
        assertTrue(TexturePixels.write(ARGB, 1, 1, RGBA, UNSIGNED_BYTE, out, 16));
        assertArrayEquals(new byte[] {(byte) 255, 0, 0, (byte) 255}, slice(out, 16, 4),
                "the one texel lands where the caller's buffer points");
        assertArrayEquals(new byte[] {0, 0, 0, 0}, slice(out, 0, 4), "and nothing before it is touched");
    }

    @Test
    void anUnsupportedPairIsRefusedRatherThanGuessed() {
        ByteBuffer out = ByteBuffer.allocate(64);
        assertFalse(TexturePixels.write(ARGB, 2, 2, 6403, UNSIGNED_BYTE, out, 0), "GL_RED is not converted");
        assertFalse(TexturePixels.write(ARGB, 2, 2, RGBA, 5126, out, 0), "GL_FLOAT is not converted");
    }

    @Test
    void aDestinationThatIsTooSmallIsAnError() {
        ByteBuffer out = ByteBuffer.allocate(8);
        assertThrows(IllegalArgumentException.class,
                () -> TexturePixels.write(ARGB, 2, 2, RGBA, UNSIGNED_BYTE, out, 0));
    }

    private static byte[] slice(ByteBuffer out, int from, int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = out.get(from + i);
        }
        return bytes;
    }
}
