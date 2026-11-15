package net.minecraftforge.client.render.shim;

import java.nio.ByteBuffer;

/**
 * Turns a framebuffer read-back into the layout {@code glReadPixels} promises for a (format, type) pair.
 *
 * <p><b>Two conversions, both invisible and both wrong in a picture.</b> The window's image is BGRA while GL's
 * {@code GL_RGBA} asks for RGBA, and the copy arrives top-down while GL's origin is the bottom-left. An icon built
 * from a copy that skipped either is blue and upside down - which is exactly the shape of the faults this renderer
 * keeps finding, so it is a separate, testable class.</p>
 */
public final class FramebufferPixels {

    /** {@code GL_RGBA}. */
    private static final int GL_RGBA = 6408;
    /** {@code GL_BGRA}. */
    private static final int GL_BGRA = 32993;
    /** {@code GL_RGB}. */
    private static final int GL_RGB = 6407;
    /** {@code GL_UNSIGNED_BYTE}. */
    private static final int GL_UNSIGNED_BYTE = 5121;

    private FramebufferPixels() {}

    /**
     * Writes {@code bgra} (rows top-down, as copied) into the request's destination, in the request's layout and GL's
     * row order.
     *
     * @return whether the request is one this method converts
     */
    public static boolean write(byte[] bgra, int width, int height,
                                net.minecraftforge.client.render.vertex.FrameDraws.ReadPixels request) {
        int format = request.format();
        if (request.type() != GL_UNSIGNED_BYTE || (format != GL_RGBA && format != GL_BGRA && format != GL_RGB)) {
            return false;
        }
        ByteBuffer out = request.destination();
        int channels = format == GL_RGB ? 3 : 4;
        int bytes = width * height * channels;
        int at = out.position();
        if (out.limit() - at < bytes) {
            // GL reads as much as fits rather than throwing, but a caller that asked for a size it cannot hold has a
            // bug worth naming, and silently truncating would hide it.
            throw new IllegalArgumentException("glReadPixels needs " + bytes + " bytes for " + width + "x" + height
                    + ", the destination has " + (out.limit() - at) + " from position " + at);
        }
        // GL_RGBA and GL_RGB both name the channels red-first; only GL_BGRA wants the copy's own order.
        boolean swap = format != GL_BGRA;
        for (int row = 0; row < height; row++) {
            // GL's row 0 is the bottom of the framebuffer; the copy's row 0 is the top.
            int source = (height - 1 - row) * width * 4;
            int rowStart = at + row * width * channels;
            for (int column = 0; column < width; column++) {
                int from = source + column * 4;
                int to = rowStart + column * channels;
                byte b = bgra[from];
                byte g = bgra[from + 1];
                byte r = bgra[from + 2];
                byte a = bgra[from + 3];
                if (swap) {
                    out.put(to, r).put(to + 1, g).put(to + 2, b);
                } else {
                    out.put(to, b).put(to + 1, g).put(to + 2, r);
                }
                if (channels == 4) {
                    out.put(to + 3, a);
                }
            }
        }
        return true;
    }
}
