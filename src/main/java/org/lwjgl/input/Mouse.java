package org.lwjgl.input;

import static org.lwjgl.sdl.SDLMouse.SDL_BUTTON_LEFT;
import static org.lwjgl.sdl.SDLMouse.SDL_BUTTON_MIDDLE;
import static org.lwjgl.sdl.SDLMouse.SDL_BUTTON_RIGHT;
import static org.lwjgl.sdl.SDLMouse.SDL_BUTTON_X1;
import static org.lwjgl.sdl.SDLMouse.SDL_BUTTON_X2;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import net.minecraftforge.common.ForgeEarlyConfig;

import org.apache.commons.lang3.StringUtils;
import org.lwjgl.BufferUtils;
import org.lwjgl.MainThread;
import org.lwjgl.sdl.SDL_MouseMotionEvent;
import org.lwjgl.sdl.SDLMouse;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.LWJGLException;
import org.lwjgl.Sys;
import org.lwjgl.opengl.Display;

public class Mouse {

    // Fields for reflection compatibility with lwjgl2
    public static final int EVENT_SIZE = 1 + 1 + 4 + 4 + 4 + 8;
    private static ByteBuffer buttons = BufferUtils.createByteBuffer(32);
    private static IntBuffer coord_buffer = BufferUtils.createIntBuffer(32);
    private static ByteBuffer readBuffer = BufferUtils.createByteBuffer(32);

    private static boolean grabbed = false;

    private static int lastEventX = 0;
    private static int lastEventY = 0;

    private static int latestX = 0;
    private static int latestY = 0;

    private static int x = 0;
    private static int y = 0;

    private static int dx = 0, dy = 0, dwheel = 0;

    private static EventQueue queue = new EventQueue(512);

    private static int[] buttonEvents = new int[queue.getMaxEvents()];
    private static boolean[] buttonEventStates = new boolean[queue.getMaxEvents()];
    private static int[] xEvents = new int[queue.getMaxEvents()];
    private static int[] yEvents = new int[queue.getMaxEvents()];
    private static int[] wheelEvents = new int[queue.getMaxEvents()];
    private static int[] lastxEvents = new int[queue.getMaxEvents()];
    private static int[] lastyEvents = new int[queue.getMaxEvents()];
    private static long[] nanoTimeEvents = new long[queue.getMaxEvents()];

    private static boolean clipPostionToDisplay = true;
    private static int ignoreNextDelta = 0;
    private static int ignoreNextMove = 0;
    
    private static Cursor currentCursor = null;

    /** Lazily created default cursor, used when {@link #setNativeCursor} is called with null. */
    private static long systemCursor = 0L;

    public static void addMoveEvent(double mouseX, double mouseY) {
        if (ignoreNextMove > 0) {
            ignoreNextMove--;
            return;
        }
        float scale = Display.getPixelScaleFactor();
        mouseX *= scale;
        mouseY *= scale;
        dx += (int) mouseX - latestX;
        dy += Display.getHeight() - (int) mouseY - latestY;
        latestX = (int) mouseX;
        latestY = Display.getHeight() - (int) mouseY;
        if (ignoreNextDelta > 0) {
            ignoreNextDelta--;
            x = latestX;
            y = latestY;
            lastEventX = latestX;
            lastEventY = latestY;
            dx = 0;
            dy = 0;
        }

        lastxEvents[queue.getNextPos()] = lastEventX;
        lastyEvents[queue.getNextPos()] = lastEventY;
        lastEventX = latestX;
        lastEventY = latestY;

        xEvents[queue.getNextPos()] = latestX;
        yEvents[queue.getNextPos()] = latestY;

        wheelEvents[queue.getNextPos()] = 0;

        buttonEvents[queue.getNextPos()] = -1;
        buttonEventStates[queue.getNextPos()] = false;

        nanoTimeEvents[queue.getNextPos()] = Sys.getNanoTime();

        queue.add();
    }

    /** Accumulates sub-pixel deltas so slow mouse motion is not rounded away. */
    private static double dxAccum = 0.0, dyAccum = 0.0;

    /**
     * Records a motion event. SDL reports relative deltas, which stay correct under relative mouse mode
     * where the absolute position does not move.
     *
     * @param event the SDL motion event
     */
    public static void addMoveEvent(SDL_MouseMotionEvent event) {
        if (ignoreNextMove > 0) {
            ignoreNextMove--;
            return;
        }
        final double scale = Display.getPixelScaleFactor();
        final double mouseX = event.x() * scale;
        final double mouseY = event.y() * scale;

        dxAccum += event.xrel() * scale;
        dyAccum -= event.yrel() * scale;
        final int wholeDx = (int) Math.round(dxAccum);
        final int wholeDy = (int) Math.round(dyAccum);
        dxAccum -= wholeDx;
        dyAccum -= wholeDy;
        dx += wholeDx;
        dy += wholeDy;

        latestX = (int) mouseX;
        latestY = Display.getHeight() - (int) mouseY;
        if (ignoreNextDelta > 0) {
            ignoreNextDelta--;
            x = latestX;
            y = latestY;
            lastEventX = latestX;
            lastEventY = latestY;
            dx = 0;
            dy = 0;
            dxAccum = 0;
            dyAccum = 0;
        }

        lastxEvents[queue.getNextPos()] = lastEventX;
        lastyEvents[queue.getNextPos()] = lastEventY;
        lastEventX = latestX;
        lastEventY = latestY;

        xEvents[queue.getNextPos()] = latestX;
        yEvents[queue.getNextPos()] = latestY;

        wheelEvents[queue.getNextPos()] = 0;

        buttonEvents[queue.getNextPos()] = -1;
        buttonEventStates[queue.getNextPos()] = false;

        nanoTimeEvents[queue.getNextPos()] = Sys.getNanoTime();

        queue.add();
    }

    /**
     * Maps an SDL mouse button index to the LWJGL2 numbering.
     *
     * @param sdlMouseButton the SDL button
     * @return the LWJGL2 button index
     */
    public static int sdlToLwjglMouseButton(byte sdlMouseButton) {
        return switch (sdlMouseButton) {
            case SDL_BUTTON_LEFT -> 0;
            case SDL_BUTTON_RIGHT -> 1;
            case SDL_BUTTON_MIDDLE -> 2;
            case SDL_BUTTON_X1 -> 3;
            case SDL_BUTTON_X2 -> 4;
            default -> -1;
        };
    }

    public static void addButtonEvent(int button, boolean pressed) {
        lastxEvents[queue.getNextPos()] = lastEventX;
        lastyEvents[queue.getNextPos()] = lastEventY;
        lastEventX = latestX;
        lastEventY = latestY;

        xEvents[queue.getNextPos()] = latestX;
        yEvents[queue.getNextPos()] = latestY;

        wheelEvents[queue.getNextPos()] = 0;

        buttonEvents[queue.getNextPos()] = button;
        buttonEventStates[queue.getNextPos()] = pressed;

        nanoTimeEvents[queue.getNextPos()] = Sys.getNanoTime();

        queue.add();
    }

    static double fractionalWheelPosition = 0.0;
    // Used for our config screen for ease of access
    public static double totalScrollAmount = 0.0;

    public static void addWheelEvent(double delta) {
        if (ForgeEarlyConfig.INPUT_INVERT_WHEEL) {
            delta = -delta;
        }
        delta *= ForgeEarlyConfig.INPUT_SCROLL_SPEED;

        final int lastWheel = (int) fractionalWheelPosition;
        fractionalWheelPosition += delta;
        totalScrollAmount += delta;
        final int newWheel = (int) fractionalWheelPosition;
        if (newWheel != lastWheel) {
            lastxEvents[queue.getNextPos()] = lastEventX;
            lastyEvents[queue.getNextPos()] = lastEventY;

            lastEventX = latestX;
            lastEventY = latestY;

            dwheel += newWheel - lastWheel;

            xEvents[queue.getNextPos()] = latestX;
            yEvents[queue.getNextPos()] = latestY;

            wheelEvents[queue.getNextPos()] = newWheel - lastWheel;

            buttonEvents[queue.getNextPos()] = -1;
            buttonEventStates[queue.getNextPos()] = false;

            nanoTimeEvents[queue.getNextPos()] = Sys.getNanoTime();

            queue.add();
        }
        fractionalWheelPosition = fractionalWheelPosition % 1;
    }

    public static void poll() {
        if (!grabbed && clipPostionToDisplay) {
            if (latestX < 0) latestX = 0;
            if (latestY < 0) latestY = 0;
            if (latestX > Display.getWidth() - 1) latestX = Display.getWidth() - 1;
            if (latestY > Display.getHeight() - 1) latestY = Display.getHeight() - 1;
        }

        x = latestX;
        y = latestY;
    }

    public static void create() throws LWJGLException {}

    public static boolean isCreated() {
        return Display.isCreated();
    }

    public static void setGrabbed(boolean grab) {
        if (grabbed == grab) {
            return;
        }
        MainThread.runOnMainThread(() -> {
            Sys.checkSdl(SDLMouse.SDL_SetWindowRelativeMouseMode(Display.getWindow(), grab));
            if (!grab) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    final IntBuffer w = stack.ints(0);
                    final IntBuffer h = stack.ints(0);
                    Sys.checkSdl(SDLVideo.SDL_GetWindowSize(Display.getWindow(), w, h));
                    SDLMouse.SDL_WarpMouseInWindow(Display.getWindow(), w.get(0) / 2.0f, h.get(0) / 2.0f);
                }
            }
        });
        grabbed = grab;
        if (!grab) {
            // The old cursor position is sent instead of the new one in the events following mouse ungrab.
            ignoreNextMove += 2;
            setCursorPosition(Display.getWidth() / 2, Display.getHeight() / 2);
            // Movement events are not properly sent when toggling mouse grab mode.
            // Trick the game into getting the correct mouse position if no new events appear.
            latestX = Display.getWidth() / 2;
            latestY = Display.getHeight() / 2;
            lastEventX = latestX;
            lastEventY = latestY;
            x = latestX;
            y = latestY;

            xEvents[queue.getNextPos()] = latestX;
            yEvents[queue.getNextPos()] = latestY;
            lastxEvents[queue.getNextPos()] = latestX;
            lastyEvents[queue.getNextPos()] = latestY;
            wheelEvents[queue.getNextPos()] = 0;
            buttonEvents[queue.getNextPos()] = -1;
            buttonEventStates[queue.getNextPos()] = false;
            nanoTimeEvents[queue.getNextPos()] = Sys.getNanoTime();
            queue.add();
        } else {
            ignoreNextDelta++; // Prevent camera rapidly rotating when closing GUIs.
            dx = 0;
            dy = 0;
            dxAccum = 0;
            dyAccum = 0;
        }
    }

    public static boolean isGrabbed() {
        return grabbed;
    }

    public static boolean isButtonDown(int button) {
        return (sdlButtonState & sdlButtonMask(button)) != 0;
    }

    /** Updated once per pump so button state queries do not need a window handle. */
    public static int sdlButtonState = 0;

    private static int sdlButtonMask(int button) {
        return button >= 0 && button < 5 ? 1 << button : 0;
    }

    /** Re-reads the SDL button state; called once per event pump. */
    public static void updateSdlButtonState() {
        sdlButtonState = 0;
        final int state = SDLMouse.nSDL_GetMouseState(0L, 0L);
        for (int i = 0; i < 5; i++) {
            if ((state & (1 << i)) != 0) {
                sdlButtonState |= 1 << i;
            }
        }
    }

    public static boolean next() {
        return queue.next();
    }

    public static int getEventX() {
        return xEvents[queue.getCurrentPos()];
    }

    public static int getEventY() {
        return yEvents[queue.getCurrentPos()];
    }

    public static int getEventDX() {
        return xEvents[queue.getCurrentPos()] - lastxEvents[queue.getCurrentPos()];
    }

    public static int getEventDY() {
        return yEvents[queue.getCurrentPos()] - lastyEvents[queue.getCurrentPos()];
    }

    public static long getEventNanoseconds() {
        return nanoTimeEvents[queue.getCurrentPos()];
    }

    public static int getEventButton() {
        return buttonEvents[queue.getCurrentPos()];
    }

    public static boolean getEventButtonState() {
        return buttonEventStates[queue.getCurrentPos()];
    }

    public static int getEventDWheel() {
        return wheelEvents[queue.getCurrentPos()];
    }

    public static int getX() {
        return x;
    }

    public static int getY() {
        return y;
    }

    public static int getDX() {
        int value = dx;
        dx = 0;
        return value;
    }

    public static int getDY() {
        int value = dy;
        dy = 0;
        return value;
    }

    public static int getDWheel() {
        int value = dwheel;
        dwheel = 0;
        return value;
    }

    public static int getButtonCount() {
        return 8;
    }

    public static void setClipMouseCoordinatesToWindow(boolean clip) {
        clipPostionToDisplay = clip;
    }

    public static void setCursorPosition(int new_x, int new_y) {
        if (grabbed) {
            return;
        }
        // convert back from framebuffer coordinates to screen-space coordinates
        final float inv_scale = 1.0f / Display.getPixelScaleFactor();
        final int screenX = (int) (new_x * inv_scale);
        final int screenY = (int) (new_y * inv_scale);
        MainThread.runOnMainThread(
            () -> SDLMouse.SDL_WarpMouseInWindow(Display.getWindow(), screenX, screenY));
        // this might lose accuracy, since we just went from fb->screen and this will
        // undo that change. Yay floating point numbers!
        addMoveEvent(screenX, screenY);
    }

    public static Cursor setNativeCursor(Cursor cursor) throws LWJGLException {
        final Cursor prevCursor = currentCursor;
        currentCursor = cursor;
        if (!Display.isCreated()) {
            return prevCursor;
        }

        if (cursor == null) {
            // SDL_SetCursor(null) only redraws the current cursor; restoring the system default needs a
            // real handle, which is created once and reused.
            MainThread.runOnMainThread(() -> {
                if (systemCursor == 0L) {
                    systemCursor = Sys.checkSdl(SDLMouse.SDL_CreateSystemCursor(SDLMouse.SDL_SYSTEM_CURSOR_DEFAULT));
                }
                Sys.checkSdl(SDLMouse.SDL_SetCursor(systemCursor));
            });
        } else {
            final long handle = cursor.getNativeCursor();
            MainThread.runOnMainThread(() -> Sys.checkSdl(SDLMouse.SDL_SetCursor(handle)));
        }

        return prevCursor;
    }

    public static void destroy() {}

    public static int getButtonIndex(String buttonName) {
        if (buttonName.matches("BUTTON[0-9]+")) {
            return Integer.parseInt(StringUtils.removeStart(buttonName, "BUTTON"));
        } else {
            return -1;
        }
    }

    public static String getButtonName(int button) {
        return "BUTTON" + button;
    }

    public static Cursor getNativeCursor() {
        return currentCursor;
    }

    public static boolean hasWheel() {
        return true;
    }

    public static boolean isClipMouseCoordinatesToWindow() {
        return clipPostionToDisplay;
    }

    public static boolean isInsideWindow() {
        return Display.isVisible();
    }

    public static void updateCursor() {
        // no-op
    }
}
