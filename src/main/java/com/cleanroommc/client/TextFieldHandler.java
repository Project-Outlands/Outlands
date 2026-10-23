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
 * Only {@code SDL_EVENT_TEXT_INPUT} carries what the IME committed, so fields read {@link #textBuffer}
 * and treat the key event character as a mere trigger.
 */
public final class TextFieldHandler {

    /** Counts nested begin/end so a closing child GUI cannot stop the session its parent still needs. */
    private static int textInputDepth = 0;
    private static final WeakReference<GuiTextField> NO_FIELD = new WeakReference<>(null);
    private static WeakReference<GuiTextField> focusedTextInput = NO_FIELD;
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

    public static void resetTextInput() {
        if (textInputDepth != 0) {
            setSessionActive(false);
            textInputDepth = 0;
        }
        focusedTextInput = NO_FIELD;
        textBuffer.setLength(0);
        ImeComposition.clear();
    }

    public static void setFocusedTextField(GuiTextField textField) {
        if (focusedTextInput.get() != textField) {
            focusedTextInput = new WeakReference<>(textField);
        }
    }

    public static void onTextInput(String text) {
        if (textInputDepth <= 0 || focusedTextInput.get() == null) {
            return;
        }
        if (text.chars().allMatch(c -> ChatAllowedCharacters.isAllowedCharacter((char) c))) {
            textBuffer.append(text);
        }
    }
}
