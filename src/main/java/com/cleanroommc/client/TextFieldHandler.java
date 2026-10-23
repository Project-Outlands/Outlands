package com.cleanroommc.client;

import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.ChatAllowedCharacters;
import org.lwjgl.MainThread;
import org.lwjgl.Sys;
import org.lwjgl.opengl.Display;
import org.lwjgl.sdl.SDLHints;
import org.lwjgl.sdl.SDLKeyboard;

import java.lang.ref.WeakReference;

/**
 * Owns SDL's text input session and buffers committed IME text until the focused field consumes it.
 * <p>
 * SDL reports a key press and the characters it produces as separate events. Only
 * {@code SDL_EVENT_TEXT_INPUT} carries what the IME actually committed, so text fields take their input from
 * {@link #textBuffer} and read the character on the key event as a mere trigger.
 * <p>
 * Mirrors lwjgl3ify's handler of the same name.
 */
public final class TextFieldHandler {

    /** Depth counts nested begin/end so a child GUI closing cannot stop the session its parent still needs. */
    private static int textInputDepth = 0;
    private static final WeakReference<GuiTextField> NO_FIELD = new WeakReference<>(null);
    private static WeakReference<GuiTextField> focusedTextInput = NO_FIELD;
    /** Text committed by the IME but not yet written into the focused field. */
    public static final StringBuilder textBuffer = new StringBuilder(8);

    private TextFieldHandler() {}

    private static void setSessionActive(boolean active) {
        MainThread.runOnMainThread(() -> {
            if (active) {
                TextInputArea.invalidate();
                Sys.checkSdl(SDLKeyboard.SDL_StartTextInput(Display.getWindow()));
            } else {
                Sys.checkSdl(SDLKeyboard.SDL_StopTextInput(Display.getWindow()));
            }
        });
    }

    public static void beginTextInput() {
        if (textInputDepth == Integer.MAX_VALUE) {
            return;
        }
        if (textInputDepth++ == 0) {
            setSessionActive(true);
        }
    }

    public static void endTextInput(GuiTextField textField) {
        if (textInputDepth <= 0) {
            return;
        }
        if (--textInputDepth == 0) {
            setSessionActive(false);
            textBuffer.setLength(0);
        }
        if (textField != null && textField == focusedTextInput.get()) {
            focusedTextInput = NO_FIELD;
        }
    }

    /** Drops the session unconditionally, for when the GUI changes underneath an open field. */
    public static void resetTextInput() {
        if (textInputDepth != 0) {
            setSessionActive(false);
            textInputDepth = 0;
        }
        focusedTextInput = NO_FIELD;
        textBuffer.setLength(0);
    }

    public static void setFocusedTextField(GuiTextField textField) {
        if (focusedTextInput.get() != textField) {
            focusedTextInput = new WeakReference<>(textField);
        }
    }

    /** Consumes committed IME text; called for every {@code SDL_EVENT_TEXT_INPUT} while a field is focused. */
    public static void onTextInput(String text) {
        if (textInputDepth <= 0 || focusedTextInput.get() == null) {
            return;
        }
        // Control characters would let multi-line input reach single-line fields.
        if (text.chars().allMatch(c -> ChatAllowedCharacters.isAllowedCharacter((char) c))) {
            textBuffer.append(text);
        }
    }
}
