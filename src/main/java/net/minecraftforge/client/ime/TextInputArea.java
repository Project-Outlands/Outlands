package net.minecraftforge.client.ime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import org.lwjgl.MainThread;
import org.lwjgl.opengl.Display;
import org.lwjgl.sdl.SDLKeyboard;
import org.lwjgl.sdl.SDL_Rect;
import org.lwjgl.system.MemoryStack;

/** Anchors the OS IME windows to the text being edited. Rectangles are in window pixels, not GUI units. */
public final class TextInputArea {

    private TextInputArea() {}

    private static int lastX = Integer.MIN_VALUE;
    private static int lastY;
    private static int lastWidth;
    private static int lastHeight;
    private static int lastCursor;

    public static void update(GuiTextField field) {
        if (field == null || !field.getVisible() || !Display.isCreated()) {
            return;
        }

        // Called from GuiTextField.drawTextBox for the focused field on every frame, which makes this the most
        // reliable place to publish "the field text input belongs to": unlike the setFocused hook it cannot be
        // stale, because a field that stops being focused stops drawing itself and stops reporting here.
        TextFieldHandler.setFocusedTextField(field);

        final Minecraft mc = Minecraft.getMinecraft();
        final FontRenderer font = mc.fontRenderer;
        final int scale = new ScaledResolution(mc).getScaleFactor();
        if (scale <= 0) {
            return;
        }

        final boolean background = field.getEnableBackgroundDrawing();
        final int textX = field.x + (background ? 4 : 0);
        final int textY = field.y + (background ? (field.height - 8) / 2 : 0);

        final String text = field.getText();
        final int scroll = Math.min(field.getLineScrollOffset(), text.length());
        final String visible = font.trimStringToWidth(text.substring(scroll), field.width);
        final int caretIndex = Math.max(0, Math.min(field.getCursorPosition() - scroll, visible.length()));
        final int caretPixels = font.getStringWidth(visible.substring(0, caretIndex));

        final int x = textX * scale;
        final int y = (textY - 1) * scale;
        final int width = field.width * scale;
        final int height = Math.max(1, font.FONT_HEIGHT) * scale;
        final int cursor = caretPixels * scale;

        publishIfChanged(x, y, width, height, cursor);
    }

    /**
     * Positions the IME window for screens that edit text without a {@link GuiTextField} - signs, books and mod
     * screens. Following the caret there would mean re-implementing their text layout (the sign draws through a 3D
     * transform, books wrap inside {@code FontRenderer}), which is not worth the fragility, so they all fall back to
     * the window's lower left corner. A screen that does know its caret can override
     * {@link GuiScreen#getTextInputArea()} and opt in to caret tracking.
     */
    public static void update(GuiScreen screen) {
        if (screen == null || !Display.isCreated()) {
            return;
        }
        // A focused text field reports its own caret on every frame and owns the anchor while it exists.
        if (TextFieldHandler.hasFocusedTextField()) {
            return;
        }
        final Minecraft mc = Minecraft.getMinecraft();
        final ScaledResolution resolution = new ScaledResolution(mc);
        final int scale = resolution.getScaleFactor();
        if (scale <= 0) {
            return;
        }

        final int[] area = screen.getTextInputArea();
        final int guiX;
        final int guiY;
        final int guiWidth;
        final int guiHeight;
        final int guiCursor;
        if (area != null && area.length >= 5) {
            guiX = area[0];
            guiY = area[1];
            guiWidth = Math.max(1, area[2]);
            guiHeight = Math.max(1, area[3]);
            guiCursor = area[4];
        } else {
            // The window's lower left corner, nothing else: candidate windows differ in size per platform and per
            // input method, so any guessed offset is wrong for somebody. Signs and books could get a caret-accurate
            // anchor if they ever grow a real GUI text editor; until then a uniform, predictable spot is better.
            guiX = 0;
            guiWidth = 8;
            guiHeight = Math.max(1, mc.fontRenderer.FONT_HEIGHT);
            guiY = Math.max(0, resolution.getScaledHeight() - guiHeight);
            guiCursor = 0;
        }

        publishIfChanged(guiX * scale, guiY * scale, guiWidth * scale, guiHeight * scale, guiCursor * scale);
    }

    private static void publishIfChanged(int x, int y, int width, int height, int cursor) {
        if (x == lastX && y == lastY && width == lastWidth && height == lastHeight && cursor == lastCursor) {
            return;
        }
        lastX = x;
        lastY = y;
        lastWidth = width;
        lastHeight = height;
        lastCursor = cursor;
        publish(x, y, width, height, cursor);
    }

    /** SDL turns this into a {@code set_cursor_rectangle} of width 1 at {@code x + cursor}, so only x, y and h matter. */
    private static void publish(int x, int y, int width, int height, int cursor) {
        final long window = Display.getWindow();
        if (window == 0L) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final SDL_Rect.Buffer rect = SDL_Rect.calloc(1, stack);
            rect.x(x).y(y).w(Math.max(1, width)).h(Math.max(1, height));
            // SDL_SetTextInputArea is part of the window API and may only run on the first thread. The marshal blocks,
            // so the stack frame above outlives the call on the other thread.
            MainThread.runOnMainThread(() -> SDLKeyboard.SDL_SetTextInputArea(window, rect, cursor));
        }
    }

    public static void invalidate() {
        lastX = Integer.MIN_VALUE;
    }
}
