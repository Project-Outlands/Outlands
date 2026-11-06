package net.minecraftforge.client.render.target;

/**
 * What the game has asked of the frame currently being built.
 *
 * <p><b>Why this is separate from the frame's command buffer.</b> Vanilla issues its frame in one order and the
 * backend consumes it in another: {@code runGameLoop} clears and binds a target long before {@code Display.update()}
 * hands the frame over. So the requests have to be held somewhere between the two. This is that place - it records
 * intent, not commands.</p>
 *
 * <p><b>Commands that cannot be held yet say so.</b> A draw is not intent: it needs a command buffer that is
 * recording, and this object deliberately has none. {@link #isTargetDefault()} exists so the code that eventually
 * records the frame can ask what was asked for instead of guessing.</p>
 */
public interface FrameRecording {

    /**
     * Makes the frame's own colour target current.
     *
     * <p>That is the window's image: in this backend the game's main framebuffer and the swapchain are the same
     * target, so binding it and unbinding it are the same request. Offscreen targets are not modelled yet and are
     * rejected by the implementation rather than silently redirected here.</p>
     */
    void targetDefault(int width, int height, boolean setViewport);

    /** Whether the frame has been told which target to draw into. */
    boolean isTargetDefault();

    void setClearColor(float red, float green, float blue, float alpha);

    void setClearDepth(double depth);

    /** Records that the game asked for a clear; the bits follow GL's convention. */
    void requestClear(int glMask);

    /** Pending clear colour, four components. */
    float[] pendingClearColor();

    /** Pending clear depth. */
    double pendingClearDepth();

    /** Whether a clear of the colour buffer was requested since the last frame was presented. */
    boolean wantsColorClear();

    /** Whether a clear of the depth buffer was requested. */
    boolean wantsDepthClear();

    /** Forgets the requests. Called by the backend once a frame has been presented. */
    void resetForNextFrame();
}
