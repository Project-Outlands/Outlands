package org.lwjgl;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

import top.outlands.foundation.boot.MainThreadExecutor;

/**
 * Dispatches window and event work onto the process main thread.
 * <p>
 * GLFW's {@code glfwPollEvents} and SDL3's {@code SDL_PumpEvents}/{@code SDL_PeepEvents} may only run there,
 * while the game loop, resource loading and mod background tasks all reach window state from other threads.
 * <p>
 * A thin adapter over {@link MainThreadExecutor} that adds the caller's context class loader propagation,
 * which Foundation does not do, and LWJGL2-shaped overloads.
 * <p>
 * Blocking methods require the main thread to be pumping {@link MainThreadExecutor#drain()}; they run inline
 * when already on the main thread.
 */
public final class MainThread {

    private MainThread() {}

    public static boolean isMainThread() {
        return MainThreadExecutor.isMainThread();
    }

    private static Callable<Void> bindClassLoader(Runnable inner) {
        return bindClassLoader(() -> {
            inner.run();
            return null;
        });
    }

    private static <R> Callable<R> bindClassLoader(Callable<R> inner) {
        final ClassLoader loader = Thread.currentThread()
            .getContextClassLoader();
        return () -> {
            final Thread thread = Thread.currentThread();
            final ClassLoader saved = thread.getContextClassLoader();
            thread.setContextClassLoader(loader);
            try {
                return inner.call();
            } finally {
                thread.setContextClassLoader(saved);
            }
        };
    }

    public static void runOnMainThread(Runnable r) {
        Objects.requireNonNull(r, "r");
        if (isMainThread()) {
            r.run();
        } else {
            MainThreadExecutor.submit(bindClassLoader(r));
        }
    }

    public static <T> T runOnMainThread(Callable<T> r) {
        Objects.requireNonNull(r, "r");
        try {
            if (isMainThread()) {
                return r.call();
            }
            return MainThreadExecutor.submit(bindClassLoader(r));
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
     * Queues a task without blocking. Safe from any thread, including while the game loop is stalled,
     * but the task only runs on the next {@link #drain()}.
     */
    public static void post(Runnable r) {
        Objects.requireNonNull(r, "r");
        MainThreadExecutor.post(() -> {
            try {
                bindClassLoader(r)
                    .call();
            } catch (Exception e) {
                throw new IllegalStateException("Main-thread task failed", e);
            }
        });
    }

    /** Runs queued tasks. Must be called from the main thread, at least once per frame. */
    public static void drain() {
        MainThreadExecutor.drain();
    }
}
