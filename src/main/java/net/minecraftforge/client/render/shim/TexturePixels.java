package net.minecraftforge.client.render.shim;

import java.nio.ByteBuffer;

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

    private TexturePixels() {}

    /**
     * Writes {@code argb} (rows top-down) into {@code out} at {@code at}, in the requested layout and GL's row order.
     *
     * @return whether the pair is one this method converts; {@code false} leaves {@code out} untouched
     */
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
