package net.minecraftforge.client.render.shim;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void theIntFormIsTheSameRowsThroughAnIntBuffer() {
        // ScreenShotHelper.createScreenshot's own call: GL_BGRA + GL_UNSIGNED_BYTE into an IntBuffer. Four bytes per
        // texel through an int buffer is 0xAARRGGBB on little-endian, which is what the backend already stores, so
        // GL_BGRA has to come out identical to the stored texels - just bottom-up.
        java.nio.IntBuffer out = java.nio.IntBuffer.allocate(4);
        assertTrue(TexturePixels.writeInts(ARGB, 2, 2, BGRA, UNSIGNED_BYTE, out, 0));
        assertArrayEquals(new int[] {0xFF0000FF, 0xFFFFFFFF, 0xFFFF0000, 0xFF00FF00},
                new int[] {out.get(0), out.get(1), out.get(2), out.get(3)},
                "bottom row first, and the texels themselves unchanged");
    }

    @Test
    void theIntFormPacksRgbaTheWayLittleEndianDoes() {
        java.nio.IntBuffer out = java.nio.IntBuffer.allocate(1);
        // One texel, GL_RGBA: the bytes go out R,G,B,A, so the int holding them is 0xAABBGGRR.
        assertTrue(TexturePixels.writeInts(new int[] {0x11223344}, 1, 1, RGBA, UNSIGNED_BYTE, out, 0));
        org.junit.jupiter.api.Assertions.assertEquals(0x11443322, out.get(0),
                "R=0x11 G=0x22 B=0x33 A=0x44 packs as A,B,G,R little-endian");
    }

    @Test
    void theIntFormRefusesWhatItDoesNotConvert() {
        java.nio.IntBuffer out = java.nio.IntBuffer.allocate(4);
        assertFalse(TexturePixels.writeInts(ARGB, 2, 2, RGB, UNSIGNED_BYTE, out, 0),
                "three channels do not fit one int per texel the way a caller means it");
        // And it is loud when the destination is too small, rather than writing past the end.
        assertThrows(IllegalArgumentException.class,
                () -> TexturePixels.writeInts(ARGB, 2, 2, BGRA, UNSIGNED_BYTE, java.nio.IntBuffer.allocate(3), 0));
    }

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

    // ---------------------------------------------------------------------------------------------------------------
    // The inverse, for glTexImage2D. Same two facts, mirrored: the rows arrive bottom-up and the channels are whatever
    // the caller's enum pair says. A mod uploading its own texture through the raw call is the only user, so getting
    // it wrong shows up as one mod's texture upside down - or blue - and nothing in the log.
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void readFlipsTheRowsBack() {
        ByteBuffer in = ByteBuffer.allocate(2 * 2 * 4);
        // GL order: bottom row (blue, white) first, then the top row (red, green).
        in.put(new byte[] {0, 0, (byte) 255, (byte) 255, (byte) 255, (byte) 255, (byte) 255, (byte) 255});
        in.put(new byte[] {(byte) 255, 0, 0, (byte) 255, 0, (byte) 255, 0, (byte) 255});
        in.flip();

        assertArrayEquals(ARGB, TexturePixels.read(2, 2, RGBA, UNSIGNED_BYTE, in), "rows top-down after the flip");
    }

    @Test
    void readSwapsBlueAndRedForBgra() {
        ByteBuffer in = ByteBuffer.allocate(4);
        in.put(new byte[] {0, 0, (byte) 255, (byte) 255}).flip();
        // BGRA bytes B=0,G=0,R=255,A=255 are the int 0xFFFF0000 - red.
        assertArrayEquals(new int[] {0xFFFF0000}, TexturePixels.read(1, 1, BGRA, UNSIGNED_BYTE, in));
    }

    @Test
    void readTakesPackedIntsAsTheyAre() {
        ByteBuffer in = ByteBuffer.allocate(4);
        // GL_BGRA + GL_UNSIGNED_INT_8_8_8_8_REV is the pair vanilla uploads with: the int already is 0xAARRGGBB.
        in.putInt(0xFF3366CC).flip();
        assertArrayEquals(new int[] {0xFF3366CC}, TexturePixels.read(1, 1, BGRA, 33639, in));
    }

    @Test
    void readFillsOpaqueAlphaForRgb() {
        ByteBuffer in = ByteBuffer.allocate(3);
        in.put(new byte[] {10, 20, 30}).flip();
        assertArrayEquals(new int[] {0xFF0A141E}, TexturePixels.read(1, 1, RGB, UNSIGNED_BYTE, in));
    }

    @Test
    void readDeclinesAPairItCannotConvert() {
        ByteBuffer in = ByteBuffer.allocate(8);
        assertNull(TexturePixels.read(1, 1, RGBA, 5124, in), "GL_INT is not a layout the byte contract converts");
    }
}
