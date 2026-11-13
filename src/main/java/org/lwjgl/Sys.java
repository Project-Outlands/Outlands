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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLClipboard;
import org.lwjgl.sdl.SDLHints;
import org.lwjgl.sdl.SDLMessageBox;
import org.lwjgl.sdl.SDLMisc;
import org.lwjgl.sdl.SDLStdinc;
import org.lwjgl.sdl.SDLTimer;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Platform;
import org.lwjgl.system.Pointer;

import net.minecraftforge.common.LoaderVersion;
import net.minecraftforge.common.ForgeEarlyConfig;
import net.minecraftforge.fml.common.FMLLog;

/**
 * LWJGL2 system/runtime services, backed by SDL3.
 */
public class Sys {

    private static boolean initialized = false;

    /** The video driver the Linux hint handed to SDL, or {@code null} when SDL picks one by itself. */
    private static String requestedVideoDriver = null;

    /**
     * Brings up the SDL subsystems. Idempotent; the work is marshalled onto the process's first thread, which is the
     * thread that owns SDL video.
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
            if (!initSubsystems() && !retryWithPlatformDefaultDriver()) {
                throw new SDLException("Could not initialize SDL: " + SDLError.SDL_GetError());
            }
            logVideoDriver();
            initialized = true;
        });
    }

    private static boolean initSubsystems() {
        return SDL_Init(
            SDL_INIT_VIDEO | SDL_INIT_EVENTS
                | SDL_INIT_JOYSTICK
                | SDL_INIT_GAMEPAD
                | SDL_INIT_HAPTIC
                | SDL_INIT_SENSOR);
    }

    /**
     * A pinned video driver is a user preference, not a hard requirement: when the driver cannot come up (X11
     * requested on a session without a reachable X server, for instance) SDL fails the whole init. Fall back to
     * the platform default instead of refusing to start.
     */
    private static boolean retryWithPlatformDefaultDriver() {
        if (requestedVideoDriver == null) {
            return false;
        }
        final String rejected = requestedVideoDriver;
        final String error = SDLError.SDL_GetError();
        requestedVideoDriver = null;
        SDLHints.SDL_ResetHint(SDLHints.SDL_HINT_VIDEO_DRIVER);
        FMLLog.log.warn("SDL video driver '{}' could not be initialised ({}), falling back to the platform default",
            rejected, error);
        return initSubsystems();
    }

    private static void logVideoDriver() {
        final String current = SDLVideo.SDL_GetCurrentVideoDriver();
        final List<String> available = new ArrayList<>();
        for (int i = 0; i < SDLVideo.SDL_GetNumVideoDrivers(); i++) {
            available.add(SDLVideo.SDL_GetVideoDriver(i));
        }
        FMLLog.log.info("SDL video driver: {} (available: {})", current, String.join(", ", available));
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
        if (platform == Platform.LINUX) {
            requestedVideoDriver = applyLinuxVideoDriverHint();
        }
    }

    /**
     * Resolves the Linux video driver.
     * <p>
     * A Wayland session gets the Wayland driver. KWin and Mutter now implement what the game needs from a native
     * client - text-input for the IME, pointer constraints for relative mouse input, viewporter for fractional
     * scale - while XWayland costs fractional scale, adds a round trip per frame and captures worse. {@code x11}
     * stays available as an explicit choice for compositor bugs and X-only tooling, and
     * {@link #retryWithPlatformDefaultDriver()} covers a session whose Wayland socket cannot be reached by falling
     * back to the platform default order.
     *
     * @return the driver handed to SDL, or {@code null} when SDL should pick one itself
     */
    private static String applyLinuxVideoDriverHint() {
        final String driver = resolveLinuxVideoDriver(ForgeEarlyConfig.linuxVideoDriver, isWaylandSession());
        if (driver != null) {
            SDLHints.SDL_SetHint(SDLHints.SDL_HINT_VIDEO_DRIVER, driver);
        }
        return driver;
    }

    /**
     * Maps the {@code linuxVideoDriver} setting onto the driver SDL is asked for.
     *
     * @param configured the setting, possibly {@code null} or padded, may also be an SDL alias such as {@code xorg}
     * @param waylandSession whether a Wayland compositor is reachable
     * @return the driver handed to SDL, or {@code null} when SDL should pick one itself
     */
    static String resolveLinuxVideoDriver(String configured, boolean waylandSession) {
        final String requested = configured == null ? "auto" : configured.trim().toLowerCase(Locale.ENGLISH);
        return switch (requested) {
            case "x11", "xorg" -> "x11";
            case "wayland" -> "wayland";
            case "auto" -> waylandSession ? "wayland" : null;
            default -> {
                FMLLog.log.warn("Unknown LINUX_VIDEO_DRIVER '{}', expected auto, x11 or wayland - using auto", configured);
                yield resolveLinuxVideoDriver("auto", waylandSession);
            }
        };
    }

    /**
     * Whether the session offers a Wayland compositor. {@code XDG_SESSION_TYPE} covers a normal login;
     * {@code WAYLAND_DISPLAY} covers the sessions that do not export it (nested compositors, launchers that scrub
     * the environment) while the socket is still reachable.
     */
    private static boolean isWaylandSession() {
        if (Objects.requireNonNullElse(System.getenv("XDG_SESSION_TYPE"), "")
            .toLowerCase(Locale.ENGLISH)
            .startsWith("wayland")) {
            return true;
        }
        return !Objects.requireNonNullElse(System.getenv("WAYLAND_DISPLAY"), "").isEmpty();
    }

    /** Publishes the loader identity to SDL; packs override the title and icon through Display. */
    private static void applyAppMetadata() {
        SDL_SetAppMetadata("Outlands", LoaderVersion.getBuildVersion(), "top.outlands.outlands");
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
