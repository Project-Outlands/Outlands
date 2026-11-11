package org.lwjgl.opengl;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Synchronous window callbacks for integrations that create their own rendering context. */
public final class DisplayEvents {
    private static final Logger LOGGER = LogManager.getLogger("Outlands|window");
    private static final List<Consumer<DisplayWindowContext>> PRE_CREATE = new CopyOnWriteArrayList<>();
    private static final List<Consumer<DisplayWindowContext>> POST_CREATE = new CopyOnWriteArrayList<>();
    private static final List<Consumer<DisplayWindowContext>> PRE_DESTROY = new CopyOnWriteArrayList<>();
    private static final List<Consumer<SwapchainInvalidatingChange>> PRE_CHANGE = new CopyOnWriteArrayList<>();
    private static boolean createGLContext = true;

    private DisplayEvents() {}

    /** Selects context creation before Display.create; non-GL integrations must install a drawable in post-create. */
    public static void setCreateGLContext(boolean enabled) { createGLContext = enabled; }

    /** Returns the configured context choice; the active window reports its choice through Display.hasGLContext. */
    public static boolean isCreateGLContextEnabled() { return createGLContext; }

    /** Registers a window-thread callback that may modify the live SDL properties before window creation. */
    public static void addPreWindowCreateListener(Consumer<DisplayWindowContext> listener) { PRE_CREATE.add(Objects.requireNonNull(listener)); }

    /** Registers a window-thread callback after the native window exists and before its drawable is bound. */
    public static void addPostWindowCreateListener(Consumer<DisplayWindowContext> listener) { POST_CREATE.add(Objects.requireNonNull(listener)); }

    /** Registers a calling-thread callback before drawable and native window resources are destroyed. */
    public static void addPreWindowDestroyListener(Consumer<DisplayWindowContext> listener) { PRE_DESTROY.add(Objects.requireNonNull(listener)); }

    /** Registers a synchronous callback on the thread requesting a window mode or fullscreen change. */
    public static void addPreSwapchainInvalidatingChangeListener(Consumer<SwapchainInvalidatingChange> listener) { PRE_CHANGE.add(Objects.requireNonNull(listener)); }

    static void firePreWindowCreate(DisplayWindowContext context) { fire(PRE_CREATE, context); }
    static void firePostWindowCreate(DisplayWindowContext context) { fire(POST_CREATE, context); }
    static void firePreWindowDestroy(DisplayWindowContext context) { fire(PRE_DESTROY, context); }

    /** Notifies integrations before an external caller changes SDL window geometry directly. */
    public static void firePreSwapchainInvalidatingChange(SwapchainInvalidatingChange change) { fire(PRE_CHANGE, change); }

    private static <T> void fire(List<Consumer<T>> listeners, T context) {
        for (Consumer<T> listener : listeners) {
            try {
                listener.accept(context);
            } catch (RuntimeException | Error failure) {
                LOGGER.error("Display event listener failed", failure);
                throw failure;
            }
        }
    }
}
