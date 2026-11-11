package org.lwjgl.opengl;

/** Native window state; props is valid only before creation, and native handles only afterwards. */
public record DisplayWindowContext(int props, long window, long glContext, PixelFormat pixelFormat,
    ContextAttribs attribs, DisplayMode mode, String windowTitle, boolean glContextEnabled) {}
