package org.lwjgl;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

import top.outlands.foundation.boot.MainThreadExecutor;

/**
 * Dispatches window and event work onto the process's first thread.
 * <p>
 * SDL's {@code SDL_PumpEvents}/{@code SDL_PeepEvents} and the window API may only run on the thread that initialised
 * video, while the game loop, resource loading and mod background tasks all reach window state from other threads.
 * <p>
 * Since Foundation 1.1.0 that thread is no longer the game thread. Following RetroFuturaBootstrap's
 * {@code MainStartOnFirstThread}, Foundation starts the game on a thread of its own ({@code Foundation-Main}) and
 * turns the process's first thread into an event loop ({@code Foundation-EventLoop}) that services the tasks queued
 * here. The loop drains continuously, so a blocking call no longer has to wait for the game loop to reach a frame
 * boundary, and it cannot deadlock a thread that is itself blocked in a launcher wait loop. Tasks keep the context
 * class loader of the submitting thread - Foundation does the binding - which is what mod code loading classes from
 * an SDL callback needs.
 * <p>
 * Blocking methods run inline when already on the first thread.
 */
public final class MainThread {

    private MainThread() {}

    /** @return whether the current thread is the process's first thread, the one that owns SDL/Cocoa */
    public static boolean isMainThread() {
        return MainThreadExecutor.isMainThread();
    }

    /** Runs a task on the first thread, waiting for it to finish unless the caller already is that thread. */
    public static void runOnMainThread(Runnable r) {
        Objects.requireNonNull(r, "r");
        if (isMainThread()) {
            r.run();
        } else {
            final long start = System.nanoTime();
            MainThreadExecutor.submit(() -> {
                r.run();
                return null;
            });
            reportIfSlow(start);
        }
    }

    /** Runs a task on the first thread and returns its result. */
    public static <T> T runOnMainThread(Callable<T> r) {
        Objects.requireNonNull(r, "r");
        try {
            if (isMainThread()) {
                return r.call();
            }
            final long start = System.nanoTime();
            final T value = MainThreadExecutor.submit(r);
            reportIfSlow(start);
            return value;
        } catch (Exception e) {
            throw new IllegalStateException("Main-thread task failed", e);
        }
    }

    /**
     * How long a blocking marshal may take before it is reported.
     *
     * <p>Not a timeout: the call still waits, because the caller needs its value. It exists because the failure mode
     * of a blocking marshal is a process that stops with no exception anywhere. Measured twice before the event loop
     * existed: once with the game thread as the first thread, where a wait loop that did not pump deadlocked every
     * caller, and once where the pump ran once per 200 ms, so each lookup cost most of a frame. Now that the loop
     * drains continuously, a marshal that takes seconds means the loop itself is stuck - a task that blocks, or the
     * driver underneath it - and the caller's frame is the only thing that says which.</p>
     */
    private static final long SLOW_MARSHAL_MS = 5_000L;

    /** At most one report per this interval, so a repeatedly blocked call site cannot flood the log. */
    private static final long SLOW_REPORT_INTERVAL_NANOS = 30_000_000_000L;

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("Outlands|mainthread");

    private static volatile long lastSlowReportAt;

    private static void reportIfSlow(long startNanos) {
        final long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        if (elapsedMs < SLOW_MARSHAL_MS) {
            return;
        }
        final long now = System.nanoTime();
        final long last = lastSlowReportAt;
        if (last != 0L && now - last < SLOW_REPORT_INTERVAL_NANOS) {
            return;
        }
        lastSlowReportAt = now;
        LOGGER.warn("A first-thread task took {} ms to run. The event loop drains continuously, so this is the loop "
                + "being stuck in a task rather than waiting for a frame. Caller: {}", elapsedMs, caller());
    }

    /** The first stack frame outside this class, for the report above. */
    private static String caller() {
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            if (!frame.getClassName().startsWith("org.lwjgl.MainThread")
                    && !frame.getClassName().startsWith("java.")) {
                return frame.toString();
            }
        }
        return "unknown";
    }

    public static boolean runOnMainThread(BooleanSupplier r) {
        Objects.requireNonNull(r, "r");
        if (isMainThread()) {
            return r.getAsBoolean();
        }
        return Objects.requireNonNull(runOnMainThread(() -> (Boolean) r.getAsBoolean()));
    }

    public static int runOnMainThread(IntSupplier r) {
        Objects.requireNonNull(r, "r");
        if (isMainThread()) {
            return r.getAsInt();
        }
        return Objects.requireNonNull(runOnMainThread(() -> (Integer) r.getAsInt()));
    }

    public static long runOnMainThread(LongSupplier r) {
        Objects.requireNonNull(r, "r");
        if (isMainThread()) {
            return r.getAsLong();
        }
        return Objects.requireNonNull(runOnMainThread(() -> (Long) r.getAsLong()));
    }

    public static double runOnMainThread(DoubleSupplier r) {
        Objects.requireNonNull(r, "r");
        if (isMainThread()) {
            return r.getAsDouble();
        }
        return Objects.requireNonNull(runOnMainThread(() -> (Double) r.getAsDouble()));
    }

    /**
     * Queues a task for the first thread without blocking. Safe from any thread; the task runs on the event loop's
     * next iteration, whether or not the game is currently drawing frames.
     */
    public static void post(Runnable r) {
        Objects.requireNonNull(r, "r");
        MainThreadExecutor.post(r);
    }
}
