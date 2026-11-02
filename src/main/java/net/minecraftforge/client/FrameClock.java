package net.minecraftforge.client;

/**
 * Measures the time between rendered frames. Called once per frame on the client render thread; callers on other
 * threads would race with the render loop. The first frame reports {@code 1/60} seconds and every later value is
 * clamped to {@code [0, 0.1]} seconds.
 */
public final class FrameClock {

    private static final float FIRST_FRAME_SECONDS = 1.0F / 60.0F;
    private static final float MAX_FRAME_SECONDS = 0.1F;

    private static boolean started;
    private static long lastFrameNanos;
    private static float deltaSeconds = FIRST_FRAME_SECONDS;

    private FrameClock() {
    }

    public static void beginFrame() {
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

    public static float getDeltaSeconds() {
        return deltaSeconds;
    }

    static void reset() {
        started = false;
        lastFrameNanos = 0L;
        deltaSeconds = FIRST_FRAME_SECONDS;
    }
}
