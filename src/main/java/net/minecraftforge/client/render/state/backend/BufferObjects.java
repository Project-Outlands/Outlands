package net.minecraftforge.client.render.state.backend;

import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;

import net.minecraftforge.client.render.RenderBackendSelection;

import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Buffer objects by name, which is all a non-GL backend needs them to be.
 *
 * <p><b>Why a name-to-bytes map is the honest implementation.</b> A GL buffer object is a handle to storage that
 * lives on the device, and its contents are only ever read back through a vertex array pointer or a draw. Nothing
 * in the pack reads one back into client memory, so the storage this backend needs is the bytes: {@code glBufferData}
 * copies them in, and a client-array pointer with a buffer offset reads them out at draw time.</p>
 *
 * <p><b>What is deliberately absent.</b> No usage hints ({@code GL_STATIC_DRAW} and friends describe a driver's
 * caching decision, not a result), no mapping, no sub-uploads, no element buffers with contents - none of them were
 * measured in the pack. Adding a method here without a caller would be guessing at a shape.</p>
 */
public final class BufferObjects {

    /** {@code GL_ARRAY_BUFFER}: where vertex data is uploaded and read back from. */
    public static final int GL_ARRAY_BUFFER = 34962;
    /** {@code GL_ELEMENT_ARRAY_BUFFER}. */
    public static final int GL_ELEMENT_ARRAY_BUFFER = 34963;

    private static final BufferObjects INSTANCE = new BufferObjects();

    private final Int2ObjectMap<byte[]> data = new Int2ObjectLinkedOpenHashMap<>();
    private int nextName = 1;
    private int boundArray;
    private int boundElement;

    private BufferObjects() {}

    /** The instance, or {@code null} when the vanilla GL code path applies. */
    public static @Nullable BufferObjects forNonGl() {
        return RenderBackendSelection.glPath() ? null : INSTANCE;
    }

    /** The instance, for callers that have already established that the backend owns the frame. */
    public static BufferObjects instance() {
        return INSTANCE;
    }

    /** Reserves {@code range} consecutive names and returns the first, the way {@code glGenBuffers} does. */
    public synchronized int generate(int range) {
        if (range <= 0) {
            throw new IllegalArgumentException("a buffer name range must be positive, got " + range);
        }
        int base = nextName;
        nextName += range;
        for (int i = 0; i < range; i++) {
            data.put(base + i, new byte[0]);
        }
        return base;
    }

    /** Releases {@code range} names from {@code first}. Unknown names are ignored, as in GL. */
    public synchronized void delete(int first, int range) {
        for (int i = 0; i < range; i++) {
            int name = first + i;
            data.remove(name);
            if (boundArray == name) {
                boundArray = 0;
            }
            if (boundElement == name) {
                boundElement = 0;
            }
        }
    }

    /** Makes {@code name} the bound buffer for {@code target}, or unbinds for 0. */
    public synchronized void bind(int target, int name) {
        if (target == GL_ARRAY_BUFFER) {
            boundArray = name;
        } else if (target == GL_ELEMENT_ARRAY_BUFFER) {
            boundElement = name;
        }
    }

    /** The name bound to {@code target}, 0 when nothing is. */
    public synchronized int bound(int target) {
        return target == GL_ARRAY_BUFFER ? boundArray : target == GL_ELEMENT_ARRAY_BUFFER ? boundElement : 0;
    }

    /**
     * Replaces the bound buffer's contents with the bytes {@code source} currently spans.
     *
     * <p>The span is {@code [position, limit)}, which is GL's rule: the mod positions and limits its buffer before
     * uploading, and copying the whole capacity would upload whatever the previous draw left behind.</p>
     */
    public synchronized void data(int target, ByteBuffer source) {
        int name = bound(target);
        if (name == 0) {
            // GL's rule: with no buffer bound, glBufferData is a no-op (it would be GL_INVALID_OPERATION).
            return;
        }
        ByteBuffer view = source.duplicate();
        byte[] bytes = new byte[view.remaining()];
        view.get(bytes);
        data.put(name, bytes);
    }

    /** The contents of {@code name}, or {@code null} when the name is not a buffer. */
    public synchronized byte @Nullable [] bytes(int name) {
        return data.get(name);
    }

    /** A read-only view of a buffer's contents in native order, or {@code null}. */
    public synchronized @Nullable ByteBuffer view(int name) {
        byte[] bytes = data.get(name);
        return bytes == null ? null : ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
    }

    /** Drops every buffer, as a context loss would. */
    public synchronized void reset() {
        data.clear();
        nextName = 1;
        boundArray = 0;
        boundElement = 0;
    }
}
