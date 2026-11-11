package org.lwjgl.opengl;

import org.jspecify.annotations.Nullable;

/** A calling-thread window mutation; newMode is present only for display-mode changes. */
public record SwapchainInvalidatingChange(Kind kind, boolean newFullscreen, @Nullable DisplayMode newMode) {
    /** Window properties that can invalidate a backend's presentation surface. */
    public enum Kind { FULLSCREEN, DISPLAY_MODE }
}
