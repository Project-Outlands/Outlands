package net.minecraftforge.client.ime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.ChatAllowedCharacters;
import net.minecraftforge.client.input.TextInput;
import net.minecraftforge.common.ForgeModContainer;
import net.minecraftforge.fml.common.FMLLog;
import org.lwjgl.MainThread;
import org.lwjgl.Sys;
import org.lwjgl.opengl.Display;
import org.lwjgl.sdl.SDLKeyboard;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.Platform;

import java.io.IOException;

/** Owns the game window's text input session and routes committed text to the active GUI. */
public final class TextFieldHandler {

    private static GuiTextField focusedTextInput;
    private static boolean screenWantsTextInput;
    private static boolean forcedTextInput;
    private static boolean mouseGrabbed;
    private static WindowsImeFocus windowsImeFocus;

    private TextFieldHandler() {}

    private static void updateSession() {
        final boolean wanted = forcedTextInput || screenWantsTextInput || focusedTextInput != null;
        final boolean blockingEnabled = ForgeModContainer.inputMethodBlockingEnabled;
        MainThread.runOnMainThread(() -> {
            final long window = Display.getWindow();
            if (window == 0L) {
                return;
            }
            final boolean suppressWindowsIme = !wanted && mouseGrabbed && blockingEnabled
                && Platform.get() == Platform.WINDOWS && "windows".equals(SDLVideo.SDL_GetCurrentVideoDriver());
            if (!suppressWindowsIme) {
                restoreWindowsIme();
            }
            if (wanted != SDLKeyboard.SDL_TextInputActive(window)) {
                if (wanted) {
                    TextInputArea.invalidate();
                    Sys.checkSdl(SDLKeyboard.SDL_StartTextInput(window));
                } else {
                    Sys.checkSdl(SDLKeyboard.SDL_StopTextInput(window));
                }
                FMLLog.log.debug("SDL text input {}, active={}", wanted ? "started" : "stopped", wanted);
            }
            if (suppressWindowsIme && windowsImeFocus == null) {
                try {
                    windowsImeFocus = WindowsImeFocus.suppress(window);
                    FMLLog.log.debug("Associated an empty TSF document with the grabbed game window");
                } catch (RuntimeException | LinkageError failure) {
                    FMLLog.log.warn("Could not suppress Windows IME composition in the grabbed game window", failure);
                }
            }
        });
    }

    /** Applies a successful mouse grab change on the window thread and clears text state when gameplay starts. */
    public static void onMouseGrabChanged(boolean grabbed) {
        mouseGrabbed = grabbed;
        if (grabbed) {
            resetTextInput();
        } else {
            updateSession();
        }
    }

    /** Restores the Win32 input association on the window thread before SDL destroys the window. */
    public static void onWindowDestroy() {
        mouseGrabbed = false;
        restoreWindowsIme();
    }

    private static void restoreWindowsIme() {
        if (windowsImeFocus == null) {
            return;
        }
        try {
            windowsImeFocus.close();
            FMLLog.log.debug("Restored the game window's previous TSF focus association");
        } catch (RuntimeException | LinkageError failure) {
            FMLLog.log.warn("Could not restore the game window's TSF focus association", failure);
        } finally {
            windowsImeFocus = null;
        }
    }

    /** Screen-level request, driven by {@code GuiOpenEvent}. */
    public static void setScreenWantsTextInput(boolean wanted) {
        screenWantsTextInput = wanted;
        updateSession();
    }

    /**
     * Opens the text input session because the player asked for it explicitly (Ctrl+Space). Without a session SDL
     * keeps the operating system's input method detached from the window, so a screen that draws its own text widget
     * - and therefore looks like a plain screen to us - could never be typed into otherwise. Only meaningful while a
     * screen is open: in the world the session would swallow movement keys for no gain.
     */
    public static void forceTextInput() {
        if (forcedTextInput) {
            return;
        }
        if (Minecraft.getMinecraft().currentScreen == null) {
            return;
        }
        forcedTextInput = true;
        updateSession();
    }

    /** Called by the focused field while it draws, which keeps the reference honest without any GUI bookkeeping. */
    public static void setFocusedTextField(GuiTextField textField) {
        if (focusedTextInput == textField) {
            return;
        }
        // Moving focus with an unfinished composition in flight would leave that composition owned by nobody: the
        // input method keeps it queued and the next commit lands in a different field than the one it was typed
        // for. SDL has no "commit this composition" call - the input method decides - so the only correct move is
        // to drop it explicitly, which is what SDL_ClearComposition dismisses.
        if (ImeComposition.isComposing()) {
            discardComposition();
        }
        focusedTextInput = textField;
        updateSession();
    }

    /** Drops the input method's unfinished composition, both our copy and the one the input method owns. */
    public static void discardComposition() {
        ImeComposition.clear();
        MainThread.runOnMainThread(() -> {
            final long window = Display.getWindow();
            if (window != 0L) {
                Sys.checkSdl(SDLKeyboard.SDL_ClearComposition(window));
            }
        });
    }

    /** True while the input method is still building a candidate, i.e. the text is not committed yet. */
    public static boolean isComposing() {
        return ImeComposition.isComposing();
    }

    /** True while a drawn text field owns text input, which also means it owns the IME candidate window anchor. */
    public static boolean hasFocusedTextField() {
        return focusedTextInput != null;
    }

    /** Drops every piece of per-screen text state; called when the open screen changes. */
    public static void resetTextInput() {
        focusedTextInput = null;
        screenWantsTextInput = false;
        forcedTextInput = false;
        ImeComposition.clear();
        updateSession();
    }

    /**
     * Delivers text committed by the input method straight into the focused text field.
     * <p>
     * Not routed through {@link org.lwjgl.input.Keyboard}: a commit has no key press behind it, so passing it
     * through the key queue means inventing events for it, which loses or duplicates characters as soon as a commit
     * spans several characters. Input stays serialised with the GUI: the client thread blocks in
     * {@link org.lwjgl.opengl.Display#processMessages()} until the event pump, which runs on Foundation's first
     * thread, has dispatched every event of the frame - so writing to the field here is safe and the key queue stays
     * exactly as LWJGL2 left it.
     */
    public static void onTextInput(String text) {
        if (text.isEmpty()) {
            return;
        }
        final String filtered = text.chars()
            .filter(c -> ChatAllowedCharacters.isAllowedCharacter((char) c))
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
            .toString();
        if (filtered.isEmpty()) {
            return;
        }

        final GuiTextField field = focusedTextInput;
        if (field != null && field.getVisible() && field.isFocused()) {
            field.writeText(filtered);
            TextInput.dispatch(filtered);
            if (FMLLog.log.isDebugEnabled()) {
                FMLLog.log.debug("committed text [{}] written to {}", filtered, field.getClass().getSimpleName());
            }
            return;
        }

        TextInput.dispatch(filtered);

        // No text field: signs, books and any mod screen that handles its own text receive the commit as the key-less
        // character events LWJGL2 produced, so nothing has to know about them individually. keyCode 0 marks "text,
        // no key" there and is what those handlers expect.
        final Minecraft mc = Minecraft.getMinecraft();
        final GuiScreen screen = mc == null ? null : mc.currentScreen;
        if (screen == null) {
            return;
        }
        try {
            screen.typeCommittedText(filtered);
        } catch (IOException e) {
            FMLLog.log.warn("Screen {} rejected committed text [{}]", screen.getClass().getName(), filtered, e);
            return;
        }
        if (FMLLog.log.isDebugEnabled()) {
            FMLLog.log.debug("committed text [{}] handed to {}", filtered, screen.getClass().getSimpleName());
        }
    }
}
