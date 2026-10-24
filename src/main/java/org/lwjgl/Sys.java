package org.lwjgl;

import static org.lwjgl.sdl.SDLInit.SDL_INIT_EVENTS;
import static org.lwjgl.sdl.SDLInit.SDL_INIT_GAMEPAD;
import static org.lwjgl.sdl.SDLInit.SDL_INIT_HAPTIC;
import static org.lwjgl.sdl.SDLInit.SDL_INIT_JOYSTICK;
import static org.lwjgl.sdl.SDLInit.SDL_INIT_SENSOR;
import static org.lwjgl.sdl.SDLInit.SDL_INIT_VIDEO;
import static org.lwjgl.sdl.SDLInit.SDL_Init;
import static org.lwjgl.sdl.SDLInit.SDL_PROP_APP_METADATA_CREATOR_STRING;
import static org.lwjgl.sdl.SDLInit.SDL_PROP_APP_METADATA_TYPE_STRING;
import static org.lwjgl.sdl.SDLInit.SDL_PROP_APP_METADATA_URL_STRING;
import static org.lwjgl.sdl.SDLInit.SDL_SetAppMetadata;
import static org.lwjgl.sdl.SDLInit.SDL_SetAppMetadataProperty;

import java.net.URI;
import java.util.Objects;

import org.lwjgl.sdl.SDLClipboard;
import org.lwjgl.sdl.SDLHints;
import org.lwjgl.sdl.SDLMessageBox;
import org.lwjgl.sdl.SDLMisc;
import org.lwjgl.sdl.SDLStdinc;
import org.lwjgl.sdl.SDLTimer;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Platform;
import org.lwjgl.system.Pointer;

import com.cleanroommc.common.CleanroomVersion;
import net.minecraftforge.common.ForgeEarlyConfig;

/**
 * LWJGL2 system/runtime services, backed by SDL3.
 */
public class Sys {

    private static boolean initialized = false;

    /**
     * Brings up the SDL subsystems. Idempotent; must be reached from the main thread.
     */
    public static void initialize() {
        if (initialized) {
            return;
        }
        // GL.create() must not run from GL's static initializer: at that point no context is current,
        // so the function pointers would resolve against nothing. Display.create() drives it instead.
        Configuration.OPENGL_EXPLICIT_INIT.set(true);
        MainThread.runOnMainThread(() -> {
            if (Platform.get() == Platform.MACOSX) {
                Configuration.HARFBUZZ_LIBRARY_NAME.set("freetype");
            }
            applyPlatformHints();
            applyAppMetadata();
            if (!SDL_Init(
                SDL_INIT_VIDEO | SDL_INIT_EVENTS
                    | SDL_INIT_JOYSTICK
                    | SDL_INIT_GAMEPAD
                    | SDL_INIT_HAPTIC
                    | SDL_INIT_SENSOR)) {
                throw new SDLException("Could not initialize SDL");
            }
            initialized = true;
        });
    }

    /** Applies the per-platform driver hints. */
    private static void applyPlatformHints() {
        final Platform platform = Platform.get();
        switch (platform) {
            case MACOSX -> SDLHints.SDL_SetHint(SDLHints.SDL_HINT_MAC_OPENGL_ASYNC_DISPATCH, "1");
            case LINUX -> {
                SDLHints.SDL_SetHint(SDLHints.SDL_HINT_VIDEO_FORCE_EGL, "1");
                SDLStdinc.SDL_setenv_unsafe("__GL_THREADED_OPTIMIZATIONS", "0", 1);
            }
            default -> {}
        }
        SDLHints.SDL_SetHint(SDLHints.SDL_HINT_MOUSE_FOCUS_CLICKTHROUGH, "1");
        // Declare that the game draws the IME composition string itself: SDL then reports it through
        // SDL_EVENT_TEXT_EDITING and stops painting its own composition window. Candidates stay native,
        // which is why 'candidates' is absent here.
        SDLHints.SDL_SetHint(SDLHints.SDL_HINT_IME_IMPLEMENTED_UI, "composition");
        if (platform == Platform.LINUX && isWaylandSession()) {
            SDLHints.SDL_SetHint(SDLHints.SDL_HINT_VIDEO_DRIVER, ForgeEarlyConfig.FORCE_WAYLAND ? "wayland" : "x11");
        }
    }

    private static boolean isWaylandSession() {
        return Objects.requireNonNullElse(System.getenv("XDG_SESSION_TYPE"), "")
            .toLowerCase()
            .startsWith("wayland");
    }

    /** Publishes the loader identity to SDL; packs override the title and icon through Display. */
    private static void applyAppMetadata() {
        SDL_SetAppMetadata("Outlands", CleanroomVersion.getBuildVersion(), "top.outlands.outlands");
        SDL_SetAppMetadataProperty(SDL_PROP_APP_METADATA_URL_STRING, "https://outlands.top");
        SDL_SetAppMetadataProperty(SDL_PROP_APP_METADATA_CREATOR_STRING, "Outlands contributors");
        SDL_SetAppMetadataProperty(SDL_PROP_APP_METADATA_TYPE_STRING, "game");
    }

    /** Returns the LWJGL version. */
    public static String getVersion() {
        return Version.getVersion();
    }

    /**
     * Obtains the number of ticks that the hires timer does in a second. This method is fast; it should be called as
     * frequently as possible, as it recalibrates the timer.
     *
     * @return timer resolution in ticks per second or 0 if no timer is present.
     */
    public static long getTimerResolution() {
        return 1000;
    }

    /**
     * Gets the current value of the hires timer, in ticks. When the Sys class is first loaded the hires timer is reset
     * to 0. If no hires timer is present then this method will always return 0.
     * <p>
     * <strong>NOTEZ BIEN</strong> that the hires timer WILL wrap around.
     *
     * @return the current hires time, in ticks (always >= 0)
     */
    public static long getTime() {
        return SDLTimer.SDL_GetTicksNS() / 1_000_000L;
    }

    public static long getNanoTime() {
        return SDLTimer.SDL_GetTicksNS();
    }

    public static boolean openURL(String url) {
        return MainThread.runOnMainThread(() -> SDLMisc.SDL_OpenURL(url));
    }

    public static void alert(String title, String message) {
        MainThread.runOnMainThread(
            () -> SDLMessageBox.SDL_ShowSimpleMessageBox(
                SDLMessageBox.SDL_MESSAGEBOX_WARNING,
                title,
                message,
                org.lwjgl.opengl.Display.getWindow()));
    }

    public static boolean is64Bit() {
        return Pointer.BITS64;
    }

    public static String getClipboard() {
        final String text = MainThread.runOnMainThread(SDLClipboard::SDL_GetClipboardText);
        return text == null ? "" : text;
    }

    /** Convenience overload for callers holding a URI. */
    public static boolean openURL(URI uri) {
        return uri != null && openURL(uri.toString());
    }

    /**
     * Fails when an SDL call reported an error.
     *
     * @param result the SDL return value
     */
    public static void checkSdl(boolean result) {
        if (!result) {
            throw new SDLException();
        }
    }

    /**
     * Fails when an SDL call returned a null pointer.
     *
     * @param result the SDL return value
     * @return the same value
     */
    public static <T> T checkSdl(T result) {
        if (result == null) {
            throw new SDLException();
        }
        return result;
    }

    /** Fails when an SDL call returned zero, which SDL uses for null handles and error codes. */
    public static int checkSdl(int result) {
        if (result == 0) {
            throw new SDLException();
        }
        return result;
    }

    public static long checkSdl(long result) {
        if (result == 0) {
            throw new SDLException();
        }
        return result;
    }
}
