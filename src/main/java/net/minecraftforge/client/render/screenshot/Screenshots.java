package net.minecraftforge.client.render.screenshot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraftforge.client.render.RenderBackendSelection;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.jspecify.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

/**
 * F2 screenshots, for a renderer that has no {@code glReadPixels}.
 *
 * <p><b>Why vanilla's path cannot be adapted.</b> {@code ScreenShotHelper.createScreenshot} binds the framebuffer and
 * calls {@code glReadPixels}, which expects the picture to still be there. Under Vulkan the window's image is owned by
 * the swapchain: at the moment the game asks, the frame it is looking at has already been presented and its image may
 * be reused by a later frame. So the request is taken here, and the bytes are read from the frame the backend is about
 * to record - which is the frame the player is looking at, one tick later. The file name is chosen when the request
 * arrives, so the chat message can be accurate before the write happens.</p>
 *
 * <p><b>What the backend owes.</b> {@link #take()} once per frame gives it a request to satisfy; it copies the image
 * into host memory and calls {@link #deliver} with tightly packed RGBA rows. Everything about naming, files and chat
 * lives here, so the backend only has to know how to read its own pixels.</p>
 */
public final class Screenshots {

    private static final Logger LOGGER = LogManager.getLogger("Outlands|render");

    /** Vanilla's name for the screenshots directory, relative to the game directory. */
    private static final String DIRECTORY = "screenshots";

    private static final AtomicLong SEQUENCE = new AtomicLong();

    /** A request the backend has not satisfied yet. */
    public record Request(int width, int height, File file, ITextComponent message) {}

    private static volatile @Nullable Request pending;

    private Screenshots() {}

    /**
     * Asks for a screenshot, if the active renderer cannot take one itself.
     *
     * <p>Called from the patched {@code ScreenShotHelper}. Returns {@code false} on the OpenGL path, where vanilla's
     * own readback runs unchanged.</p>
     *
     * @param width  the framebuffer width in pixels
     * @param height the framebuffer height in pixels
     * @param name   the name vanilla was given, or {@code null} for a timestamped one
     * @return the message to show, or {@code null} when the caller should run vanilla's own readback
     */
    public static @Nullable ITextComponent request(int width, int height, @Nullable String name) {
        if (RenderBackendSelection.glPath()) {
            return null;
        }
        File gameDirectory = Minecraft.getMinecraft().gameDir;
        File directory = new File(gameDirectory, DIRECTORY);
        if (!directory.isDirectory() && !directory.mkdir()) {
            LOGGER.error("could not create the screenshot directory {}", directory);
            return null;
        }
        File file = name != null ? new File(directory, name) : timestampedFile(directory);
        ITextComponent message = successMessage(file);
        pending = new Request(width, height, file, message);
        return message;
    }

    /** Takes the outstanding request, if any. Called by the backend at the start of a frame. */
    public static @Nullable Request take() {
        Request request = pending;
        pending = null;
        return request;
    }

    /**
     * Writes the pixels of the frame that satisfied a request.
     *
     * @param rgba   the picture, four bytes per pixel, first row at the top, as Vulkan reads it back
     * @param width  the picture's width
     * @param height the picture's height
     */
    public static void deliver(Request request, byte[] rgba, int width, int height) {
        try {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            // Fully transparent pixels would come out black in the PNG's RGB channels otherwise; the window is opaque,
            // so every pixel has its alpha set.
            java.awt.image.DataBufferInt buffer = (java.awt.image.DataBufferInt) image.getRaster().getDataBuffer();
            int[] pixels = buffer.getData();
            // Rows go in as they were read. Vulkan's row 0 is the top of the picture, which is the order a PNG wants;
            // GL's readback starts at the bottom and vanilla flips it for that reason, so copying that flip here turned
            // every screenshot upside down - measured, with the hotbar at the top of the file.
            for (int i = 0; i < width * height; i++) {
                int at = i * 4;
                int red = rgba[at] & 0xFF;
                int green = rgba[at + 1] & 0xFF;
                int blue = rgba[at + 2] & 0xFF;
                pixels[i] = 0xFF000000 | (red << 16) | (green << 8) | blue;
            }
            Files.createDirectories(request.file().toPath().getParent());
            javax.imageio.ImageIO.write(image, "png", request.file());
        } catch (Exception e) {
            LOGGER.error("could not write the screenshot {}", request.file(), e);
            post(new TextComponentString("无法保存截图: " + e));
        }
    }

    /** Reports that the request could not be satisfied, without pretending a file exists. */
    public static void failed(Request request, String reason) {
        LOGGER.error("could not take the screenshot: {}", reason);
        post(new TextComponentString("无法保存截图: " + reason));
    }

    private static void post(ITextComponent message) {
        Minecraft minecraft = Minecraft.getMinecraft();
        GuiIngame gui = minecraft.ingameGUI;
        if (gui != null) {
            gui.getChatGUI().printChatMessage(message);
        }
    }

    private static ITextComponent successMessage(File file) {
        ITextComponent name = new TextComponentString(file.getName());
        name.getStyle().setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, file.getAbsolutePath()));
        name.getStyle().setUnderlined(true);
        return new TextComponentTranslation("screenshot.success", name);
    }

    /**
     * A name vanilla's own screenshots could have.
     *
     * <p>Same format as {@code ScreenShotHelper} so the two renderers' files sort together, with the sequence suffix
     * that vanilla adds when a name is taken.</p>
     */
    private static File timestampedFile(File directory) {
        DateFormat format = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss");
        String base = format.format(new Date());
        File file = new File(directory, base + ".png");
        while (file.exists()) {
            file = new File(directory, base + "_" + SEQUENCE.incrementAndGet() + ".png");
        }
        return file;
    }

    /** Used by the backend when the image format is one it has to swizzle. */
    public static byte[] swizzleBgra(ByteBuffer source, int width, int height) {
        byte[] rgba = new byte[width * height * 4];
        for (int i = 0; i < width * height; i++) {
            int at = i * 4;
            byte blue = source.get(at);
            byte green = source.get(at + 1);
            byte red = source.get(at + 2);
            rgba[at] = red;
            rgba[at + 1] = green;
            rgba[at + 2] = blue;
            rgba[at + 3] = (byte) 0xFF;
        }
        return rgba;
    }
}
