package com.cleanroommc.client;

import net.minecraftforge.common.ForgeModContainer;
import net.minecraftforge.fml.common.FMLLog;
import org.lwjgl.sdl.SDLVideo;

/**
 * Entry point for IME blocking: routes the "is a text field focused" signal that MC raises into SDL's text
 * input session. All platforms go through the same session, because SDL already talks to IMM32 on Windows and
 * to XIM/ibus on X11 and Wayland itself.
 */
public class IMEHandler {
    private static final boolean blockingEnabled;
    static {
        if (ForgeModContainer.inputMethodBlockingEnabled) {
            final String videoDriver = SDLVideo.SDL_GetCurrentVideoDriver();
            if (videoDriver == null) {
                blockingEnabled = false;
                FMLLog.log.warn("IME handling disabled: SDL video driver unavailable");
            } else {
                blockingEnabled = true;
            }
        } else {
            blockingEnabled = false;
        }
    }

    public static void setIME(boolean active) {
        if (!blockingEnabled) {
            return;
        }
        if (active) {
            TextFieldHandler.beginTextInput();
        } else {
            TextFieldHandler.endTextInput(null);
        }
    }
}
