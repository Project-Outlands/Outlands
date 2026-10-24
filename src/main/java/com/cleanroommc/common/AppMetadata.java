package com.cleanroommc.common;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;

import javax.imageio.ImageIO;

import org.lwjgl.opengl.Display;
import org.lwjgl.sdl.SDLInit;

/**
 * Application identity reported to the operating system: the AppUserModelID behind Windows notifications and
 * taskbar grouping, and the name and {@code app_id} Linux audio services and desktop notifications show.
 * <p>
 * Defaults to the loader's identity. A pack overrides it through {@link #set} or {@link #setTitle}; the last
 * call wins and takes effect immediately. Safe from any thread.
 */
public final class AppMetadata {

    /** Title the window starts with, before any pack overrides it. */
    public static final String DEFAULT_TITLE = "Outlands 1.12.2";

    /** Icon shipped in the loader resources, applied until a pack replaces it. */
    private static final String DEFAULT_ICON = "/assets/cleanroom/icon.png";


    /** Display name shown by the OS. */
    public static final String NAME = SDLInit.SDL_PROP_APP_METADATA_NAME_STRING;

    public static final String VERSION = SDLInit.SDL_PROP_APP_METADATA_VERSION_STRING;

    /** Reverse-domain identifier; becomes the desktop notification {@code app_id} on Linux. */
    public static final String IDENTIFIER = SDLInit.SDL_PROP_APP_METADATA_IDENTIFIER_STRING;

    public static final String CREATOR = SDLInit.SDL_PROP_APP_METADATA_CREATOR_STRING;

    public static final String COPYRIGHT = SDLInit.SDL_PROP_APP_METADATA_COPYRIGHT_STRING;

    public static final String URL = SDLInit.SDL_PROP_APP_METADATA_URL_STRING;

    /** Free-form; SDL documents {@code game} and {@code application}. */
    public static final String TYPE = SDLInit.SDL_PROP_APP_METADATA_TYPE_STRING;

    private AppMetadata() {}

    /**
     * Sets one property.
     *
     * @param key one of the constants on this class
     * @param value the value, or {@code null} to clear it
     * @return {@code false} when {@code key} is not a property SDL accepts
     */
    public static boolean set(String key, String value) {
        return SDLInit.SDL_SetAppMetadataProperty(key, value);
    }

    /** Sets name, version and identifier in one call. */
    public static void set(String name, String version, String identifier) {
        SDLInit.SDL_SetAppMetadata(name, version, identifier);
    }

    /** Reads a property back, or {@code null} when unset. */
    public static String get(String key) {
        return SDLInit.SDL_GetAppMetadataProperty(key);
    }

    /**
     * Window title, separate from {@link #NAME} which only reaches platform services.
     *
     * @param title the new title, or {@code null} to restore the default
     */
    public static void setTitle(String title) {
        Display.setTitle(title == null ? DEFAULT_TITLE : title);
    }

    public static String getTitle() {
        return Display.getTitle();
    }

    /**
     * Sets the window icon. Provide several sizes (16, 32, 256); the OS picks whichever fits the surface it
     * draws on. Callable before or after the window exists, from any thread.
     *
     * @throws IllegalArgumentException when an image is not square
     */
    public static void setIcon(BufferedImage... images) {
        if (images == null || images.length == 0) {
            throw new IllegalArgumentException("At least one icon image is required");
        }
        final ByteBuffer[] buffers = new ByteBuffer[images.length];
        for (int i = 0; i < images.length; i++) {
            buffers[i] = toRgba(images[i]);
        }
        Display.setIcon(buffers);
    }

    /** Sets the window icon from already encoded RGBA buffers, square and 4 bytes per pixel. */
    public static void setIcon(ByteBuffer... rgba) {
        Display.setIcon(rgba);
    }

    /** Applies the icon shipped in the loader resources, leaving the current one when it is absent. */
    public static void applyDefaultIcon() {
        try (InputStream stream = AppMetadata.class.getResourceAsStream(DEFAULT_ICON)) {
            if (stream == null) {
                return;
            }
            setIcon(ImageIO.read(stream));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load the default window icon " + DEFAULT_ICON, e);
        }
    }

    private static ByteBuffer toRgba(BufferedImage image) {
        final int width = image.getWidth();
        final int height = image.getHeight();
        if (width != height) {
            throw new IllegalArgumentException("Icon must be square, got " + width + "x" + height);
        }
        final int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        final ByteBuffer buffer = ByteBuffer.allocate(pixels.length * 4);
        for (final int argb : pixels) {
            buffer.putInt(Integer.rotateLeft(argb, 8));
        }
        buffer.flip();
        return buffer;
    }
}
