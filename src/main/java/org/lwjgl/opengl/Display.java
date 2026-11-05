package org.lwjgl.opengl;

import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_KEY_DOWN;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_KEY_UP;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_KEYMAP_CHANGED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_MOUSE_BUTTON_UP;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_MOUSE_MOTION;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_MOUSE_WHEEL;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_QUIT;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_TEXT_INPUT;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_TEXT_EDITING;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_CLOSE_REQUESTED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_EXPOSED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_FOCUS_GAINED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_FOCUS_LOST;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_MINIMIZED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_MOVED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_RESIZED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_RESTORED;
import static org.lwjgl.sdl.SDLProperties.SDL_CreateProperties;
import static org.lwjgl.sdl.SDLProperties.SDL_DestroyProperties;
import static org.lwjgl.sdl.SDLProperties.SDL_SetBooleanProperty;
import static org.lwjgl.sdl.SDLProperties.SDL_SetNumberProperty;
import static org.lwjgl.sdl.SDLProperties.SDL_SetStringProperty;
import static org.lwjgl.sdl.SDLVideo.SDL_CreateWindowWithProperties;
import static org.lwjgl.sdl.SDLVideo.SDL_DestroyWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_GetDesktopDisplayMode;
import static org.lwjgl.sdl.SDLVideo.SDL_GetFullscreenDisplayModes;
import static org.lwjgl.sdl.SDLVideo.SDL_GetDisplayBounds;
import static org.lwjgl.sdl.SDLVideo.SDL_GetDisplayForWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_GetDisplays;
import static org.lwjgl.sdl.SDLVideo.SDL_GetPrimaryDisplay;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowFlags;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowPosition;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowSize;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowSizeInPixels;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowTitle;
import static org.lwjgl.sdl.SDLVideo.SDL_SetWindowBordered;
import static org.lwjgl.sdl.SDLVideo.SDL_SetWindowFullscreen;
import static org.lwjgl.sdl.SDLVideo.SDL_SetWindowIcon;
import static org.lwjgl.sdl.SDLVideo.SDL_SetWindowPosition;
import static org.lwjgl.sdl.SDLVideo.SDL_SetWindowResizable;
import static org.lwjgl.sdl.SDLVideo.SDL_SetWindowSize;
import static org.lwjgl.sdl.SDLVideo.SDL_SetWindowTitle;
import static org.lwjgl.sdl.SDLVideo.SDL_ShowWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_SyncWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_WINDOWPOS_CENTERED;
import static org.lwjgl.sdl.SDLVideo.SDL_WINDOW_BORDERLESS;
import static org.lwjgl.sdl.SDLVideo.SDL_WINDOW_HIDDEN;
import static org.lwjgl.sdl.SDLVideo.SDL_WINDOW_INPUT_FOCUS;
import static org.lwjgl.sdl.SDLVideo.SDL_WINDOW_MINIMIZED;
import static org.lwjgl.sdl.SDLVideo.SDL_WINDOW_OPENGL;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memUTF8;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.DoubleSupplier;

import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.ime.ImeComposition;
import net.minecraftforge.client.ime.ImeForceKey;
import net.minecraftforge.client.ime.TextFieldHandler;
import net.minecraftforge.client.windows.DwmApi;
import net.minecraftforge.common.ForgeEarlyConfig;
import net.minecraftforge.fml.common.FMLLog;

import org.lwjgl.LWJGLException;
import org.lwjgl.MainThread;
import org.lwjgl.PointerBuffer;
import org.lwjgl.Sys;
import org.lwjgl.input.InputSnapshot;
import org.lwjgl.input.InputState;
import org.lwjgl.input.KeyCodes;
import org.lwjgl.input.KeyState;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL;
import org.lwjgl.sdl.SDL_DisplayMode;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.sdl.SDL_KeyboardEvent;
import org.lwjgl.sdl.SDL_MouseButtonEvent;
import org.lwjgl.sdl.SDL_MouseMotionEvent;
import org.lwjgl.sdl.SDL_MouseWheelEvent;
import org.lwjgl.sdl.SDL_Surface;
import org.lwjgl.sdl.SDL_Rect;
import org.lwjgl.sdl.SDL_TextInputEvent;
import org.lwjgl.sdl.SDL_TextEditingEvent;
import org.lwjgl.sdl.SDL_WindowEvent;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDLKeyboard;
import org.lwjgl.sdl.SDLPixels;
import org.lwjgl.sdl.SDLStdinc;
import org.lwjgl.sdl.SDLSurface;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.Rectangle;

/**
 * LWJGL2 Display, backed by SDL3.
 */
public class Display {

    private static String windowTitle = "Minecraft";

    private static boolean displayCreated = false;
    private static boolean displayFocused = true;
    private static boolean displayVisible = true;
    private static boolean displayDirty = false;
    private static boolean displayResizable = true;
    private static boolean displayCloseRequested = false;
    private static boolean borderlessInsteadOfFullscreen = false;

    /** Pixels the borderless window extends past the display; an exact match reads as exclusive fullscreen. */
    private static final int BORDERLESS_OVERSCAN = 1;

    private static DisplayMode mode = new DisplayMode(854, 480);
    private static DisplayMode desktopDisplayMode = new DisplayMode(854, 480);

    private static int displayX = 0;
    private static int displayY = 0;

    private static boolean displayResized = false;
    private static int displayWidth = 854;
    private static int displayHeight = 480;
    private static int displayFramebufferWidth = 854;
    private static int displayFramebufferHeight = 480;

    private static boolean latestResized = false;

    private static boolean lastAltIsRightAlt = false;
    private static ByteBuffer[] savedIcons;

    private static boolean hasPixelFormat = false;
    private static PixelFormat attachmentPixelFormat = null;
    private static boolean hasContextAttribs = false;
    private static ContextAttribs attachmentContextAttribs = null;
    private static boolean hasDrawable = false;
    private static Drawable attachmentDrawable = null;

    /**
     * Serialises context switches between the client thread and the splash render thread. SDL's GL
     * binding is process-global, so concurrent swaps would leave a thread with the wrong context.
     */
    public static final ReentrantLock glContextMutex = new ReentrantLock();

    /** SDL window handle, or NULL before {@link #create()}. */
    private static long sdlWindow = NULL;
    /** GL context bound to {@link #sdlWindow}. */
    private static long sdlGlContext = NULL;
    /** SDL window id, used to filter events addressed to other windows. */
    private static int sdlWindowId = 0;

    /** Scratch event used while draining the SDL queue. */
    private static final SDL_Event sdlEvent = SDL_Event.calloc();
    private static final SDL_WindowEvent sdlWindowEvent = sdlEvent.window();
    private static final SDL_KeyboardEvent sdlKeyEvent = sdlEvent.key();
    private static final SDL_TextInputEvent sdlTextEvent = sdlEvent.text();
    private static final SDL_TextEditingEvent sdlEditEvent = sdlEvent.edit();
    private static final SDL_MouseMotionEvent sdlMotionEvent = sdlEvent.motion();
    private static final SDL_MouseButtonEvent sdlButtonEvent = sdlEvent.button();
    private static final SDL_MouseWheelEvent sdlWheelEvent = sdlEvent.wheel();

    private static DrawableGL drawable = null;

    static {
        Sys.initialize();
        desktopDisplayMode = queryDesktopDisplayMode();
        mode = new DisplayMode(desktopDisplayMode.getWidth(), desktopDisplayMode.getHeight());
    }

    private static DisplayMode queryDesktopDisplayMode() {
        return MainThread.runOnMainThread(() -> {
            final int display = Sys.checkSdl(SDL_GetPrimaryDisplay());
            final SDL_DisplayMode sdlMode = Sys.checkSdl(SDL_GetDesktopDisplayMode(display));
            return new DisplayMode(
                sdlMode.w(),
                sdlMode.h(),
                Sys.checkSdl(SDLPixels.SDL_GetPixelFormatDetails(sdlMode.format()))
                    .bits_per_pixel(),
                Math.round(sdlMode.refresh_rate()));
        });
    }

    public static void setPixelFormat(PixelFormat pixel_format) {
        hasPixelFormat = true;
        attachmentPixelFormat = pixel_format;
    }

    public static void setPixelFormat(PixelFormat pixel_format, ContextAttribs attribs) {
        hasPixelFormat = true;
        attachmentPixelFormat = pixel_format;
        hasContextAttribs = attribs != null;
        attachmentContextAttribs = attribs;
    }

    public static PixelFormat getPixelFormat() {
        return attachmentPixelFormat;
    }

    public static void setAttribs(ContextAttribs attribs) {
        hasContextAttribs = attribs != null;
        attachmentContextAttribs = attribs;
    }

    public static void setDisplayModeAndFullscreen(DisplayMode newMode) {
        mode = newMode;
        setFullscreen(true);
    }

    public static void create(PixelFormat pixel_format, Drawable shared_drawable) {
        if (pixel_format != null) {
            setPixelFormat(pixel_format);
        }
        hasDrawable = shared_drawable != null;
        attachmentDrawable = shared_drawable;
        create();
    }

    public static void create(PixelFormat pixel_format, ContextAttribs attribs) {
        if (pixel_format != null) {
            setPixelFormat(pixel_format);
        }
        setAttribs(attribs);
        create();
    }

    public static void create(PixelFormat pixel_format) {
        if (pixel_format != null) {
            setPixelFormat(pixel_format);
        }
        create();
    }

    public static void create() {
        if (displayCreated) {
            return;
        }

        final int resolvedMajor = resolveContextMajor();
        final int resolvedMinor = resolveContextMinor();

        MainThread.runOnMainThread(() -> createWindow(resolvedMajor, resolvedMinor));

        Sys.checkSdl(SDLVideo.SDL_GL_MakeCurrent(sdlWindow, sdlGlContext));
        // OPENGL_EXPLICIT_INIT is set in Sys.initialize, so GL.create() has not run yet. It must be
        // driven here, once a context is current, or every GL call resolves to a null pointer.
        GL.create(SDLVideo::SDL_GL_GetProcAddress);
        drawable = new DrawableGL();
        try {
            drawable.makeCurrent();
        } catch (LWJGLException e) {
            throw new IllegalStateException("Failed to make the Display GL context current", e);
        }

        displayCreated = true;

        // Rebuild after the context is current: the table is cached in a static initializer, and
        // OpenGlHelper reads it right after create().
        GLContext.refreshCapabilities();

        if (pendingBorderless) {
            pendingBorderless = false;
            borderlessInsteadOfFullscreen = true;
            applyBorderlessGeometry();
            Sys.checkSdl(SDL_SyncWindow(sdlWindow));
        }

        // Ask for the foreground: SDL creates the window without stealing activation from whatever launched the
        // game, so without this the window can open behind the launcher.
        Sys.checkSdl(SDLVideo.SDL_RaiseWindow(sdlWindow));

        refreshWindowStyle();
    }

    /**
     * Re-applies the configured DWM window style to the current window.
     * <p>
     * Only Windows reads it, and only when the window exists: the attributes are written to the HWND that SDL
     * owns, and DWM silently drops them while the client area is still empty.
     */
    private static void refreshWindowStyle() {
        if (sdlWindow == NULL) {
            return;
        }
        DwmApi.updateDwm(borderlessInsteadOfFullscreen, sdlWindow);
    }

    /** Oversizes the window past the display; an exact match reads as exclusive fullscreen. */
    private static void applyBorderlessGeometry() {
        restoreFromMaximized();
        Sys.checkSdl(SDL_SetWindowBordered(sdlWindow, false));
        final Rectangle bounds = currentDisplayBounds();

        boolean left = false;
        boolean right = false;
        boolean up = false;
        boolean down = false;
        for (final Rectangle other : displayBounds()) {
            if (other.getX() == bounds.getX() && other.getY() == bounds.getY()) {
                continue;
            }
            left |= other.getX() < bounds.getX();
            right |= other.getX() > bounds.getX();
            up |= other.getY() < bounds.getY();
            down |= other.getY() > bounds.getY();
        }

        // Push the spare pixels into directions with no neighbour display, so none of them are visible.
        final int insetX = left ? 0 : (right ? -BORDERLESS_OVERSCAN * 2 : -BORDERLESS_OVERSCAN);
        final int insetY = up ? 0 : (down ? -BORDERLESS_OVERSCAN * 2 : -BORDERLESS_OVERSCAN);
        final int spareX = left && right ? 0 : BORDERLESS_OVERSCAN * 2;
        final int spareY = up && down ? 0 : BORDERLESS_OVERSCAN * 2;

        Sys.checkSdl(SDL_SetWindowPosition(sdlWindow, bounds.getX() + insetX, bounds.getY() + insetY));
        Sys.checkSdl(SDL_SetWindowSize(
            sdlWindow,
            bounds.getWidth() + spareX,
            bounds.getHeight() + spareY));
    }

    /**
     * Leaves the maximized state, if the window is in it.
     * <p>
     * A maximized window ignores {@code SDL_SetWindowSize}: the platform keeps it at the work area size, so entering
     * borderless fullscreen from a maximized window covers everything except the taskbar instead of the display.
     */
    private static void restoreFromMaximized() {
        if ((SDL_GetWindowFlags(sdlWindow) & SDLVideo.SDL_WINDOW_MAXIMIZED) != 0) {
            Sys.checkSdl(SDLVideo.SDL_RestoreWindow(sdlWindow));
        }
    }

    /** Desktop bounds of every connected display. */
    private static List<Rectangle> displayBounds() {
        final IntBuffer ids = Sys.checkSdl(SDL_GetDisplays());
        final List<Rectangle> result = new ArrayList<>(ids.remaining());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final SDL_Rect rect = SDL_Rect.malloc(stack);
            for (int i = 0; i < ids.remaining(); i++) {
                if (SDL_GetDisplayBounds(ids.get(i), rect)) {
                    result.add(new Rectangle(rect.x(), rect.y(), rect.w(), rect.h()));
                }
            }
        }
        return result;
    }
    private static int resolveContextMajor() {
        int major = ForgeEarlyConfig.openglVersionMajor;
        int minor = ForgeEarlyConfig.openglVersionMinor;
        if (hasContextAttribs) {
            return Math.max(2, attachmentContextAttribs.getMajorVersion());
        }
        if (major < 2) {
            return 2;
        }
        if (major > 4) {
            return 4;
        }
        return major == 1 ? 2 : major;
    }

    private static int resolveContextMinor() {
        if (hasContextAttribs) {
            final int major = Math.max(2, attachmentContextAttribs.getMajorVersion());
            return major < 3 ? (major == 2 ? 1 : 0) : attachmentContextAttribs.getMinorVersion();
        }
        final int major = resolveContextMajor();
        final int minor = ForgeEarlyConfig.openglVersionMinor;
        if (major == 2) {
            return 1;
        }
        if (minor < 0) {
            return 0;
        }
        return Math.min(minor, major == 3 ? 3 : 6);
    }

    private static void createWindow(int versionMajor, int versionMinor) {
        applyGlAttributes(versionMajor, versionMinor);

        final int props = SDL_CreateProperties();
        try {
            Sys.checkSdl(
                SDL_SetNumberProperty(
                    props,
                    SDLVideo.SDL_PROP_WINDOW_CREATE_X_NUMBER,
                    ForgeEarlyConfig.windowCentered
                        ? SDLVideo.SDL_WINDOWPOS_CENTERED
                        : SDLVideo.SDL_WINDOWPOS_UNDEFINED));
            Sys.checkSdl(
                SDL_SetNumberProperty(
                    props,
                    SDLVideo.SDL_PROP_WINDOW_CREATE_Y_NUMBER,
                    ForgeEarlyConfig.windowCentered
                        ? SDLVideo.SDL_WINDOWPOS_CENTERED
                        : SDLVideo.SDL_WINDOWPOS_UNDEFINED));
            Sys.checkSdl(SDL_SetNumberProperty(props, SDLVideo.SDL_PROP_WINDOW_CREATE_WIDTH_NUMBER, (long) mode.getWidth()));
            Sys.checkSdl(
                SDL_SetNumberProperty(props, SDLVideo.SDL_PROP_WINDOW_CREATE_HEIGHT_NUMBER, (long) mode.getHeight()));
            Sys.checkSdl(SDL_SetNumberProperty(props, SDLVideo.SDL_PROP_WINDOW_CREATE_FLAGS_NUMBER, (long) windowFlags()));
            Sys.checkSdl(SDL_SetStringProperty(props, SDLVideo.SDL_PROP_WINDOW_CREATE_TITLE_STRING, windowTitle));
            Sys.checkSdl(
                SDL_SetBooleanProperty(props, SDLVideo.SDL_PROP_WINDOW_CREATE_OPENGL_BOOLEAN, true));
            Sys.checkSdl(
                SDL_SetBooleanProperty(
                    props,
                    SDLVideo.SDL_PROP_WINDOW_CREATE_HIGH_PIXEL_DENSITY_BOOLEAN,
                    ForgeEarlyConfig.scaleFramebuffer));
            Sys.checkSdl(
                SDL_SetBooleanProperty(
                    props,
                    SDLVideo.SDL_PROP_WINDOW_CREATE_HIDDEN_BOOLEAN,
                    ForgeEarlyConfig.windowStartIconified));
            Sys.checkSdl(
                SDL_SetBooleanProperty(
                    props,
                    SDLVideo.SDL_PROP_WINDOW_CREATE_RESIZABLE_BOOLEAN,
                    true));
            Sys.checkSdl(
                SDL_SetBooleanProperty(
                    props,
                    SDLVideo.SDL_PROP_WINDOW_CREATE_BORDERLESS_BOOLEAN,
                    !ForgeEarlyConfig.windowDecorated));
            Sys.checkSdl(
                SDL_SetBooleanProperty(
                    props,
                    SDLVideo.SDL_PROP_WINDOW_CREATE_MAXIMIZED_BOOLEAN,
                    ForgeEarlyConfig.windowStartMaximized));

            sdlWindow = SDL_CreateWindowWithProperties(props);
            if (sdlWindow == NULL) {
                throw new IllegalStateException("Failed to create Display window: " + SDLError.SDL_GetError());
            }
        } finally {
            SDL_DestroyProperties(props);
        }

        sdlWindowId = SDLVideo.SDL_GetWindowID(sdlWindow);
        sdlGlContext = SDLVideo.SDL_GL_CreateContext(sdlWindow);
        if (sdlGlContext == NULL) {
            throw new IllegalStateException("Failed to create GL context: " + SDLError.SDL_GetError());
        }

        final long flags = SDL_GetWindowFlags(sdlWindow);
        displayFocused = (flags & SDL_WINDOW_INPUT_FOCUS) != 0;
        displayVisible = (flags & SDL_WINDOW_MINIMIZED) == 0;

        updateWindowDimensions();
        logWindowGeometry();
        if (savedIcons != null) {
            applySavedIcons();
        }
    }

    /**
     * Records the window/framebuffer geometry and the display scale the backend reports: on a high density desktop
     * the two sizes differ, and a mismatch here explains stretched or blurred rendering.
     */
    private static void logWindowGeometry() {
        FMLLog.log.info(
            "Window geometry: {}x{} logical, {}x{} pixels, pixel density {}, display scale {} (SDL video driver: {})",
            displayWidth,
            displayHeight,
            displayFramebufferWidth,
            displayFramebufferHeight,
            SDLVideo.SDL_GetWindowPixelDensity(sdlWindow),
            SDLVideo.SDL_GetWindowDisplayScale(sdlWindow),
            SDLVideo.SDL_GetCurrentVideoDriver());
    }

    private static int windowFlags() {
        long flags = SDL_WINDOW_OPENGL;
        if (!ForgeEarlyConfig.windowDecorated) {
            flags |= SDL_WINDOW_BORDERLESS;
        }
        if (ForgeEarlyConfig.windowStartIconified) {
            flags |= SDL_WINDOW_HIDDEN;
        }
        return (int) flags;
    }

    private static void applyGlAttributes(int versionMajor, int versionMinor) {
        SDLVideo.SDL_GL_ResetAttributes();

        int contextFlags = 0;
        if (hasContextAttribs) {
            if (attachmentContextAttribs.isForwardCompatible() && !attachmentContextAttribs.isProfileES()) {
                contextFlags |= SDLVideo.SDL_GL_CONTEXT_FORWARD_COMPATIBLE_FLAG;
            }
            if (attachmentContextAttribs.isDebug()) {
                contextFlags |= SDLVideo.SDL_GL_CONTEXT_DEBUG_FLAG;
            }
        } else if (ForgeEarlyConfig.openglDebugContext) {
            contextFlags |= SDLVideo.SDL_GL_CONTEXT_DEBUG_FLAG;
        }
        Sys.checkSdl(SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_FLAGS, contextFlags));
        Sys.checkSdl(SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MAJOR_VERSION, versionMajor));
        Sys.checkSdl(SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MINOR_VERSION, versionMinor));
        Sys.checkSdl(
            SDLVideo.SDL_GL_SetAttribute(
                SDLVideo.SDL_GL_FRAMEBUFFER_SRGB_CAPABLE,
                ForgeEarlyConfig.openglSrgbContext ? 1 : 0));
        Sys.checkSdl(
            SDLVideo.SDL_GL_SetAttribute(
                SDLVideo.SDL_GL_DOUBLEBUFFER,
                ForgeEarlyConfig.openglDoubleBuffer ? 1 : 0));
        Sys.checkSdl(
            SDLVideo.SDL_GL_SetAttribute(
                SDLVideo.SDL_GL_CONTEXT_NO_ERROR,
                ForgeEarlyConfig.openglContextNoError ? 1 : 0));
        Sys.checkSdl(SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_DEPTH_SIZE, 24));
        Sys.checkSdl(SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_STENCIL_SIZE, 8));
        // No alpha channel on the window surface. Minecraft clears with alpha 0, and two things go wrong when the
        // surface carries an alpha channel that a compositor honours: on Wayland the cleared window blends away
        // until only the opaque GUI pixels are left, and on Windows the DWM backdrop material would show through
        // the whole client area. The backdrop is a frame decoration here, not a window transparency effect.
        Sys.checkSdl(SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_RED_SIZE, 8));
        Sys.checkSdl(SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_GREEN_SIZE, 8));
        Sys.checkSdl(SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_BLUE_SIZE, 8));
        Sys.checkSdl(SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_ALPHA_SIZE, 0));

        if (hasContextAttribs) {
            if (attachmentContextAttribs.isProfileCore()) {
                Sys
                    .checkSdl(
                        SDLVideo.SDL_GL_SetAttribute(
                            SDLVideo.SDL_GL_CONTEXT_PROFILE_MASK,
                            SDLVideo.SDL_GL_CONTEXT_PROFILE_CORE));
            } else if (attachmentContextAttribs.isProfileES()) {
                Sys
                    .checkSdl(
                        SDLVideo.SDL_GL_SetAttribute(
                            SDLVideo.SDL_GL_CONTEXT_PROFILE_MASK,
                            SDLVideo.SDL_GL_CONTEXT_PROFILE_ES));
            } else {
                Sys
                    .checkSdl(
                        SDLVideo.SDL_GL_SetAttribute(
                            SDLVideo.SDL_GL_CONTEXT_PROFILE_MASK,
                            SDLVideo.SDL_GL_CONTEXT_PROFILE_COMPATIBILITY));
            }
        } else {
            Sys
                .checkSdl(
                    SDLVideo.SDL_GL_SetAttribute(
                        SDLVideo.SDL_GL_CONTEXT_PROFILE_MASK,
                        ForgeEarlyConfig.openglCompatProfile ? SDLVideo.SDL_GL_CONTEXT_PROFILE_COMPATIBILITY
                            : SDLVideo.SDL_GL_CONTEXT_PROFILE_CORE));
        }
    }

    private static void updateWindowDimensions() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final IntBuffer w = stack.ints(0);
            final IntBuffer h = stack.ints(0);
            SDL_GetWindowSize(sdlWindow, w, h);
            displayWidth = w.get(0);
            displayHeight = h.get(0);
            SDL_GetWindowSizeInPixels(sdlWindow, w, h);
            displayFramebufferWidth = w.get(0);
            displayFramebufferHeight = h.get(0);
            SDL_GetWindowPosition(sdlWindow, w, h);
            displayX = w.get(0);
            displayY = h.get(0);
        }
    }

    public static boolean isCreated() {
        return displayCreated;
    }

    public static boolean isActive() {
        return displayFocused;
    }

    public static boolean isVisible() {
        return displayVisible;
    }

    public static void setLocation(int new_x, int new_y) {
        displayX = new_x;
        displayY = new_y;
        if (displayCreated) {
            MainThread.runOnMainThread(() -> Sys.checkSdl(SDL_SetWindowPosition(sdlWindow, new_x, new_y)));
        }
    }

    public static void setVSyncEnabled(boolean sync) {
        setSwapInterval(sync ? 1 : 0);
    }

    /** @return the SDL window handle. */
    public static long getWindow() {
        return sdlWindow;
    }

    /** @return the SDL GL context bound to the Display window. */
    public static long getGlContext() {
        return sdlGlContext;
    }

    public static void update() {
        update(true);
    }

    public static void update(boolean processMessages) {
        swapBuffers();
        displayDirty = false;

        if (processMessages) {
            processMessages();
        }
    }

    public static void processMessages() {
        if (MainThread.isMainThread()) {
            // Queued tasks land here; must precede the window calls so they apply this frame.
            MainThread.drain();
            pumpSdlEvents();
        }
        Mouse.poll();
        Keyboard.poll();

        if (latestResized) {
            latestResized = false;
            displayResized = true;
        } else {
            displayResized = false;
        }
    }

    /**
     * Characters a key event in the current pump already carries. SDL reports a plain keystroke twice: as the
     * character of {@code SDL_EVENT_KEY_DOWN} and as {@code SDL_EVENT_TEXT_INPUT} with the same text. The key event
     * is what the field consumes (next frame), so the commit has to be stripped of those characters here, while a
     * real input method commit - which has no key event behind it - passes through untouched.
     */
    private static final StringBuilder pumpedKeyCharacters = new StringBuilder(8);

    private static void pumpSdlEvents() {
        pumpedKeyCharacters.setLength(0);
        SDLEvents.SDL_PumpEvents();
        while (SDLEvents.SDL_PollEvent(sdlEvent)) {
            handleSdlEvent();
        }
        // Refreshed after the queue drains so state queries match the events just delivered.
        Keyboard.sdlKeyPressedArray = SDLKeyboard.SDL_GetKeyboardState();
        Mouse.updateSdlButtonState();
    }

    /**
     * @return true when the event was consumed here.
     */
    private static boolean handleSdlEvent() {
        return switch (sdlEvent.type()) {
            case SDL_EVENT_QUIT -> {
                displayCloseRequested = true;
                yield true;
            }
            case SDL_EVENT_WINDOW_CLOSE_REQUESTED -> {
                // SDL only emits SDL_EVENT_QUIT when the last visible window closes; take the window
                // event too so a close request is not lost while other windows are alive.
                if (sdlWindowEvent.windowID() != sdlWindowId) {
                    yield true;
                }
                displayCloseRequested = true;
                yield true;
            }
            case SDL_EVENT_WINDOW_EXPOSED -> {
                if (sdlWindowEvent.windowID() != sdlWindowId) {
                    yield true;
                }
                displayDirty = true;
                yield true;
            }
            case SDL_EVENT_WINDOW_FOCUS_GAINED -> {
                if (sdlWindowEvent.windowID() != sdlWindowId) {
                    yield true;
                }
                displayFocused = true;
                // Both state arrays lag one pump; refresh them so the rebuilt snapshot is the state at focus time.
                Keyboard.sdlKeyPressedArray = SDLKeyboard.SDL_GetKeyboardState();
                Mouse.updateSdlButtonState();
                KeyBinding.updateKeyBindState();
                yield true;
            }
            case SDL_EVENT_WINDOW_FOCUS_LOST -> {
                if (sdlWindowEvent.windowID() != sdlWindowId) {
                    yield true;
                }
                displayFocused = false;
                InputState.clear();
                KeyBinding.unPressAllKeys();
                yield true;
            }
            case SDL_EVENT_WINDOW_MINIMIZED -> {
                if (sdlWindowEvent.windowID() != sdlWindowId) {
                    yield true;
                }
                displayVisible = false;
                yield true;
            }
            case SDL_EVENT_WINDOW_RESTORED -> {
                if (sdlWindowEvent.windowID() != sdlWindowId) {
                    yield true;
                }
                displayVisible = true;
                yield true;
            }
            case SDL_EVENT_WINDOW_RESIZED -> {
                if (sdlWindowEvent.windowID() != sdlWindowId) {
                    yield true;
                }
                displayWidth = sdlWindowEvent.data1();
                displayHeight = sdlWindowEvent.data2();
                yield true;
            }
            case SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED -> {
                if (sdlWindowEvent.windowID() != sdlWindowId) {
                    yield true;
                }
                displayFramebufferWidth = sdlWindowEvent.data1();
                displayFramebufferHeight = sdlWindowEvent.data2();
                latestResized = true;
                // The client area just got (or changed) its size, which is the first moment DWM can show the frame
                // material, and the attributes do not survive the window being re-created by a display change.
                if (displayWidth > 0 && displayHeight > 0) {
                    refreshWindowStyle();
                }
                yield true;
            }
            case SDL_EVENT_WINDOW_MOVED -> {
                if (sdlWindowEvent.windowID() != sdlWindowId) {
                    yield true;
                }
                displayX = sdlWindowEvent.data1();
                displayY = sdlWindowEvent.data2();
                yield true;
            }
            case SDL_EVENT_MOUSE_MOTION -> {
                Mouse.addMoveEvent(sdlMotionEvent);
                yield true;
            }
            case SDL_EVENT_MOUSE_BUTTON_DOWN, SDL_EVENT_MOUSE_BUTTON_UP -> {
                Mouse.addButtonEvent(Mouse.sdlToLwjglMouseButton(sdlButtonEvent.button()), sdlButtonEvent.down());
                yield true;
            }
            case SDL_EVENT_MOUSE_WHEEL -> {
                Mouse.addWheelEvent(sdlWheelEvent.y());
                yield true;
            }
            case SDL_EVENT_KEY_DOWN, SDL_EVENT_KEY_UP -> {
                handleKeyEvent();
                yield true;
            }
            case SDL_EVENT_TEXT_INPUT -> {
                handleTextEvent();
                yield true;
            }
            case SDL_EVENT_TEXT_EDITING -> {
                handleTextEditingEvent();
                yield true;
            }
            case SDL_EVENT_KEYMAP_CHANGED -> {
                Keyboard.populateKeyLookupTables();
                yield true;
            }
            default -> false;
        };
    }

    /**
     * Keys that let the game act on the text field while an input method is still composing. Tab switches fields or
     * completes player names, and both read the field's text; letting either run mid-composition loses what was typed.
     * Keys the input method needs for its own candidate selection never reach here as a plain press from the input
     * method's point of view, and the ones that do (Escape, Space, Enter) are left to it so it can finish first.
     */
    private static boolean consumesComposition(int lwjglKey, short mods) {
        if (lwjglKey != Keyboard.KEY_TAB) {
            return false;
        }
        // Shift+Tab walks fields backwards and is just as lossy; no modifier is acceptable here.
        return (mods & (org.lwjgl.sdl.SDLKeycode.SDL_KMOD_CTRL | org.lwjgl.sdl.SDLKeycode.SDL_KMOD_ALT
            | org.lwjgl.sdl.SDLKeycode.SDL_KMOD_GUI)) == 0;
    }

    private static void handleKeyEvent() {
        final boolean isDown = sdlKeyEvent.down();
        final boolean isRepeat = sdlKeyEvent.repeat();
        final short mods = sdlKeyEvent.mod();
        final int keycode = sdlKeyEvent.key();
        final int scancode = sdlKeyEvent.scancode();
        final KeyState state = isDown ? (isRepeat ? KeyState.REPEAT : KeyState.PRESS) : KeyState.RELEASE;

        // The layout's keycode, which is what LWJGL2 and every mod written against it mean by a key: on a non-QWERTY
        // layout the physical key and the character it types are different keys. The physical key travels with the
        // event for anyone who needs it; see KeyEvent.scancode.
        final int lwjglKey = KeyCodes.sdlKeycodeToLwjgl(keycode);
        final int lwjglScan = KeyCodes.sdlScancodeToLwjgl(scancode);
        InputState.update(lwjglKey, isDown);

        if (isDown && !isRepeat) {
            // The one combination the input method cannot have taken first: while SDL's text input session is
            // closed the operating system never sees this key, so a screen that draws its own text widget can be
            // made reachable with the same combination that switches input methods everywhere else.
            ImeForceKey.onKeyDown(lwjglKey, InputSnapshot.current());
        }

        if (isDown && TextFieldHandler.isComposing() && consumesComposition(lwjglKey, mods)) {
            // Vanilla reads the field when it acts on keys like Tab (chat completion rewrites the text around the
            // caret) or Enter, but the text the player is still picking candidates for is not in the field yet, so
            // acting on it sees half of what was typed. The composition is dropped instead of committed: SDL has no
            // call to commit on somebody's behalf, and an orphaned composition would be delivered to whichever field
            // is focused when the input method finally finishes.
            TextFieldHandler.discardComposition();
            return;
        }

        if (isDown) {
            if (keycode == org.lwjgl.sdl.SDLKeycode.SDLK_LALT) {
                lastAltIsRightAlt = false;
            } else if (keycode == org.lwjgl.sdl.SDLKeycode.SDLK_RALT) {
                lastAltIsRightAlt = true;
            }
        }

        if (isRepeat && !Keyboard.areRepeatEventsEnabled()) {
            return;
        }

        final int rawKeycode = SDLKeyboard.SDL_GetKeyFromScancode(scancode, (short) 0, false);
        final char character = rawKeycode >= 32 && rawKeycode < 0xFFFF ? (char) rawKeycode : '\0';

        if (rawKeycode >= org.lwjgl.sdl.SDLKeycode.SDLK_SPACE
            && rawKeycode <= org.lwjgl.sdl.SDLKeycode.SDLK_TILDE) {
            // AltGr sends ALT|CTRL, so disambiguate text input from combos by the last pressed Alt side.
            final boolean isAlt = (mods & org.lwjgl.sdl.SDLKeycode.SDL_KMOD_ALT) != 0;
            final boolean isAltGr = lastAltIsRightAlt;
            final boolean ctrlGraphicalMode;
            if (isAlt) {
                if (isAltGr) {
                    ctrlGraphicalMode = !ForgeEarlyConfig.inputAltGrEscapeCodes;
                } else {
                    ctrlGraphicalMode = ForgeEarlyConfig.inputCtrlAltText;
                }
                if (ctrlGraphicalMode) {
                    Keyboard.addSdlKeyEvent(lwjglKey, (char) (keycode & 0x1f), state, lwjglScan, mods);
                }
            } else {
                ctrlGraphicalMode = false;
            }

            if ((mods & org.lwjgl.sdl.SDLKeycode.SDL_KMOD_GUI) != 0) {
                queueKeyEvent(lwjglKey, character, state, isDown, lwjglScan, mods);
            } else if ((mods & org.lwjgl.sdl.SDLKeycode.SDL_KMOD_CTRL) != 0 && !ctrlGraphicalMode) {
                queueKeyEvent(lwjglKey, (char) (keycode & 0x1f), state, isDown, lwjglScan, mods);
            } else {
                queueKeyEvent(lwjglKey, character, state, isDown, lwjglScan, mods);
            }
        } else {
            // Other key with no char event associated
            queueKeyEvent(lwjglKey, character, state, isDown, lwjglScan, mods);
        }
    }

    /** Queues one key event and remembers the character a press contributed to this pump's text. */
    private static void queueKeyEvent(int key, char character, KeyState state, boolean isDown, int scancode, short mods) {
        Keyboard.addSdlKeyEvent(key, character, state, scancode, mods);
        if (isDown && character >= ' ' && pumpedKeyCharacters.length() < 32) {
            pumpedKeyCharacters.append(character);
        }
    }

    private static void handleTextEvent() {
        final String text = sdlTextEvent.textString();
        if (text == null || text.isEmpty()) {
            return;
        }
        if (FMLLog.log.isDebugEnabled()) {
            FMLLog.log.debug("TEXT_INPUT [{}]", text);
        }
        // Handed straight to the focused field, minus the characters this pump already queued as key events, so a
        // plain keystroke is typed exactly once and an input method commit - which has no key event - arrives whole.
        String commit = text;
        while (commit.length() > 0) {
            final int at = pumpedKeyCharacters.indexOf(String.valueOf(commit.charAt(0)));
            if (at < 0) {
                break;
            }
            pumpedKeyCharacters.deleteCharAt(at);
            commit = commit.substring(1);
        }
        if (!commit.isEmpty()) {
            TextFieldHandler.onTextInput(commit);
        }
    }

    private static void handleTextEditingEvent() {
        // Composition is not committed text: record it for the field to draw, do not queue it as input.
        ImeComposition.update(sdlEditEvent.textString(), sdlEditEvent.start());
    }

    public static void swapBuffers() {
        if (!displayCreated) {
            return;
        }
        // Runs on the caller thread: the splash renderer swaps its own frames and would deadlock waiting
        // for the client thread to drain a queued swap.
        Sys.checkSdl(SDLVideo.SDL_GL_SwapWindow(sdlWindow));
    }

    public static void destroy() {
        if (drawable != null) {
            drawable.destroy();
            drawable = null;
        }
        // create() installs the function provider before the display counts as created, so releasing it must not
        // depend on displayCreated: a create that fails right after GL.create() would otherwise leave the provider
        // loaded and make every retry throw "OpenGL library has already been loaded" from GL.create().
        // GL.destroy() is a no-op when no provider was installed.
        GL.setCapabilities(null);
        GL.destroy();
        MainThread.runOnMainThread(() -> {
            if (sdlGlContext != NULL) {
                SDLVideo.SDL_GL_DestroyContext(sdlGlContext);
                sdlGlContext = NULL;
            }
            if (sdlWindow != NULL) {
                SDL_DestroyWindow(sdlWindow);
                sdlWindow = NULL;
                sdlWindowId = 0;
            }
        });
        displayCreated = false;
    }

    public static void setDisplayMode(DisplayMode dm) {
        mode = dm;
        if (displayCreated) {
            MainThread.runOnMainThread(() -> Sys.checkSdl(SDL_SetWindowSize(sdlWindow, dm.getWidth(), dm.getHeight())));
        }
    }

    public static DisplayMode getDisplayMode() {
        return mode;
    }

    public static DisplayMode[] getAvailableDisplayModes() {
        return MainThread.runOnMainThread(() -> {
            final int display = Sys.checkSdl(SDL_GetPrimaryDisplay());
            final PointerBuffer modes = Sys.checkSdl(SDL_GetFullscreenDisplayModes(display));
            final DisplayMode[] result = new DisplayMode[modes.remaining()];
            for (int i = 0; i < result.length; i++) {
                final SDL_DisplayMode reader = new SDL_DisplayMode(modes.getByteBuffer(i, SDL_DisplayMode.SIZEOF));
                result[i] = new DisplayMode(
                    reader.w(),
                    reader.h(),
                    Sys.checkSdl(SDLPixels.SDL_GetPixelFormatDetails(reader.format()))
                        .bits_per_pixel(),
                    Math.round(reader.refresh_rate()));
            }
            // SDL allocated this array itself, so it must go back through SDL_free; PointerBuffer.free()
            // would hand it to LWJGL's allocator and corrupt the heap.
            SDLStdinc.SDL_free(modes);
            return result;
        });
    }

    public static DisplayMode getDesktopDisplayMode() {
        return desktopDisplayMode;
    }

    public static boolean wasResized() {
        return displayResized;
    }

    public static int getX() {
        return displayX;
    }

    public static int getY() {
        return displayY;
    }

    /** Vanilla and Forge both treat this as the framebuffer width and pass it to glViewport. */
    public static int getWidth() {
        return displayFramebufferWidth;
    }

    public static int getHeight() {
        return displayFramebufferHeight;
    }

    public static int getFramebufferWidth() {
        return displayFramebufferWidth;
    }

    public static int getFramebufferHeight() {
        return displayFramebufferHeight;
    }

    public static String getTitle() {
        return windowTitle;
    }

    public static float getPixelScaleFactor() {
        if (!displayCreated) {
            return 1.0f;
        }
        return (float) MainThread.runOnMainThread((DoubleSupplier) () -> SDLVideo.SDL_GetWindowPixelDensity(sdlWindow));
    }

    public static void setTitle(String title) {
        // LWJGL2 maps null to an empty title rather than forwarding it to the backend.
        final String newTitle = title == null ? "" : title;
        windowTitle = newTitle;
        if (displayCreated) {
            MainThread.runOnMainThread(() -> Sys.checkSdl(SDL_SetWindowTitle(sdlWindow, newTitle)));
        }
    }

    public static boolean isCloseRequested() {
        final boolean saved = displayCloseRequested;
        displayCloseRequested = false;
        return saved;
    }

    public static boolean isDirty() {
        return displayDirty;
    }

    public static void setInitialBackground(float red, float green, float blue) {
        // no-op
    }

    public static int setIcon(ByteBuffer[] icons) {
        if (icons == null || icons.length == 0) {
            return 0;
        }
        // Copy first: callers may reuse or release their buffers once this returns.
        savedIcons = new ByteBuffer[icons.length];
        for (int i = 0; i < icons.length; i++) {
            final ByteBuffer icon = icons[i];
            savedIcons[i] = ByteBuffer.allocate(icon.remaining())
                .order(ByteOrder.nativeOrder());
            final int oldPos = icon.position();
            savedIcons[i].put(icon);
            icon.position(oldPos);
            savedIcons[i].flip();
        }
        // Test the handle rather than displayCreated: the window exists once createWindow runs, while that
        // flag is only set later by create().
        if (sdlWindow == 0L) {
            // LWJGL2 reports 0 for an uncreated Display and replays the cached icons from create().
            return 0;
        }
        applySavedIcons();
        return icons.length;
    }

    /** Uploads {@link #savedIcons} as the window icon. Main-thread only. */
    private static void applySavedIcons() {
        final ByteBuffer[] sortedIcons = Arrays.copyOf(savedIcons, savedIcons.length);
        Arrays.sort(sortedIcons, Comparator.comparingInt(bb -> -bb.remaining()));
        // SDL_SetWindowIcon is main-thread only, so the surfaces live entirely inside the marshalled task.
        MainThread.runOnMainThread(() -> {
            SDL_Surface iconSet = null;
            for (final ByteBuffer iconRgba : sortedIcons) {
                final int dimension = (int) Math.sqrt(iconRgba.remaining() / 4D);
                if (dimension * dimension * 4 != iconRgba.remaining()) {
                    throw new IllegalStateException(
                        "Could not determine icon size from a buffer length of " + iconRgba.remaining());
                }
                final SDL_Surface surface = Sys
                    .checkSdl(SDLSurface.SDL_CreateSurface(dimension, dimension, SDLPixels.SDL_PIXELFORMAT_RGBA32));
                Sys.checkSdl(SDLSurface.SDL_LockSurface(surface));
                final ByteBuffer pixels = Objects.requireNonNull(surface.pixels());
                final int oldPos = iconRgba.position();
                pixels.put(iconRgba);
                iconRgba.position(oldPos);
                SDLSurface.SDL_UnlockSurface(surface);
                if (iconSet == null) {
                    iconSet = surface;
                } else {
                    Sys.checkSdl(SDLSurface.SDL_AddSurfaceAlternateImage(iconSet, surface));
                }
            }
            if (!SDL_SetWindowIcon(sdlWindow, iconSet)) {
                FMLLog.log.debug(
                    "Could not set a window icon with SDL video driver '{}' ({}), continuing without one",
                    SDLVideo.SDL_GetCurrentVideoDriver(),
                    SDLError.SDL_GetError());
            }
            SDLSurface.SDL_DestroySurface(iconSet);
        });
    }

    public static void setResizable(boolean resizable) {
        displayResizable = resizable;
        if (displayCreated) {
            MainThread.runOnMainThread(() -> Sys.checkSdl(SDL_SetWindowResizable(sdlWindow, resizable)));
        }
    }

    public static boolean isResizable() {
        return displayResizable;
    }

    public static PositionedDisplayMode getTargetFullscreenMonitor() {
        return MainThread.runOnMainThread(() -> {
            final int display = displayCreated && sdlWindow != NULL ? SDLVideo.SDL_GetDisplayForWindow(sdlWindow)
                : SDL_GetPrimaryDisplay();
            return describeDisplay(Sys.checkSdl(display));
        });
    }

    private static PositionedDisplayMode describeDisplay(int display) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final SDL_Rect bounds = SDL_Rect.malloc(stack);
            Sys.checkSdl(SDLVideo.SDL_GetDisplayBounds(display, bounds));
            final SDL_DisplayMode sdlMode = Sys.checkSdl(SDL_GetDesktopDisplayMode(display));
            return new PositionedDisplayMode(
                bounds.x(),
                bounds.y(),
                new Rectangle(bounds.x(), bounds.y(), bounds.w(), bounds.h()),
                display,
                new DisplayMode(
                    sdlMode.w(),
                    sdlMode.h(),
                    Sys.checkSdl(SDLPixels.SDL_GetPixelFormatDetails(sdlMode.format()))
                        .bits_per_pixel(),
                    Math.round(sdlMode.refresh_rate())));
        }
    }

    /** A display with its desktop-relative bounds. */
    public record PositionedDisplayMode(int x, int y, Rectangle bounds, long displayId, DisplayMode mode) {}

    /** Set when {@link #setFullscreen} runs before the window exists; applied once create() finishes. */
    private static boolean pendingBorderless = false;

    public static void setFullscreen(boolean fullscreen) {
        if (!displayCreated) {
            pendingBorderless = fullscreen;
            return;
        }
        setBorderless(fullscreen);
    }

    public static void toggleBorderless() {
        setBorderless(!isBorderlessActive());
    }

    public static void setBorderless(boolean toBorderless) {
        borderlessInsteadOfFullscreen = toBorderless;
        if (!displayCreated) {
            return;
        }
        MainThread.runOnMainThread(() -> {
            if (toBorderless) {
                if ((SDL_GetWindowFlags(sdlWindow) & SDLVideo.SDL_WINDOW_FULLSCREEN) != 0) {
                    Sys.checkSdl(SDL_SetWindowFullscreen(sdlWindow, false));
                }
                applyBorderlessGeometry();
            } else {
                restoreFromMaximized();
                Sys.checkSdl(SDL_SetWindowBordered(sdlWindow, true));
                Sys.checkSdl(SDL_SetWindowSize(sdlWindow, mode.getWidth(), mode.getHeight()));
                Sys.checkSdl(SDL_SetWindowPosition(
                    sdlWindow,
                    SDL_WINDOWPOS_CENTERED,
                    SDL_WINDOWPOS_CENTERED));
            }
            Sys.checkSdl(SDL_SyncWindow(sdlWindow));
            updateWindowDimensions();
        });
    }

    private static Rectangle currentDisplayBounds() {
        // Both getters return a display id, not a success flag, so neither may go through checkSdl.
        int display = SDL_GetDisplayForWindow(sdlWindow);
        if (display == 0) {
            display = SDL_GetPrimaryDisplay();
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final SDL_Rect rect = SDL_Rect.malloc(stack);
            Sys.checkSdl(SDL_GetDisplayBounds(display, rect));
            return new Rectangle(rect.x(), rect.y(), rect.w(), rect.h());
        }
    }

    public static boolean isBorderless() {
        return borderlessInsteadOfFullscreen;
    }

    private static boolean isBorderlessActive() {
        return displayCreated
            && (SDL_GetWindowFlags(sdlWindow) & SDLVideo.SDL_WINDOW_BORDERLESS) != 0
            && (SDL_GetWindowFlags(sdlWindow) & SDLVideo.SDL_WINDOW_FULLSCREEN) == 0;
    }

    public static boolean isFullscreen() {
        return displayCreated
            && (SDL_GetWindowFlags(sdlWindow) & SDLVideo.SDL_WINDOW_FULLSCREEN) != 0;
    }

    public static void setParent(java.awt.Canvas parent) {
        // Not supported by the SDL backend.
    }

    public static java.awt.Canvas getParent() {
        return null;
    }

    public static void setSwapInterval(int value) {
        if (!displayCreated) {
            return;
        }
        MainThread.runOnMainThread(() -> Sys.checkSdl(SDLVideo.SDL_GL_SetSwapInterval(value)));
    }

    public static void setDisplayConfiguration(float gamma, float brightness, float contrast) {
        // ignore
    }

    public static void releaseContext() {
        if (!displayCreated) {
            return;
        }
        MainThread.runOnMainThread(() -> Sys.checkSdl(SDLVideo.SDL_GL_MakeCurrent(sdlWindow, NULL)));
    }

    public static boolean isCurrent() {
        return displayCreated && SDLVideo.SDL_GL_GetCurrentContext() == sdlGlContext;
    }

    public static void makeCurrent() {
        if (!displayCreated) {
            return;
        }
        MainThread.runOnMainThread(() -> Sys.checkSdl(SDLVideo.SDL_GL_MakeCurrent(sdlWindow, sdlGlContext)));
    }

    public static String getAdapter() {
        if (isCreated()) {
            return GL11.glGetString(GL11.GL_VENDOR);
        }
        return "Unknown";
    }

    public static String getVersion() {
        if (isCreated()) {
            return GL11.glGetString(GL11.GL_VERSION);
        }
        return "Unknown";
    }

    /**
     * An accurate sync method that will attempt to run at a constant frame rate. It should be called once every frame.
     *
     * @param fps - the desired frame rate, in frames per second
     */
    public static void sync(int fps) {
        Sync.sync(fps);
    }

    public static Drawable getDrawable() {
        return drawable;
    }

    static DisplayImplementation getImplementation() {
        return null;
    }

    /** Present for API compatibility; SDL routes input through the event pump instead. */
    public static void setCallbacks() {}

    /** Present for API compatibility; SDL routes input through the event pump instead. */
    public static void releaseCallbacks() {}
}
