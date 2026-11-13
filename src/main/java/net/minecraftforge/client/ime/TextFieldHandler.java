package net.minecraftforge.client.ime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.ChatAllowedCharacters;
import net.minecraftforge.client.input.TextInput;
import net.minecraftforge.fml.common.FMLLog;
import org.lwjgl.MainThread;
import org.lwjgl.Sys;
import org.lwjgl.opengl.Display;
import org.lwjgl.sdl.SDLKeyboard;

import java.io.IOException;

/**
 * Owns SDL's text input session and delivers the text an input method commits to the focused text field.
 * <p>
 * SDL exposes a single switch for "text input" (there is no way to keep plain text input while turning the input
 * method off): on Windows {@code SDL_StopTextInput} detaches the IME context from the window, on Wayland it
 * disables {@code zwp_text_input_v3}, and on X11 it only flips SDL's own flag. So the switch is left on exactly
 * while text can be entered, and the char a key event carries is what makes plain typing independent of it.
 */
public final class TextFieldHandler {

    /** The field text belongs to. Strong on purpose: a weak reference was collected between focus and typing. */
    private static GuiTextField focusedTextInput = null;

    /** Set by {@code IMEHandler} for screens that want an input method without a focused field (book/sign/...). */
    private static boolean screenWantsTextInput = false;

    /**
     * Set when the player asks for the input method by hand (Ctrl+Space) on a screen nothing else recognises as a
     * text screen. Deliberately one way: a running input method is switched between Chinese and English by the input
     * method itself, so the only thing the game has to offer is "make the session reachable", not "take it away".
     */
    private static boolean forcedTextInput = false;

    private TextFieldHandler() {}

    /**
     * Brings SDL's text input state in line with what the current screen and field want. Idempotent: it compares
     * against SDL's own state and only issues a call on a transition, so nothing has to keep a counter balanced
     * and a missed begin/end cannot leave the two out of sync.
     */
    private static void updateSession() {
        final boolean wanted = forcedTextInput || screenWantsTextInput || focusedTextInput != null;
        MainThread.runOnMainThread(() -> {
            final long window = Display.getWindow();
            if (window == 0L) {
                return;
            }
            if (wanted == SDLKeyboard.SDL_TextInputActive(window)) {
                return;
            }
            if (wanted) {
                TextInputArea.invalidate();
                Sys.checkSdl(SDLKeyboard.SDL_StartTextInput(window));
            } else {
                Sys.checkSdl(SDLKeyboard.SDL_StopTextInput(window));
            }
            if (FMLLog.log.isDebugEnabled()) {
                FMLLog.log.debug("SDL text input {}, active={}", wanted ? "started" : "stopped",
                    SDLKeyboard.SDL_TextInputActive(window));
            }
        });
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

        // Announced before it is written anywhere: a screen that draws its own text widget and the focused field are
        // two of several possible destinations, and whoever else wants to see the text should not depend on which
        // one it happened to reach.
        TextInput.dispatch(filtered);

        final GuiTextField field = focusedTextInput;
        if (field != null && field.getVisible() && field.isFocused()) {
            field.writeText(filtered);
            if (FMLLog.log.isDebugEnabled()) {
                FMLLog.log.debug("committed text [{}] written to {}", filtered, field.getClass().getSimpleName());
            }
            return;
        }

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
