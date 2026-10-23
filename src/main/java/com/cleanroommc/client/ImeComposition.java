package com.cleanroommc.client;

/**
 * The composition string an IME is still building, e.g. the pinyin before a candidate is picked.
 * <p>
 * Kept apart from the text field's own text: nothing here is committed, so it is drawn over the field
 * instead of being inserted into it. SDL reports it through {@code SDL_EVENT_TEXT_EDITING}, which only
 * arrives once the game declared that it renders composition text itself.
 */
public final class ImeComposition {

    private static volatile String text = "";
    private static volatile int caret = 0;

    private ImeComposition() {}

    /** Composition string being built, empty when the IME is not composing. */
    public static String getText() {
        return text;
    }

    /** Caret offset inside {@link #getText()}. */
    public static int getCaret() {
        return caret;
    }

    public static void update(String newText, int newCaret) {
        final String value = newText == null ? "" : newText;
        text = value;
        caret = Math.max(0, Math.min(newCaret, value.length()));
    }

    public static void clear() {
        text = "";
        caret = 0;
    }

    /** True while the IME is building a composition string that has not been committed yet. */
    public static boolean isComposing() {
        return !text.isEmpty();
    }
}