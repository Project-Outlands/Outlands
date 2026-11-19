package net.minecraftforge.client.render.shim;

import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * Converts a texture's pixels into the byte layout {@code glGetTexImage} promises for a (format, type) pair.
 *
 * <p><b>Why a separate class.</b> The conversion is the only part of the read-back that is pure arithmetic, so it is
 * the only part that can be tested without a device - and it is where the mistakes live: GL's rows run bottom-up
 * while this backend stores them top-down, and the pack's exporters ask for RGBA while every driver's framebuffer
 * format is BGRA. Both are one line here and invisible in a picture.</p>
 */
final class TexturePixels {

    /** {@code GL_RGBA}. */
    static final int GL_RGBA = 6408;
    /** {@code GL_BGRA}. */
    static final int GL_BGRA = 32993;
    /** {@code GL_RGB}. */
    static final int GL_RGB = 6407;
    /** {@code GL_UNSIGNED_BYTE}. */
    static final int GL_UNSIGNED_BYTE = 5121;
    /** {@code GL_UNSIGNED_INT_8_8_8_8_REV}: four bytes per pixel, packed in an int as {@code 0xAARRGGBB}. */
    static final int GL_UNSIGNED_INT_8_8_8_8_REV = 33639;

    private TexturePixels() {}

    /**
     * The inverse of {@link #write}: reads {@code width x height} pixels into ARGB ints, rows top-down.
     *
     * <p><b>Why the top-down flip is here and not at the call site.</b> {@code glTexImage2D} receives its rows in GL's
     * order, bottom-up, and this backend stores textures top-down. Getting that wrong draws every texture a mod uploads
     * upside down, which looks like a broken texture rather than a row-order mistake - the same failure the read-back
     * side has, mirrored.</p>
     *
     * @return the pixels, or {@code null} for a (format, type) pair this method does not convert
     */
    static int @Nullable [] read(int width, int height, int format, int type, ByteBuffer in) {
        boolean packed = type == GL_UNSIGNED_INT_8_8_8_8_REV && (format == GL_BGRA || format == GL_RGBA);
        int channels = format == GL_RGB ? 3 : 4;
        boolean bytes = type == GL_UNSIGNED_BYTE && (format == GL_RGBA || format == GL_BGRA || format == GL_RGB);
        if (!packed && !bytes) {
            return null;
        }
        int pixelBytes = packed ? 4 : channels;
        int needed = width * height * pixelBytes;
        if (in.remaining() < needed) {
            throw new IllegalArgumentException("glTexImage2D needs " + needed + " bytes for " + width + "x" + height
                    + ", the source has " + in.remaining());
        }
        int[] argb = new int[width * height];
        int base = in.position();
        boolean bgra = format == GL_BGRA;
        for (int row = 0; row < height; row++) {
            int source = (height - 1 - row) * width;
            int rowStart = base + row * width * pixelBytes;
            for (int column = 0; column < width; column++) {
                int p = rowStart + column * pixelBytes;
                int pixel;
                if (packed) {
                    // Already 0xAARRGGBB: the byte order the enum pair describes is this int's own byte order.
                    pixel = in.getInt(p);
                } else {
                    int r = in.get(p) & 0xFF;
                    int g = in.get(p + 1) & 0xFF;
                    int b = in.get(p + 2) & 0xFF;
                    int a = channels == 4 ? (in.get(p + 3) & 0xFF) : 0xFF;
                    if (bgra) {
                        int swap = r;
                        r = b;
                        b = swap;
                    }
                    pixel = (a << 24) | (r << 16) | (g << 8) | b;
                }
                argb[source + column] = pixel;
            }
        }
        return argb;
    }

    /**
     * Writes {@code argb} (rows top-down) into {@code out} at {@code at}, in the requested layout and GL's row order.
     *
     * @return whether the pair is one this method converts; {@code false} leaves {@code out} untouched
     */
    /**
     * Writes {@code argb} (rows top-down) into an int buffer, one texel per int, in GL's row order.
     *
     * <p><b>Why this exists at all.</b> {@code ScreenShotHelper.createScreenshot} reads the framebuffer with
     * {@code glGetTexImage(GL_TEXTURE_2D, 0, GL_BGRA, GL_UNSIGNED_BYTE, IntBuffer)}: four bytes per texel delivered
     * through an int buffer, which is exactly the {@code 0xAARRGGBB} this backend already stores, so
     * {@code GL_BGRA} is a row-flipped copy and nothing more. That call used to be answered with a blank buffer (on
     * the grounds that "vanilla's callers are routed earlier"), which is the black {@code icon.png} in every save.</p>
     *
     * @return whether the pair is one this method converts; {@code false} leaves {@code out} untouched
     */
    static boolean writeInts(int[] argb, int width, int height, int format, int type, IntBuffer out, int at) {
        if (type != GL_UNSIGNED_BYTE || (format != GL_RGBA && format != GL_BGRA)) {
            return false;
        }
        int needed = width * height;
        if (out.limit() - at < needed) {
            throw new IllegalArgumentException("glGetTexImage needs " + needed + " ints for " + width + "x" + height
                    + ", the destination has " + (out.limit() - at) + " from position " + at);
        }
        boolean bgra = format == GL_BGRA;
        for (int row = 0; row < height; row++) {
            // GL's origin is the bottom-left and the stored image's is the top-left, the same flip the byte form
            // documents.
            int source = (height - 1 - row) * width;
            for (int column = 0; column < width; column++) {
                int pixel = argb[source + column];
                if (bgra) {
                    out.put(at + row * width + column, pixel);
                } else {
                    int red = (pixel >>> 16) & 0xFF;
                    int green = (pixel >>> 8) & 0xFF;
                    int blue = pixel & 0xFF;
                    int alpha = (pixel >>> 24) & 0xFF;
                    // GL_RGBA + GL_UNSIGNED_BYTE packs the bytes R,G,B,A, which little-endian is 0xAABBGGRR.
                    out.put(at + row * width + column, (alpha << 24) | (blue << 16) | (green << 8) | red);
                }
            }
        }
        return true;
    }

    static boolean write(int[] argb, int width, int height, int format, int type, ByteBuffer out, int at) {
        if (type != GL_UNSIGNED_BYTE || (format != GL_RGBA && format != GL_BGRA && format != GL_RGB)) {
            return false;
        }
        int channels = format == GL_RGB ? 3 : 4;
        int bytes = width * height * channels;
        if (out.limit() - at < bytes) {
            throw new IllegalArgumentException("glGetTexImage needs " + bytes + " bytes for " + width + "x" + height
                    + ", the destination has " + (out.limit() - at) + " from position " + at);
        }
        boolean bgra = format == GL_BGRA;
        for (int row = 0; row < height; row++) {
            // GL's origin is the bottom-left, the stored image's is the top-left: row 0 of the output is the last row
            // of the texture, which is what a caller writing a PNG in GL's order expects to see.
            int source = (height - 1 - row) * width;
            int rowStart = at + row * width * channels;
            for (int column = 0; column < width; column++) {
                int pixel = argb[source + column];
                int p = rowStart + column * channels;
                int r = (pixel >>> 16) & 0xFF;
                int g = (pixel >>> 8) & 0xFF;
                int b = pixel & 0xFF;
                if (bgra) {
                    out.put(p, (byte) b).put(p + 1, (byte) g).put(p + 2, (byte) r);
                    if (channels == 4) {
                        out.put(p + 3, (byte) ((pixel >>> 24) & 0xFF));
                    }
                } else {
                    out.put(p, (byte) r).put(p + 1, (byte) g).put(p + 2, (byte) b);
                    if (channels == 4) {
                        out.put(p + 3, (byte) ((pixel >>> 24) & 0xFF));
                    }
                }
            }
        }
        return true;
    }
}
