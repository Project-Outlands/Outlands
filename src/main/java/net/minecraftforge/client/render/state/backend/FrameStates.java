package net.minecraftforge.client.render.state.backend;

import net.minecraftforge.client.render.RenderBackendSelection;

import org.jspecify.annotations.Nullable;

/** Supplies the frame-state backend for a renderer that is not OpenGL. */
public final class FrameStates {

    private static final FrameStateVk VK = new FrameStateVk();
    private static final ImmediateMode IMMEDIATE = new ImmediateMode();

    private FrameStates() {}

    /** The backend for the active renderer, or {@code null} when the vanilla GL code path applies. */
    public static @Nullable FrameState forNonGl() {
        return RenderBackendSelection.glPath() ? null : VK;
    }

    /**
     * Reports a fixed-function or immediate-mode operation this backend does not implement yet.
     *
     * <p>Always throws. It exists so the guards read as "this is a known gap with a name" rather than as a silent
     * no-op, and so the message says what would be needed. Calling it is a programming error in the guard, not
     * something a caller can catch and continue from.</p>
     */
    public static UnsupportedOperationException unimplemented(String operation, String needs) {
        return new UnsupportedOperationException("the Vulkan backend does not implement " + operation + " yet. "
                + "Needed: " + needs + ". Vanilla's own use of it is small and enumerated in"
                + " docs/vulkan-backend-plan.md 8.4.1, but the fixed-function and immediate-mode groups are a"
                + " shader-variant and vertex-accumulation problem, not a translation.");
    }

    private static final java.util.concurrent.atomic.AtomicBoolean REPORTED = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Records, once, that an operation was skipped.
     *
     * <p>For operations whose absence changes how the game looks but not whether it runs. Reported once because a
     * per-frame line would be unreadable, and recorded because an omitted effect with no trace is indistinguishable
     * from a bug. Everything that changes whether the game runs correctly throws instead - see
     * {@link #unimplemented}.</p>
     */
    public static void reportSkippedOnce(String operation, String consequence) {
        if (REPORTED.compareAndSet(false, true)) {
            org.apache.logging.log4j.LogManager.getLogger("Outlands|render").warn(
                    "Skipping {}: the Vulkan backend does not implement it yet, so {}. This is reported once.",
                    operation, consequence);
        }
    }

    /**
     * The immediate-mode accumulator.
     *
     * <p>Always available; callers are the {@code glBegin}/{@code glEnd} guards, which only reach it when the GL path
     * does not apply.</p>
     */
    public static ImmediateMode immediate() {
        return IMMEDIATE;
    }

    /** The Vulkan instance, for diagnostics and tests. {@code null} when the GL path is active. */
    public static @Nullable FrameState instance() {
        return forNonGl();
    }
}
