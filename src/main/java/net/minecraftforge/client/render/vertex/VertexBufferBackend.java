package net.minecraftforge.client.render.vertex;

import java.nio.ByteBuffer;

import net.minecraft.client.renderer.vertex.VertexFormat;

/**
 * Where a {@code VertexBuffer} actually lives.
 *
 * <p><b>Why an interface and not a GL call site.</b> The methods here are named after what happens, not after the
 * GL entry point that happens to do it today. A backend that receives {@code upload(data)} can copy bytes into a
 * device-local buffer; a backend that received {@code glBufferData(GL_ARRAY_BUFFER, data, GL_STATIC_DRAW)} would
 * have to understand GL's target enums and usage hints first. The vocabulary of the contract decides whether the
 * implementation is a renderer or a GL emulator - see {@code docs/vulkan-backend-plan.md} §8.4.2.</p>
 *
 * <p><b>{@link #bind()} and {@link #unbind()} are kept although a non-GL backend has nothing to bind.</b> They are
 * part of the class's existing public surface, so callers keep calling them; a backend for which binding is
 * meaningless implements them as no-ops and says so. Removing them from the surface would be a source change to
 * every caller for no gain.</p>
 *
 * <p><b>The vertex format is fixed at construction and is part of the contract.</b> Vanilla's GL path gets the
 * layout implicitly, from whichever {@code glVertexPointer} calls ran last; Vulkan's vertex input is pipeline
 * state, so the layout has to be declared up front. Passing it in is what makes the two paths expressible with one
 * interface.</p>
 */
public interface VertexBufferBackend {

    /** Creates the storage. Called once, from the {@code VertexBuffer} constructor. */
    void create();

    /**
     * Makes this buffer the one a subsequent {@link #draw(int, int, int)} reads from.
     *
     * <p>A no-op where the draw call names the buffer directly.</p>
     */
    void bind();

    /** Uploads vertex data, replacing whatever was there. */
    void upload(ByteBuffer data);

    /**
     * Draws {@code count} vertices starting at {@code first}.
     *
     * @param mode a GL primitive constant, because that is what the caller has. Converting it to a topology is the
     *             backend's job: Vulkan has no quads, so a backend must split them itself rather than push the
     *             problem back up to callers that cannot express it.
     */
    void draw(int mode, int first, int count);

    /** Releases {@link #bind()}. A no-op where binding is meaningless. */
    void unbind();

    /** Releases the storage. Safe to call twice. */
    void delete();

    /** The layout this buffer holds. */
    VertexFormat format();

    /** Vertex count of the last {@link #upload(ByteBuffer)}. */
    int vertexCount();
}
