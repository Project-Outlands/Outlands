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
            MainThreadExecutor.submit(() -> {
                r.run();
                return null;
            });
        }
    }

    /** Runs a task on the first thread and returns its result. */
    public static <T> T runOnMainThread(Callable<T> r) {
        Objects.requireNonNull(r, "r");
        try {
            if (isMainThread()) {
                return r.call();
            }
            return MainThreadExecutor.submit(r);
        } catch (Exception e) {
            throw new IllegalStateException("Main-thread task failed", e);
        }
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
