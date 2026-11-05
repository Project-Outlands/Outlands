package net.minecraftforge.client.render.state;

import org.jspecify.annotations.Nullable;

/**
 * Process-wide registration point for the read-only state views.
 *
 * <p>Views are installed once, from the engine's initialization, and read for the rest of the process.
 * A missing view reports {@link #UNAVAILABLE} rather than null, so call sites never need a null check.</p>
 *
 * <p><b>Why registration instead of inheritance.</b> {@code GlStateManager} is a static class holding static
 * state; it cannot be subclassed per backend. What it can do is forward to an instance that is chosen once.
 * This is the same shape used for the backend selection itself: decide early, then read a single field.</p>
 */
public final class RenderState {

    private static volatile GlStateView glState = GlStateView.UNAVAILABLE;
    private static volatile MatrixView matrices = MatrixView.UNAVAILABLE;
    private static volatile ChunkView chunks = ChunkView.UNAVAILABLE;

    private RenderState() {}

    public static GlStateView glState() {
        return glState;
    }

    public static MatrixView matrices() {
        return matrices;
    }

    public static ChunkView chunks() {
        return chunks;
    }

    /**
     * Installs the GL state view.
     *
     * @throws IllegalStateException if a different view is already installed; replacing one at runtime would
     *                               make the answer depend on timing
     */
    public static void installGlState(GlStateView view) {
        glState = install(glState, view, "GlStateView");
    }

    public static void installMatrices(MatrixView view) {
        matrices = install(matrices, view, "MatrixView");
    }

    public static void installChunks(ChunkView view) {
        chunks = install(chunks, view, "ChunkView");
    }

    private static <T> T install(T current, @Nullable T incoming, String what) {
        if (incoming == null) {
            throw new IllegalArgumentException(what + " must not be null");
        }
        if (current != incoming && !isUnavailable(current)) {
            throw new IllegalStateException(what + " is already installed; it cannot be replaced at runtime");
        }
        return incoming;
    }

    private static boolean isUnavailable(Object view) {
        return view == GlStateView.UNAVAILABLE || view == MatrixView.UNAVAILABLE || view == ChunkView.UNAVAILABLE;
    }
}
