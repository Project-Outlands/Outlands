package org.lwjgl.opengl;

import static org.lwjgl.sdl.SDLVideo.SDL_GetDesktopDisplayMode;
import static org.lwjgl.sdl.SDLVideo.SDL_GetPrimaryDisplay;

import org.lwjgl.MainThread;
import org.lwjgl.Sys;
import org.lwjgl.sdl.SDLPixels;
import org.lwjgl.sdl.SDL_DisplayMode;

/**
 * Resolves the desktop display mode on the process's first thread.
 * <p>
 * Deliberately a separate class. The query is marshalled, and a lambda compiled into {@link Display} is a static
 * method of {@code Display}: invoking it on the event loop requires {@code Display} to have finished initializing,
 * while the client thread that is running {@code Display}'s static initializer is waiting for this very result. The
 * JVM then has one thread waiting for a class it is initializing and the initializer waiting for that thread - the
 * process stops with no window, no exception and no log line after SDL comes up. Keeping the lambda in a class whose
 * initialization completes before the marshal breaks that cycle.
 */
final class DesktopDisplayModeQuery {

    private DesktopDisplayModeQuery() {}

    /** @return the primary display's desktop mode, queried on the first thread */
    static DisplayMode query() {
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
}
