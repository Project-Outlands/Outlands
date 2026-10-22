package com.cleanroommc.client.ime;

import org.lwjgl.MainThread;
import org.lwjgl.Sys;
import org.lwjgl.opengl.Display;
import org.lwjgl.sdl.SDLKeyboard;

import java.util.function.Consumer;

/**
 * Toggles SDL text input. GLFW exposed a per-window IME switch; SDL models the same state through
 * its text input session, which also drives the IME on X11 and Wayland.
 */
public class SdlTextInputHandler implements Consumer<Boolean> {

    @Override
    public void accept(Boolean active) {
        MainThread.runOnMainThread(() -> {
            if (active) {
                Sys.checkSdl(SDLKeyboard.SDL_StartTextInput(Display.getWindow()));
            } else {
                Sys.checkSdl(SDLKeyboard.SDL_StopTextInput(Display.getWindow()));
            }
        });
    }
}
