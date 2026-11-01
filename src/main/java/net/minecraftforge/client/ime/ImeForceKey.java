package net.minecraftforge.client.ime;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiControls;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.settings.KeyCombination;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import org.lwjgl.input.InputSnapshot;
import org.lwjgl.input.Keyboard;

/**
 * The "make the input method reachable" key.
 * <p>
 * SDL keeps the operating system's input method detached from the window while its text input session is closed,
 * and the session only opens for things the game recognises as text input: a focused {@link
 * net.minecraft.client.gui.GuiTextField}, a book or a sign. A mod screen that draws its own text widget is none of
 * those, so switching to an input method there can be impossible. This key lets the player ask for the session.
 * <p>
 * It is a {@link KeyBinding} so it appears under Options - Controls and can be rebound, but the match happens in the
 * SDL event layer: {@code KeyInputEvent} is only fired while {@code currentScreen == null ||
 * currentScreen.allowUserInput}, and {@code GuiScreen.allowUserInput} is false for every screen that is not a chat box
 * - exactly the screens this key exists for.
 */
public final class ImeForceKey {

    /**
     * Ctrl+Space, the combination input methods use to switch to themselves. The Ctrl is a logical modifier, so
     * either side of it works and the controls screen names it the way the modifier itself is named.
     */
    public static final KeyBinding FORCE_IME = new KeyBinding(
        "key.outlands.forceIme",
        KeyCombination.fromChord(Keyboard.KEY_SPACE, List.of(Keyboard.KEY_LCONTROL, Keyboard.KEY_SPACE)),
        "key.categories.outlands"
    );

    static {
        // This key is an addition to whatever the player already has on the combination, not a replacement for it, so
        // it must not be reported as clashing with them.
        FORCE_IME.setConflictReportingSuppressed(true);
    }

    private ImeForceKey() {
    }

    public static void register() {
        ClientRegistry.registerKeyBinding(FORCE_IME);
    }

    /** Handles a non-repeat SDL key press with the complete event-time input state. */
    public static void onKeyDown(int lwjglKey, InputSnapshot snapshot) {
        if (Minecraft.getMinecraft().currentScreen instanceof GuiControls
            && isRecording((GuiControls) Minecraft.getMinecraft().currentScreen)) {
            return;
        }
        final KeyCombination combination = FORCE_IME.getKeyCombination();
        if (!combination.matchesEvent(lwjglKey, snapshot)) {
            return;
        }
        if (combination.requires(KeyModifier.CONTROL)) {
            if (!combination.requires(KeyModifier.ALT) && KeyModifier.ALT.isActive(snapshot)) {
                return;
            }
            if (hasUnrequiredMeta(combination, snapshot)) {
                return;
            }
        }
        TextFieldHandler.forceTextInput();
    }

    static boolean isRecording(GuiControls controls) {
        return controls.buttonId != null;
    }

    private static boolean hasUnrequiredMeta(KeyCombination combination, InputSnapshot snapshot) {
        if (!snapshot.isKeyDown(Keyboard.KEY_LMETA) && !snapshot.isKeyDown(Keyboard.KEY_RMETA)) {
            return false;
        }
        if (Minecraft.IS_RUNNING_ON_MAC && combination.requires(KeyModifier.CONTROL)) {
            return false;
        }
        if (isMeta(combination.getKeyCode())) {
            return false;
        }
        for (int keyCode : combination.getAdditionalKeys()) {
            if (isMeta(keyCode)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isMeta(int keyCode) {
        return keyCode == Keyboard.KEY_LMETA || keyCode == Keyboard.KEY_RMETA;
    }

    /** Migrates the legacy hardcoded-Ctrl binding and preserves explicit chord configurations. */
    public static KeyCombination fromOptions(String value) {
        final KeyCombination parsed = KeyCombination.parse(value);
        if (value.startsWith("chord:") || parsed.getKeyCode() == 0) {
            return parsed;
        }
        if (KeyModifier.CONTROL.matches(parsed.getKeyCode())) {
            return KeyCombination.of(parsed.getKeyCode(), KeyModifier.NONE);
        }
        return KeyCombination.fromChord(parsed.getKeyCode(), List.of(Keyboard.KEY_LCONTROL, parsed.getKeyCode()));
    }

    /** Marks this binding explicit so a new single-key rebind is not migrated again. */
    public static String toOptions(KeyCombination combination) {
        return combination.serializeChord();
    }
}
