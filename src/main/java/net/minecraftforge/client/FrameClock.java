package net.minecraftforge.client;

/**
 * Measures rendered frame durations. {@link #beginFrame()} runs at the start of the current frame and records the
 * time since the previous frame start, so {@link #getDeltaSeconds()} returns the duration of the frame that just
 * ended. That is the value smoothing code should integrate over the current frame; it is not the current frame's
 * length. The first frame reports {@code 1/60} seconds, and every later value is clamped to {@code [0, 0.1]} seconds
 * so a hitch cannot be caught up in one frame.
 *
 * <p>Client render thread only. The public read path is {@link ForgeHooksClient#getFrameDeltaSeconds()}.
 */
final class FrameClock {

    private static final float FIRST_FRAME_SECONDS = 1.0F / 60.0F;
    private static final float MAX_FRAME_SECONDS = 0.1F;

    private static boolean started;
    private static long lastFrameNanos;
    private static float deltaSeconds = FIRST_FRAME_SECONDS;

    private FrameClock() {
    }

    static void beginFrame() {
        beginFrame(System.nanoTime());
    }

    static void beginFrame(long nanos) {
        if (!started) {
            started = true;
            lastFrameNanos = nanos;
            deltaSeconds = FIRST_FRAME_SECONDS;
            return;
        }

        long elapsed = nanos - lastFrameNanos;
        lastFrameNanos = nanos;
        deltaSeconds = elapsed <= 0L ? 0.0F : Math.min(elapsed / 1.0e9F, MAX_FRAME_SECONDS);
    }

    static float getDeltaSeconds() {
        return deltaSeconds;
    }

    /** Test-only reset; production never restarts the clock. */
    static void reset() {
        started = false;
        lastFrameNanos = 0L;
        deltaSeconds = FIRST_FRAME_SECONDS;
    }
}
