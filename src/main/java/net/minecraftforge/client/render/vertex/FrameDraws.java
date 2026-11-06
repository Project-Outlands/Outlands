package net.minecraftforge.client.render.vertex;

import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * The draws a frame asked for, held until the frame is recorded.
 *
 * <p><b>Why a list and not a command buffer.</b> Vanilla issues its frame before {@code Display.update()} hands it
 * over, so its draws arrive while no command buffer is recording. Handing them a command buffer would mean
 * splitting the backend's frame in two; holding them as data means the existing record-and-submit path stays as it
 * is, and the draws are replayed inside the rendering instance it already opens.</p>
 *
 * <p><b>Order is preserved.</b> Vanilla draws in the order it always did, so the list replays into the same
 * picture - the only difference from a command buffer is that the work happens at the end of the frame instead of
 * during it.</p>
 */
public final class FrameDraws {

    /**
     * One recorded draw: its vertices plus the state it was issued with.
     *
     * <p>The state is part of the draw, not of the frame. Reading it at replay time would apply one matrix and one
     * set of enable bits to every draw in the frame, which is not what GL did.</p>
     */
    public record Draw(VertexLayout layout, int mode, int vertexCount, byte[] vertices, DrawState state) {}

    private static final List<Draw> DRAWS = new ArrayList<>();
    private static volatile boolean collecting;

    private FrameDraws() {}

    /** Starts collecting. Called by a backend that will replay the list later in the frame. */
    public static void beginCollecting() {
        synchronized (DRAWS) {
            DRAWS.clear();
        }
        collecting = true;
    }

    /** Stops collecting and returns what was recorded, in order. */
    public static List<Draw> drain() {
        collecting = false;
        synchronized (DRAWS) {
            List<Draw> copy = List.copyOf(DRAWS);
            DRAWS.clear();
            return copy;
        }
    }

    /** Whether a backend is collecting this frame. */
    public static boolean isCollecting() {
        return collecting;
    }

    /**
     * Adds one draw.
     *
     * @return {@code false} when nothing is collecting, which means the caller has no frame to record into
     */
    public static boolean record(VertexLayout layout, int mode, int vertexCount, ByteBuffer data, DrawState state) {
        if (!collecting) {
            return false;
        }
        int bytes = vertexCount * layout.stride();
        if (data.limit() < bytes) {
            throw new IllegalArgumentException("a draw of " + vertexCount + " vertices at stride " + layout.stride()
                    + " needs " + bytes + " bytes, the buffer has " + data.limit());
        }
        byte[] copy = new byte[bytes];
        ByteBuffer view = data.duplicate();
        view.position(0);
        view.get(copy, 0, bytes);
        synchronized (DRAWS) {
            DRAWS.add(new Draw(layout, mode, vertexCount, copy, state));
        }
        return true;
    }

    /** How many draws are waiting. Diagnostics. */
    public static int pending() {
        synchronized (DRAWS) {
            return DRAWS.size();
        }
    }
}
