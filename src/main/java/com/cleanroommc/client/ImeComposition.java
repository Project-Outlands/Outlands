package com.cleanroommc.client;

/**
 * The composition string an IME is still building, e.g. the pinyin before a candidate is picked.
 * Kept apart from the field's own text: it is not committed, so it is drawn over the field instead of
 * being inserted into it.
 */
public final class ImeComposition {

    private static volatile String text = "";
    private static volatile int caret = 0;

    private ImeComposition() {}

    public static String getText() {
        return text;
    }

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

    public static boolean isComposing() {
        return !text.isEmpty();
    }
}