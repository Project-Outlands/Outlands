package net.minecraftforge.client.render.state.backend;

/**
 * Compiled draw lists, the way {@code GlStateManager} exposes them.
 *
 * <p><b>What a display list is.</b> A block of drawing commands, recorded once and replayed many times. Vanilla uses
 * three of them - the sky, the stars and the cloud plane - precisely because they are static geometry that would
 * otherwise be re-tessellated every frame.</p>
 *
 * <p><b>Why this is implementable before there is a frame.</b> Recording does not draw. The bytes that the
 * recording pass produces are captured instead of submitted, so a backend can build a list with no command buffer
 * and no swapchain. Only {@link #call} needs a frame, because only that actually draws.</p>
 *
 * <p><b>There is no GL implementation of this interface.</b> The GL path is {@code GlStateManager}'s own bodies.</p>
 */
public interface DisplayListBackend {

    /** Reserves {@code range} consecutive list names and returns the first. */
    int generate(int range);

    /**
     * Starts recording into a list.
     *
     * @param glMode a GL compile mode; {@code 4864} is {@code GL_COMPILE} and what vanilla uses
     */
    void begin(int id, int glMode);

    /** Stops recording. */
    void end();

    /** The list currently being recorded, or {@code null}. */
    Integer recording();

    /**
     * Replays a list.
     *
     * @throws UnsupportedOperationException when there is no frame to record the replay into, naming the list and
     *         how much it holds
     */
    void call(int id);

    /** Releases {@code range} consecutive lists starting at {@code id}. */
    void delete(int id, int range);

    /** How many draws one list holds. Diagnostics, and what {@link #call}'s failure message reports. */
    int drawCount(int id);
}
