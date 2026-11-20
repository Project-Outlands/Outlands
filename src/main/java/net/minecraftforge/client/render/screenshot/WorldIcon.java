package net.minecraftforge.client.render.screenshot;

import net.minecraftforge.client.render.RenderBackendSelection;
import net.minecraftforge.client.render.vertex.FrameDraws;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.jspecify.annotations.Nullable;

import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.nio.ByteBuffer;

/**
 * The picture a save is listed with, {@code icon.png}, for a renderer that has no {@code glGetTexImage}.
 *
 * <p><b>Why vanilla's path cannot be adapted.</b> {@code EntityRenderer.createWorldIcon} calls
 * {@code ScreenShotHelper.createScreenshot}, which reads the framebuffer's colour attachment with
 * {@code glGetTexImage} - synchronously, because the very next thing it does is crop the result and write it. This
 * backend records a frame instead of executing it, so there is nothing on the host to read at that point; the shim
 * answered with a blank buffer and every save's icon was a 91-byte black square.</p>
 *
 * <p><b>The read happens at the game's own place in the frame, not at the end of it.</b> That is not a detail: vanilla
 * takes the picture right after the world is rendered and before the in-game HUD is drawn, so its icon has no hotbar.
 * An earlier version of this class copied the window after the frame's last draw and produced an icon with one - which
 * is exactly the kind of difference a screenshot-shaped shortcut makes. Recording a {@code ReadPixels} operation where
 * the game asked is the same mechanism {@code glReadPixels} already uses, so the picture is cut at the same point
 * vanilla cuts it.</p>
 *
 * <p><b>The crop lives here, not in the backend.</b> Vanilla's shape is "centre square, scaled to 64x64, written to
 * the save's {@code icon.png}"; the backend only owes a read-back of the rectangle. Everything about the file lives in
 * this package, as it does for a screenshot.</p>
 */
public final class WorldIcon {

    private static final Logger LOGGER = LogManager.getLogger("Outlands|render");

    /** Vanilla's icon size; {@code EntityRenderer} builds a 64x64 image too. */
    private static final int SIZE = 64;

    // GL enums, as the game's own read uses them.
    private static final int GL_BGRA = 32993;
    private static final int GL_UNSIGNED_BYTE = 5121;

    /** The icon being read back, and the buffer its pixels will land in. */
    private static @Nullable File waitingFor;
    private static @Nullable ByteBuffer destination;
    private static int readWidth;
    private static int readHeight;

    private WorldIcon() {}

    /**
     * Asks for the world's icon, if the active renderer cannot take one itself.
     *
     * <p>Called from the patched {@code EntityRenderer.createWorldIcon}, at the point vanilla would have read the
     * framebuffer. Recording the read here is what keeps the HUD out of the picture.</p>
     *
     * @param file   the save's {@code icon.png}
     * @param width  the framebuffer's width in pixels
     * @param height the framebuffer's height in pixels
     * @return whether the backend has taken the request, in which case vanilla's own readback must be skipped
     */
    public static boolean request(File file, int width, int height) {
        if (RenderBackendSelection.glPath()) {
            return false;
        }
        if (waitingFor != null) {
            // Already asked for and not yet written; asking again would only queue a second read of the same frame.
            return true;
        }
        if (width <= 0 || height <= 0 || (long) width * height * 4L > Integer.MAX_VALUE) {
            LOGGER.error("could not take the world icon for {}: {}x{} is not a size this renderer can read back",
                    file, width, height);
            return true;
        }
        ByteBuffer bytes = ByteBuffer.allocateDirect(width * height * 4);
        if (!FrameDraws.recordReadPixels(0, 0, width, height, GL_BGRA, GL_UNSIGNED_BYTE, bytes)) {
            LOGGER.error("could not take the world icon for {}: the frame is not collecting reads", file);
            return true;
        }
        waitingFor = file;
        destination = bytes;
        readWidth = width;
        readHeight = height;
        return true;
    }

    /**
     * Writes the icon when a finished read-back was the one this class asked for.
     *
     * <p>Called by the backend once a read's pixels are in host memory. The rows arrive in GL's order - bottom row
     * first - because that is what {@code glReadPixels} promises; the PNG wants them the other way up.</p>
     *
     * @return whether the read was this class's, so the caller knows nothing else needs to be told
     */
    public static boolean consume(ByteBuffer read, int width, int height) {
        File file = waitingFor;
        if (file == null || read != destination) {
            return false;
        }
        waitingFor = null;
        destination = null;
        try {
            BufferedImage source = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            int[] pixels = ((DataBufferInt) source.getRaster().getDataBuffer()).getData();
            for (int row = 0; row < height; row++) {
                int from = (height - 1 - row) * width * 4;
                for (int column = 0; column < width; column++) {
                    int at = from + column * 4;
                    // GL_BGRA through a byte buffer: blue, green, red, alpha.
                    int blue = read.get(at) & 0xFF;
                    int green = read.get(at + 1) & 0xFF;
                    int red = read.get(at + 2) & 0xFF;
                    pixels[row * width + column] = 0xFF000000 | (red << 16) | (green << 8) | blue;
                }
            }

            // Vanilla's own crop, to the pixel: the largest square in the middle of the window, scaled to 64x64. A
            // window is wider than it is tall, so the square comes out of the middle and the sides are dropped.
            int square = Math.min(width, height);
            int left = (width - square) / 2;
            int top = (height - square) / 2;
            BufferedImage icon = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
            Graphics graphics = icon.createGraphics();
            try {
                graphics.drawImage(source, 0, 0, SIZE, SIZE, left, top, left + square, top + square, null);
            } finally {
                graphics.dispose();
            }
            javax.imageio.ImageIO.write(icon, "png", file);
        } catch (Exception e) {
            LOGGER.error("could not write the world icon {}", file, e);
        }
        return true;
    }
}
