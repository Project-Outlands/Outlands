package com.cleanroommc.client;

import org.lwjgl.MainThread;
import org.lwjgl.Sys;
import org.lwjgl.opengl.Display;
import org.lwjgl.sdl.SDLVideo;

/**
 * Drives the taskbar loading indicator through SDL's progress API.
 * <p>
 * This replaces the direct Win32 ITaskbarList3 calls: SDL maps the same state onto each desktop's
 * native indicator (Windows taskbar, Unity launcher, and others), so no HWND is needed.
 */
public final class TaskbarProgress {

    private TaskbarProgress() {}

    /** Shows an indeterminate indicator, for phases without a meaningful ratio. */
    public static void setIndeterminate() {
        setState(SDLVideo.SDL_PROGRESS_STATE_INDETERMINATE);
    }

    /** Shows a determinate indicator. */
    public static void setNormal() {
        setState(SDLVideo.SDL_PROGRESS_STATE_NORMAL);
    }

    private static void setState(int state) {
        if (!Display.isCreated()) {
            return;
        }
        final long window = Display.getWindow();
        MainThread.runOnMainThread(() -> Sys.checkSdl(SDLVideo.SDL_SetWindowProgressState(window, state)));
    }

    /**
     * @param completed units done
     * @param total     units total
     */
    public static void setValue(long completed, long total) {
        if (!Display.isCreated() || total <= 0) {
            return;
        }
        final long window = Display.getWindow();
        final float value = (float) Math.max(0.0, Math.min(1.0, (double) completed / (double) total));
        MainThread.runOnMainThread(() -> Sys.checkSdl(SDLVideo.SDL_SetWindowProgressValue(window, value)));
    }

    /** Clears the indicator. */
    public static void clear() {
        if (!Display.isCreated()) {
            return;
        }
        final long window = Display.getWindow();
        MainThread
            .runOnMainThread(() -> Sys.checkSdl(SDLVideo.SDL_SetWindowProgressState(window, SDLVideo.SDL_PROGRESS_STATE_NONE)));
    }
}
