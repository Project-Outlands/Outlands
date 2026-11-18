package net.minecraftforge.client.input;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Text that arrives without a key press behind it: an input method commit, or a paste.
 * <p>
 * Vanilla only ever sees such text as the character attached to a key event, which is why a commit either has to
 * invent key events for each character or write to the focused field directly. Both leave a screen that draws its
 * own text widget outside the loop. This is the channel for those screens and for anything that wants to observe
 * the text the game accepted, in the order it was accepted.
 * <p>
 * A {@link GuiScreen} implementing {@link Listener} is notified while it is the open screen, so a screen needs no
 * registration and cannot leak one.
 */
public final class TextInput {

    /**
     * Receives committed text after it passed the same character filter a typed character goes through. A focused
     * text field has already accepted the text when this is called.
     * <p>
     * Called on Foundation's event-pump thread while the client thread waits for input dispatch.
     */
    public interface Listener {
        void onCommittedText(String text);
    }

    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private TextInput() {}

    public static void addListener(Listener listener) {
        listeners.add(listener);
    }

    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Called by the input layer once text is final; not part of the mod-facing surface. */
    public static void dispatch(String text) {
        if (text.isEmpty()) {
            return;
        }
        final Minecraft mc = Minecraft.getMinecraft();
        final GuiScreen screen = mc == null ? null : mc.currentScreen;
        if (screen instanceof Listener listener) {
            listener.onCommittedText(text);
        }
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).onCommittedText(text);
        }
    }
}
