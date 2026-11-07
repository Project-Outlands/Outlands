package net.minecraftforge.client.render.graph;

import org.jspecify.annotations.Nullable;

/**
 * What a graph node is handed when it runs.
 *
 * <p>Deliberately small. A node gets the frame's size and the backend's own handle, and nothing else, because
 * everything else the phases will eventually want - GL state, matrices, chunk data, the original implementation -
 * does not exist yet. Adding a field here is a promise to fill it on every backend; inventing four of them now
 * would mean four fields that are null on the only backend that exists.</p>
 *
 * <p>{@link #nativeContext()} is untyped for the same reason it is on the backend: a Vulkan command buffer and a
 * GL context have nothing in common, and pretending they do produces an abstraction that fits neither.</p>
 */
public interface RenderGraphContext {

    /** Width of the frame in pixels. */
    int width();

    /** Height of the frame in pixels. */
    int height();

    /** The backend's own handle for the work in progress, or {@code null} when it has none. */
    @Nullable
    Object nativeContext();

    /**
     * Which frame in flight this recording belongs to.
     *
     * <p>A backend with more than one frame in flight has to know this to manage anything it hands to the GPU and
     * frees later: a buffer a copy shader reads is only safe to release once <i>this</i> frame's fence has been
     * waited on, and with two frames in flight "after the current frame" releases memory the other one is still
     * reading. The validation layers report that as a destroyed resource in use, which is how it was found.</p>
     *
     * <p>Zero for a backend that has one frame in flight, which is the only sensible answer there.</p>
     */
    default int frameSlot() {
        return 0;
    }
}
