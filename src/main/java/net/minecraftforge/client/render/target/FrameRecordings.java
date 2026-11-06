package net.minecraftforge.client.render.target;

import org.jspecify.annotations.Nullable;

/**
 * The recording for the frame being built, installed by the active backend.
 *
 * <p>{@code null} means the GL path, where {@code Framebuffer} talks to GL directly and none of this is consulted.</p>
 */
public final class FrameRecordings {

    private static volatile @Nullable FrameRecording current;

    private FrameRecordings() {}

    /** Installed by a backend when it takes over the frame. */
    public static void install(@Nullable FrameRecording recording) {
        current = recording;
    }

    /** The recording for the current frame, or {@code null} on the GL path. */
    public static @Nullable FrameRecording current() {
        return current;
    }
}
