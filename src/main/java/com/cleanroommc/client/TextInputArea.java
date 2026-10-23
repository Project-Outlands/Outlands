package com.cleanroommc.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import org.lwjgl.Sys;
import org.lwjgl.opengl.Display;
import org.lwjgl.sdl.SDLKeyboard;
import org.lwjgl.sdl.SDL_Rect;
import org.lwjgl.system.MemoryStack;

/** Anchors the OS IME windows to the focused text field's caret. Rectangles are in window pixels, not GUI units. */
public final class TextInputArea {

    private TextInputArea() {}

    private static int lastX = Integer.MIN_VALUE;
    private static int lastY;
    private static int lastHeight;
    private static int lastCursor;

    public static void update(GuiTextField field) {
        if (field == null || !field.getVisible() || !Display.isCreated()) {
            return;
        }

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
        final int height = Math.max(1, font.FONT_HEIGHT) * scale;
        final int cursor = caretPixels * scale;

        if (x == lastX && y == lastY && height == lastHeight && cursor == lastCursor) {
            return;
        }
        lastX = x;
        lastY = y;
        lastHeight = height;
        lastCursor = cursor;
        publish(x, y, field.width * scale, height, cursor);
    }

    private static void publish(int x, int y, int width, int height, int cursor) {
        final long window = Display.getWindow();
        if (window == 0L) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final SDL_Rect.Buffer rect = SDL_Rect.calloc(1, stack);
            rect.x(x).y(y).w(Math.max(1, width)).h(height);
            SDLKeyboard.SDL_SetTextInputArea(window, rect, cursor);
        }
    }


    public static void invalidate() {
        lastX = Integer.MIN_VALUE;
    }
}
