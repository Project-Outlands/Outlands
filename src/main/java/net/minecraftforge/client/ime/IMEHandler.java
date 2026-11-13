package net.minecraftforge.client.ime;

import net.minecraftforge.common.ForgeModContainer;
import net.minecraftforge.fml.common.FMLLog;
import org.lwjgl.MainThread;
import org.lwjgl.sdl.SDLHints;
import org.lwjgl.sdl.SDLVideo;

/**
 * Entry point for IME blocking: routes the "is a text field focused" signal that MC raises into SDL's text
 * input session. All platforms go through the same session, because SDL already talks to IMM32 on Windows and
 * to XIM on X11 and to the compositor's text-input protocol on Wayland.
 * <p>
 * SDL keeps the operating system's input method detached from the window until {@code SDL_StartTextInput} is
 * called, so the session is the only thing that decides whether the player can switch to a CJK input method at
 * all; see {@link TextFieldHandler} for the session itself.
 */
public class IMEHandler {

    /** Resolved on first use, once SDL video is up; {@code null} until then or when the answer is still unknown. */
    private static volatile Boolean blockingEnabled;

    public static void setIME(boolean active) {
        if (!blockingEnabled()) {
            return;
        }
        TextFieldHandler.setScreenWantsTextInput(active);
    }

    /**
     * Whether IME blocking may touch the SDL session. Resolved lazily instead of in a static initializer: this
     * class can be loaded before SDL video is initialised, and {@code SDL_GetCurrentVideoDriver} then returns
     * {@code null} - which must not be mistaken for "this platform has no IME" and cached for the whole process.
     */
    private static boolean blockingEnabled() {
        final Boolean cached = blockingEnabled;
        if (cached != null) {
            return cached;
        }
        if (!ForgeModContainer.inputMethodBlockingEnabled) {
            blockingEnabled = Boolean.FALSE;
            FMLLog.log.info("IME handling disabled: forge.cfg has inputMethodBlockingEnabled=false");
            return false;
        }
        final String videoDriver = MainThread.runOnMainThread(SDLVideo::SDL_GetCurrentVideoDriver);
        if (videoDriver == null) {
            // Video is not up yet. Deliberately not cached, the caller asks again once it is.
            return false;
        }
        blockingEnabled = Boolean.TRUE;
        FMLLog.log.info("IME handling enabled on SDL video driver '{}' (implemented IME UI: {})",
            videoDriver, MainThread.runOnMainThread(
                () -> SDLHints.SDL_GetHint(SDLHints.SDL_HINT_IME_IMPLEMENTED_UI)));
        return true;
    }
}
