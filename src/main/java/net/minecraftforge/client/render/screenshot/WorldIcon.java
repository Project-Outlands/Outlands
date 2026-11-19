package net.minecraftforge.client.render.screenshot;

import net.minecraftforge.client.render.RenderBackendSelection;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.jspecify.annotations.Nullable;

import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;

/**
 * The picture a save is listed with, {@code icon.png}, for a renderer that has no {@code glGetTexImage}.
 *
 * <p><b>Why vanilla's path cannot be adapted.</b> {@code EntityRenderer.createWorldIcon} calls
 * {@code ScreenShotHelper.createScreenshot}, which binds the framebuffer's colour attachment and reads it back with
 * {@code glGetTexImage} — synchronously, because the very next thing it does is crop the result and write it. Under
 * Vulkan the picture is on the device and this backend records a frame rather than executing it, so nothing can be
 * read at that point; the shim's {@code glGetTexImage} is a no-op and the icon came out pure black, which is what a
 * zeroed pixel buffer is.</p>
 *
 * <p><b>So the request is taken here and the backend answers it with the next frame</b>, the same way
 * {@link Screenshots} answers F2. Nothing is lost by the delay: {@code createWorldIcon} keeps asking every frame
 * until the server reports the icon as set, and a world's listing picture is a still of the world either way.</p>
 *
 * <p><b>The crop lives here, not in the backend.</b> Vanilla's shape is "centre square, scaled to 64x64, written to
 * the save's icon.png"; the backend only has to hand over the window's pixels in RGBA rows. That keeps the backend
 * free of anything but its own pixels, exactly as for a screenshot.</p>
 */
public final class WorldIcon {

    private static final Logger LOGGER = LogManager.getLogger("Outlands|render");

    /** Vanilla's icon size; {@code EntityRenderer} builds a 64x64 image too. */
    private static final int SIZE = 64;

    /** A request the backend has not satisfied yet. */
    public record Request(File file) {}

    private static volatile @Nullable Request pending;

    private WorldIcon() {}

    /**
     * Asks for the world's icon, if the active renderer cannot take one itself.
     *
     * <p>Called from the patched {@code EntityRenderer.createWorldIcon}. Returns {@code false} on the OpenGL path,
     * where vanilla's own readback runs unchanged.</p>
     *
     * @param file the save's {@code icon.png}
     * @return whether the backend has taken the request, in which case vanilla's own path must be skipped
     */
    public static boolean request(File file) {
        if (RenderBackendSelection.glPath()) {
            return false;
        }
        if (pending != null) {
            // Already asked for and not yet served; asking again would only push the picture back a frame.
            return true;
        }
        pending = new Request(file);
        return true;
    }

    /** Takes the outstanding request, if any. Called by the backend at the start of a frame. */
    public static @Nullable Request take() {
        Request request = pending;
        pending = null;
        return request;
    }

    /**
     * Writes the icon from the pixels of the frame that satisfied a request.
     *
     * @param rgba   the window, four bytes per pixel, first row at the top, as Vulkan reads it back
     * @param width  the window's width
     * @param height the window's height
     */
    public static void deliver(Request request, byte[] rgba, int width, int height) {
        try {
            BufferedImage source = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            int[] pixels = ((DataBufferInt) source.getRaster().getDataBuffer()).getData();
            for (int i = 0; i < width * height; i++) {
                int at = i * 4;
                int red = rgba[at] & 0xFF;
                int green = rgba[at + 1] & 0xFF;
                int blue = rgba[at + 2] & 0xFF;
                pixels[i] = 0xFF000000 | (red << 16) | (green << 8) | blue;
            }

            // Vanilla's own crop, to the pixel: the largest square in the middle of the window, scaled to 64x64. The
            // window is wider than it is tall, so the square is taken out of the middle and the sides are dropped.
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
            javax.imageio.ImageIO.write(icon, "png", request.file());
        } catch (Exception e) {
            LOGGER.error("could not write the world icon {}", request.file(), e);
        }
    }

    /** Reports that the request could not be satisfied, without pretending a file exists. */
    public static void failed(Request request, String reason) {
        LOGGER.error("could not take the world icon for {}: {}", request.file(), reason);
    }
}
