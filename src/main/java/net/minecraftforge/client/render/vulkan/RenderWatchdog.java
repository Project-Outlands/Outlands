package net.minecraftforge.client.render.vulkan;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Says what the render thread was doing when it stops doing anything.
 *
 * <p><b>Why this exists.</b> Three of this renderer's blocking points have no timeout: the wait for a frame's fence,
 * the wait for an image to present, and {@code vkDeviceWaitIdle} before a target is rebuilt. A device that has hung -
 * or a fence that is never signalled - turns every one of them into a silent freeze: no exception, no log line, the
 * window stops repainting and the log ends wherever it happened to be. That is exactly what a report of "it hangs
 * when the world loads, the log looks fine" is, and it is indistinguishable from a deadlock or an infinite loop
 * without a stack.</p>
 *
 * <p>So the render thread reports two things: every completed frame (a timestamp) and, around each blocking call,
 * what it is about to block on. A daemon thread compares the two. If five seconds pass with no frame, it prints the
 * phase and the render thread's stack - once, so a stall does not fill the log - and then prints how long the stall
 * lasted when it ends. {@code -Doutlands.watchdog.seconds=N} moves the threshold; {@code 0} turns it off.</p>
 *
 * <p>Cost when nothing is wrong: one volatile write a frame and a thread that sleeps.</p>
 */
final class RenderWatchdog {

    private static final Logger LOGGER = LogManager.getLogger("Outlands|render");

    /** How long without a frame counts as a stall; a level load can take a second, a hang takes forever. */
    private static final long STALL_NANOS = Long.getLong("outlands.watchdog.seconds", 5L) * 1_000_000_000L;

    private static volatile long lastFrameAt = System.nanoTime();
    private static volatile long stalledSince;
    private static volatile String phase = "not started";
    private static volatile Thread renderThread;

    private RenderWatchdog() {
    }

    /** Starts watching {@code thread}, once, on the first frame the renderer runs. */
    static void start(Thread thread) {
        if (STALL_NANOS <= 0L || renderThread != null) {
            return;
        }
        renderThread = thread;
        lastFrameAt = System.nanoTime();
        Thread watchdog = new Thread(RenderWatchdog::loop, "Outlands render watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /** Called by the render thread before a call that can block for an unbounded time. */
    static void blockingOn(String what) {
        phase = what;
    }

    /** Called by the render thread once a frame has been submitted. */
    static void frameCompleted() {
        long now = System.nanoTime();
        long stalled = stalledSince;
        if (stalled != 0L) {
            stalledSince = 0L;
            LOGGER.warn("render thread resumed after {} s in: {}", (now - stalled) / 1_000_000_000.0, phase);
        }
        lastFrameAt = now;
        phase = "between frames";
    }

    private static void loop() {
        while (true) {
            try {
                Thread.sleep(Math.min(1000L, Math.max(100L, STALL_NANOS / 1_000_000L / 4L)));
            } catch (InterruptedException e) {
                return;
            }
            long now = System.nanoTime();
            long idle = now - lastFrameAt;
            if (idle < STALL_NANOS || stalledSince != 0L) {
                continue;
            }
            stalledSince = lastFrameAt;
            StackTraceElement[] stack = renderThread.getStackTrace();
            LOGGER.warn("render thread has not completed a frame for {} s, blocked in: {}",
                    idle / 1_000_000_000.0, phase);
            for (StackTraceElement element : stack) {
                LOGGER.warn("    at {}", element);
            }
            if (stack.length == 0) {
                LOGGER.warn("    (no stack: the thread is in native code that the JVM cannot walk, or has exited)");
            }
        }
    }
}
