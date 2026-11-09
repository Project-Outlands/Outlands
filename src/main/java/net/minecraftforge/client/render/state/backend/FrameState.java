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

    /**
     * Copies the matrix currently in effect for a GL matrix target into {@code out}, column-major, 16 floats.
     *
     * <p><b>Why this is a matrix method and not a state query.</b> {@code glGetFloatv} is the only state readback
     * vanilla performs at all - four call sites, {@code RenderChunk.initModelviewMatrix}, {@code ActiveRenderInfo},
     * {@code ClippingHelperImpl} and {@code TileEntityEndPortalRenderer} - and every one of them asks for
     * {@code GL_MODELVIEW_MATRIX} or {@code GL_PROJECTION_MATRIX}. So the readback is a matrix readback: the stack
     * this interface already keeps is the whole answer, with no GL query and no invented value. Anything else is a
     * gap this method names rather than a number this method guesses.</p>
     *
     * <p>Written from position 0 of {@code out} without moving its position, because {@code glGetFloatv} writes where
     * the buffer points rather than advancing it, and vanilla reuses these buffers across draws.</p>
     *
     * @param glMatrixTarget {@code GL_MODELVIEW_MATRIX} (2982), {@code GL_PROJECTION_MATRIX} (2983) or
     *                       {@code GL_TEXTURE_MATRIX} (2984)
     */
    void readMatrix(int glMatrixTarget, java.nio.FloatBuffer out);

    void depthFunc(int glComparison);

    void alphaFunc(int glComparison, float reference);

    /** Whether the alpha test is active, i.e. whether fragments below the reference are discarded. */
    boolean alphaTestEnabled();

    /**
     * The alpha test's reference value.
     *
     * <p>Read at draw time with {@link #alphaTestEnabled()} because it decides whether fragments exist at all. It is
     * not a cosmetic detail: without it a transparent texel still writes its colour when blending happens to be off,
     * which is how the main menu's wordmark turned into a black rectangle.</p>
     */
    float alphaReference();

    /**
     * The alpha test's comparison, as a GL enum.
     *
     * <p>Reported rather than interpreted so the backend can reject a comparison it does not implement instead of
     * quietly testing something else.</p>
     */
    int alphaComparison();

    // region fog
    //
    // GL's fog is a fragment-stage term computed from the eye-space distance and five pieces of state. The five setters
    // below are that state, kept here because vanilla sets them through GlStateManager's named setters - setFog,
    // setFogDensity, setFogStart, setFogEnd, and the raw glFog/glFogi pair EntityRenderer and the default profile call.
    //
    // They are recorded but not yet read: nothing in this backend computes the term, so the world currently renders
    // with distances unmodulated. That is a visible difference (the chunk edge is sharp where vanilla fades it into
    // the sky) and it is listed as such rather than being allowed to throw, because it changes what the picture looks
    // like and not whether the frame renders. The term is one `mix` in the fragment stage once these values travel
    // with the draw; the reason they are not already there is that it means growing the push-constant block, which is
    // a separate, checkable change.

    /** {@code glFogi(GL_FOG_MODE)}: {@code GL_EXP} (2048), {@code GL_EXP2} (2049) or {@code GL_LINEAR} (9729). */
    void fogMode(int glMode);

    /**
     * {@code glFogf}: one scalar fog value, named by {@code glPname}.
     *
     * <p>{@code GL_FOG_DENSITY} (2914), {@code GL_FOG_START} (2915) and {@code GL_FOG_END} (2916). The pname is carried
     * because that is the parameter vanilla's own setters pass, and because it keeps the case analysis in one place
     * instead of spread over three methods that differ only in which field they write.</p>
     */
    void fog(int glPname, float value);

    /**
     * {@code glFog}: the same values from a buffer, which is how {@code GL_FOG_COLOR} (2918) is set.
     *
     * <p>Vanilla fills a four-float buffer with the sky colour and hands it over once per frame, so this is the call
     * that decides what the world fades into.</p>
     */
    void fog(int glPname, java.nio.FloatBuffer param);

    /**
     * {@code glMultiTexCoord2f} on the lightmap unit: the light that geometry without a lightmap attribute gets.
     *
     * <p>Vanilla does not give entity and item models a lightmap attribute. It sets a constant texture coordinate on
     * the lightmap unit instead, and GL's combine stage reads that for the whole draw. So this is not decoration - it
     * is the only thing that makes a mob in a cave darker than the same mob in daylight.</p>
     */
    void lightmap(float u, float v);

    /**
     * The lightmap coordinate in effect, as {@code u, v} in 0..1, or {@code null} when the game has not set one.
     *
     * <p>Read when a draw is recorded, for the same reason the matrix is: the draw uses the value that was set when it
     * was issued.</p>
     */
    float[] lightmap();

    /**
     * The texture handle the game uploads its lightmap into.
     *
     * <p>Reported rather than discovered: the backend has to sample that image, and nothing about a 16x16 texture says
     * it is the lightmap. {@code 0} means the game has not created one yet.</p>
     */
    void lightmapTexture(int texture);

    /** The lightmap texture handle, or {@code 0}. */
    int lightmapTexture();

    /**
     * Intercepts fixed-function {@code glLight} for directional lights in eye space.
     */
    default void glLight(int light, int pname, java.nio.FloatBuffer params) {}

    /** Eye-space direction vector for light 0. */
    default float[] light0Eye() {
        return new float[] {0.1617F, 0.8085F, -0.5659F};
    }

    /** Eye-space direction vector for light 1. */
    default float[] light1Eye() {
        return new float[] {-0.1617F, 0.8085F, 0.5659F};
    }

    // endregion

    void shadeModel(int glModel);

    void cullFace(int glFace);

    void colorMask(boolean red, boolean green, boolean blue, boolean alpha);

    /**
     * The channels a draw may write, in {@code red, green, blue, alpha} order.
     *
     * <p>Vanilla turns alpha writing off for the surfaces it composes rather than draws - the main menu panorama and
     * its blur both do - and the framebuffer's alpha then stays at whatever the clear left. A draw that ignores the
     * mask writes alpha it was never meant to write, and every later {@code SRC_ALPHA} blend of that region reads it.
     * Measured on the main menu: masking alpha away left the copy's alpha at {@code ff}, ignoring the mask drove it
     * to {@code 00} over the seven blur layers, which made the blurred backdrop fully transparent.</p>
     */
    boolean[] colorMask();

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
