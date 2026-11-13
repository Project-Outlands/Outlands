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
 * SDL's {@code SDL_PumpEvents}/{@code SDL_PeepEvents} may only run there, while the game loop,
 * resource loading and mod background tasks all reach window state from other threads.
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
            final long start = System.nanoTime();
            MainThreadExecutor.submit(bindClassLoader(r));
            reportIfSlow(start);
        }
    }

    public static <T> T runOnMainThread(Callable<T> r) {
        Objects.requireNonNull(r, "r");
        try {
            if (isMainThread()) {
                return r.call();
            }
            final long start = System.nanoTime();
            final T value = MainThreadExecutor.submit(bindClassLoader(r));
            reportIfSlow(start);
            return value;
        } catch (Exception e) {
            throw new IllegalStateException("Main-thread task failed", e);
        }
    }

    /**
     * How long a blocking marshal may take before it is reported.
     *
     * <p>Not a timeout: the call still waits, because the caller needs its value. It exists because the failure
     * mode of a blocking marshal is a process that stops with no exception anywhere - measured: a world load that
     * never finished, with the integrated server parked in {@code MainThreadExecutor.submit} while the main thread
     * slept in its wait loop. A line naming the caller turns that into a five-minute diagnosis.</p>
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
        LOGGER.warn("A main-thread task took {} ms to run. Queued tasks only run from Display.processMessages(), "
                + "so the main thread has to be pumping; a wait loop that sleeps without draining deadlocks every "
                + "caller. Caller: {}", elapsedMs, caller());
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

    /** Runs queued tasks. Must be called from the main thread, at least once per frame.
     *
     * <p><b>"Once per frame" is not the same as "once per rendered frame".</b> The main thread also spends long
     * stretches waiting for other threads - starting and stopping an integrated server are the two that matter -
     * and those waits are frames too, in the only sense this contract cares about: they are the moments when a
     * thread that needs a value from the main thread is blocked. Both loops in {@code Minecraft} drain for exactly
     * that reason.</p>
     */
    public static void drain() {
        MainThreadExecutor.drain();
    }
}
