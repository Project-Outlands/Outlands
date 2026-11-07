package net.minecraftforge.client.render.state.backend;

/**
 * Render state for one frame, in the terms vanilla sets it.
 *
 * <p><b>Method names are the operation, not the GL entry point.</b> {@link #depthFunc(int)} rather than
 * {@code glDepthFunc}. Where a parameter is still a GL enum, it is because that is the parameter type of
 * {@code GlStateManager}'s own public method - mods pass those ints - so it is vanilla's vocabulary rather than an
 * artefact of how GL happens to spell the call. Each implementation is expected to reject values it does not
 * understand instead of ignoring them.</p>
 *
 * <p><b>Matrices are part of the state, not of the caller.</b> GL keeps a per-mode stack that the draw call reads
 * implicitly; Vulkan's matrices are push-constant data, so a backend has to hold the stack itself and hand it over
 * at draw time. That is why the matrix methods are here rather than being left as CPU-only bookkeeping in
 * {@code GlStateManager}.</p>
 *
 * <p><b>Why {@link FloatBuffer} on {@link #multiply(FloatBuffer)} and nowhere else.</b> That method's parameter is
 * literally the caller's buffer - vanilla and mods build a {@code FloatBuffer} and hand it over - so it is passed
 * through rather than copied into a matrix type. Every other matrix method takes numbers, so no matrix type appears
 * in the signature. The implementation uses JOML internally, which is already the declared math library for the
 * render seams (see {@code net.minecraftforge.client.render.state.MatrixView}).</p>
 */
public interface FrameState {

    void capability(Capability capability, boolean enabled);

    /** Whether a capability is currently enabled. */
    boolean isEnabled(Capability capability);

    /** The current vertex colour, four components in 0..1. */
    float[] currentColor();

    /** The current viewport as {@code x, y, width, height}. */
    int[] viewport();

    /** Whether depth writes are enabled. */
    boolean depthWriteEnabled();

    /**
     * The projection matrix currently in effect.
     *
     * <p>Read at draw time because that is when GL read it too: the draw uses whatever the stack held when it was
     * issued, not what it held when the matrix was set.</p>
     */
    org.joml.Matrix4fc projection();

    /** The modelview matrix currently in effect. */
    org.joml.Matrix4fc modelView();

    void depthFunc(int glComparison);

    void alphaFunc(int glComparison, float reference);

    void shadeModel(int glModel);

    void cullFace(int glFace);

    void colorMask(boolean red, boolean green, boolean blue, boolean alpha);

    /** {@code glDepthMask}: whether depth writes are enabled. */
    void depthMask(boolean enabled);

    /**
     * The current vertex colour, which vanilla sets instead of passing a colour per vertex.
     *
     * <p>In a pipeline model this is per-draw data rather than pipeline state - it ends up in a push constant - but
     * it is set far more often than it is used, so it is tracked here and read when a draw happens.</p>
     */
    void color(float red, float green, float blue, float alpha);

    void blendFunc(int glSourceFactor, int glDestinationFactor);

    /**
     * The source and destination factors for RGB, as GL set them.
     *
     * <p>Read at draw time: blend factors are baked into a Vulkan pipeline, so they take part in the pipeline key
     * and have to travel with the draw rather than being read once per frame.</p>
     */
    int blendSource();

    int blendDestination();

    /**
     * The four separate factors, or {@code null} when the last call was the non-separate {@code blendFunc}.
     *
     * <p>Returning {@code null} rather than repeating the two shared factors keeps the distinction visible: the two
     * calls mean the same thing now, but only one of them says so explicitly, and a backend that later has to report
     * what the game actually asked for needs to be able to tell.</p>
     */
    int[] blendFactors();

    void blendFuncSeparate(int glSourceRgb, int glDestinationRgb, int glSourceAlpha, int glDestinationAlpha);

    void blendEquation(int glMode);

    void logicOp(int glOpcode);

    void lineWidth(float width);

    void polygonMode(int glFace, int glMode);

    void polygonOffset(float factor, float units);

    void viewport(int x, int y, int width, int height);

    /**
     * {@code glScissor}: the rectangle everything is clipped to while {@link Capability#SCISSOR_TEST} is on.
     *
     * <p>Part of the draw's state rather than of the frame's, because Vulkan has scissor as dynamic state that a
     * draw picks up and vanilla sets it around individual widgets - the mod list changes it per entry to clip a
     * scrolling name.</p>
     *
     * <p>Coordinates arrive in GL's convention, with the origin at the bottom-left, because that is what the game
     * computed. A backend whose origin is elsewhere converts it.</p>
     */
    void scissor(int x, int y, int width, int height);

    /** The current scissor rectangle as {@code x, y, width, height} in GL's bottom-up coordinates. */
    int[] scissorBox();

    void clearColor(float red, float green, float blue, float alpha);

    void clearDepth(double depth);

    /** Clears the buffers named by a GL clear mask. */
    void clear(int glMask);

    void matrixMode(int glMode);

    void loadIdentity();

    void pushMatrix();

    void popMatrix();

    /** {@code glMultMatrixf}: composes the given column-major matrix on the right. */
    void multiply(java.nio.FloatBuffer columnMajor16);

    void ortho(double left, double right, double bottom, double top, double zNear, double zFar);

    void translate(double x, double y, double z);

    void rotate(float angleDegrees, float x, float y, float z);

    /** {@code glScalef}. */
    void scale(float x, float y, float z);

    /**
     * {@code glScaled}.
     *
     * <p>A separate overload rather than a cast, because vanilla uses this one where the scale can be small enough
     * that single precision would shift geometry: {@code GlStateManager.scale(double, double, double)} exists
     * precisely to reach {@code glScaled}.</p>
     */
    void scale(double x, double y, double z);

    /** Attributes have no per-call state to save in a pipeline model; recording the depth keeps mismatches loud. */
    void pushAttributes();

    void popAttributes();
}
