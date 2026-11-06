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

    void depthFunc(int glComparison);

    void alphaFunc(int glComparison, float reference);

    void shadeModel(int glModel);

    void cullFace(int glFace);

    void colorMask(boolean red, boolean green, boolean blue, boolean alpha);

    void blendFunc(int glSourceFactor, int glDestinationFactor);

    void blendFuncSeparate(int glSourceRgb, int glDestinationRgb, int glSourceAlpha, int glDestinationAlpha);

    void blendEquation(int glMode);

    void logicOp(int glOpcode);

    void lineWidth(float width);

    void polygonMode(int glFace, int glMode);

    void polygonOffset(float factor, float units);

    void viewport(int x, int y, int width, int height);

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
