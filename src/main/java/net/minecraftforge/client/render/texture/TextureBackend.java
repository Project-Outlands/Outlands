package net.minecraftforge.client.render.texture;

import java.nio.IntBuffer;

/**
 * Texture object state, in the form a renderer needs it.
 *
 * <p><b>Why the pixel contract is ARGB ints.</b> Vanilla reaches {@code glTexImage2D} through exactly one
 * combination: {@code GL_BGRA} with {@code GL_UNSIGNED_INT_8_8_8_8_REV}, over an {@code IntBuffer}. On a
 * little-endian machine that pair is how GL is told "each int is {@code 0xAARRGGBB}, laid out so the four bytes of
 * the int are B, G, R, A in memory". The visual result is ordinary RGBA; the enum pair is a statement about byte
 * order, not about colour channels. So the interface takes the ints and says what they are, and the backend is not
 * asked to reproduce GL's two-enum grammar for the same fact.</p>
 *
 * <p><b>There is no GL implementation of this interface.</b> The GL path is {@code GlStateManager}'s own bodies,
 * left in place and still reachable; {@link TextureBackends#forNonGl()} returns {@code null} for it.</p>
 *
 * <p><b>Texture ids are ours, not GL's.</b> {@link #create()} hands out ids from the backend's own namespace. A
 * caller treats them as opaque handles, which is what GL names were anyway.</p>
 */
public interface TextureBackend {

    /** Creates an empty texture and returns its handle. */
    int create();

    /** Releases a texture. Safe for an unknown id. */
    void delete(int id);

    /**
     * Makes this texture the one a draw samples from.
     *
     * @param unit the texture unit, zero-based
     */
    void bind(int unit, int id);

    /**
     * Uploads a mip level, replacing its contents.
     *
     * @param pixels ARGB ints, rows from top to bottom, or {@code null} to allocate without data
     */
    void upload(int id, int level, int width, int height, IntBuffer pixels);

    /** Uploads into part of a mip level. */
    void subUpload(int id, int level, int xOffset, int yOffset, int width, int height, IntBuffer pixels);

    /** Records an integer sampler parameter (filter, wrap, mipmap range). */
    void parameter(int id, int pname, int value);

    /** Records a floating-point sampler parameter (anisotropy). */
    void parameter(int id, int pname, float value);

    /**
     * How many textures hold data that has not been turned into a device image yet.
     *
     * <p>Diagnostics, and the reason the deferred part of this path is visible rather than assumed: a texture that
     * is uploaded here and never sampled is indistinguishable from one that was dropped, unless the count is
     * observable.</p>
     */
    int pendingDeviceUploads();
}
