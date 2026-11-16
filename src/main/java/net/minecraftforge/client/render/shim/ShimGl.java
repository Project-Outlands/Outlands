package net.minecraftforge.client.render.shim;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

/**
 * The facade that {@link net.minecraftforge.lwjgl.shim.RetargetEntry} points GL call sites at.
 *
 * <p><b>Why this must be a dispatcher, not a direct implementation.</b> A rewritten call site cannot be
 * rewritten back: class bytes are transformed once, when the class loads. If this facade only ever called
 * into a Vulkan implementation, then a Vulkan backend that turns out to be unusable would leave every GL
 * call throwing, and the game would be unable to fall back. Keeping a real-GL implementation behind the same
 * facade means the worst case is a backend switch, not a dead process.</p>
 *
 * <p>The failure is contained either way — measured: a call site whose target class is missing still loads,
 * and only fails on first execution — so the switch can be made lazily.</p>
 *
 * <p><b>Cost.</b> Measured at about +0.5 ns per call versus calling {@code GL11} directly, which is the price
 * of one interface dispatch. With no ruleset selected the facade is never reached at all, because the call
 * sites are not rewritten.</p>
 *
 * <p><b>Why the signatures are GL's and not the backend's.</b> A retarget may change the owner of a call but
 * not its name or descriptor (see {@code RetargetEntry}), so these methods are fixed by the call sites rather
 * than by what would read well. Everything here returns {@code void} and takes only primitives, which is what
 * makes that constraint painless: no boxing, no argument recipes, no stack-map work.</p>
 *
 * <p><b>The set is measured, not guessed.</b> Eighteen of these methods are the entire raw-GL surface of
 * IC2Classic 1.12-1.5.8. Six more came from the rest of the pack: JEI's ghost-ingredient drag draws its line with
 * {@code glBegin}/{@code glVertex3f}/{@code glEnd} plus {@code glLineWidth} and {@code glHint}, and Baubles tints
 * its slot with {@code glColor3ub}. Immersive Engineering added its five queries, four raster/stencil entries and
 * one light parameter. The next mod that needs another one adds it here, in
 * {@code net.minecraftforge.lwjgl.shim.FrameShimRules}, and in {@link FrameShim}. {@code FrameShimRulesTest}
 * fails if those three disagree.</p>
 *
 * <p><b>Why ARB_shader_objects is here too.</b> A retarget is per call site, not per class: the entries carry the
 * owner they rewrite away from, so the same facade serves {@code GL11} and
 * {@code org.lwjgl.opengl.ARBShaderObjects}. That is what makes the shader block work at all - the mods that need
 * it (Immersive Engineering, immersivepetroleum, Immersive Intelligence) use the ARB names exclusively, and a
 * second facade would mean a second implementation of the same object model.</p>
 */
public final class ShimGl {

    private static final org.apache.logging.log4j.Logger LOGGER = org.apache.logging.log4j.LogManager.getLogger("Outlands|shim");

    /** {@code GL_FLOAT}, {@code GL_BYTE}, {@code GL_UNSIGNED_BYTE} and {@code GL_SHORT}: what the two-int client-array
     * overloads imply about their buffer, and what the real-GL bodies have to put back. */
    private static final int GL_BYTE = 5120;
    private static final int GL_UNSIGNED_BYTE = 5121;
    private static final int GL_SHORT = 5122;
    private static final int GL_FLOAT = 5126;

    /**
     * One implementation of the facade.
     *
     * <p>Every method has a do-nothing default, so an implementation only states what it actually answers. The two
     * real ones - {@link #REAL_GL} and {@code FrameShim} - override all of them, and {@link #CLOSED} overrides none,
     * which is exactly what "the renderer has closed" means.</p>
     */
    public interface Impl {
        default void glEnable(int cap) {}

        default void glDisable(int cap) {}

        default void glPushMatrix() {}

        default void glPopMatrix() {}

        default void glTranslatef(float x, float y, float z) {}

        default void glTranslated(double x, double y, double z) {}

        default void glScalef(float x, float y, float z) {}

        /** {@code glScaled}: the same scale, from doubles. CodeChicken's microblock renderers call this one. */
        default void glScaled(double x, double y, double z) {}

        default void glRotatef(float angle, float x, float y, float z) {}

        /** {@code glRotated}: the same rotation, from doubles. */
        default void glRotated(double angle, double x, double y, double z) {}

        default void glMatrixMode(int mode) {}

        default void glLoadIdentity() {}

        default void glOrtho(double left, double right, double bottom, double top, double zNear, double zFar) {}

        default void glAlphaFunc(int comparison, float reference) {}

        default void glDepthFunc(int comparison) {}

        default void glViewport(int x, int y, int width, int height) {}

        default void glClear(int mask) {}

        default void glColor4f(float red, float green, float blue, float alpha) {}

        /** {@code glColor3ub}: the byte form of the current colour, with an implied opaque alpha. */
        default void glColor3ub(byte red, byte green, byte blue) {}

        default void glNormal3f(float x, float y, float z) {}

        /** {@code glBegin}: opens the immediate-mode accumulator. */
        default void glBegin(int mode) {}

        /** {@code glEnd}: submits whatever the accumulator collected as one draw. */
        default void glEnd() {}

        /** {@code glVertex3f}: one vertex of the open immediate-mode primitive. */
        default void glVertex3f(float x, float y, float z) {}

        /** {@code glVertex2f}: the z is zero, which is where an orthographic GUI draws. */
        default void glVertex2f(float x, float y) {}

        /** {@code glVertex3d}: the double form of {@link #glVertex3f}. */
        default void glVertex3d(double x, double y, double z) {}

        /** {@code glVertex2i}: integer coordinates, which is how JEI's drag line and modernsplash's quads are built. */
        default void glVertex2i(int x, int y) {}

        /** {@code glTexCoord2f}: the texture coordinate of the vertices that follow. */
        default void glTexCoord2f(float u, float v) {}

        /** {@code glColor3f}: the float form of the current colour, with an implied opaque alpha. */
        default void glColor3f(float red, float green, float blue) {}

        /** {@code glColor4ub}: unsigned bytes, which is how a packed colour arrives from a mod's own packing. */
        default void glColor4ub(byte red, byte green, byte blue, byte alpha) {}

        /** {@code glColor4d}: the double form of {@link #glColor4f}. */
        default void glColor4d(double red, double green, double blue, double alpha) {}

        /** {@code glGenTextures}: a new texture handle. */
        default int glGenTextures() { return 0; }

        /** {@code glDeleteTextures}: releases a handle {@link #glGenTextures} handed out. */
        default void glDeleteTextures(int texture) {}

        /** {@code glBindTexture}: makes a texture the one the active unit samples. */
        default void glBindTexture(int target, int texture) {}

        /** {@code glTexParameteri}: integer sampler state (filter, wrap, mipmap range). */
        default void glTexParameteri(int target, int parameterName, int value) {}

        /** {@code glTexParameterf}: floating-point sampler state (anisotropy). */
        default void glTexParameterf(int target, int parameterName, float value) {}

        /** {@code glTexImage2D}: allocates or replaces a mip level from packed bytes. */
        default void glTexImage2D(int target, int level, int internalFormat, int width, int height, int border,
                                  int format, int type, java.nio.ByteBuffer pixels) {}

        /** {@code glTexSubImage2D}: replaces part of a mip level from packed bytes. */
        default void glTexSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height,
                                     int format, int type, java.nio.ByteBuffer pixels) {}

        /** {@code glPixelStorei}: the unpack/pack alignment, which this backend's byte contract does not have. */
        default void glPixelStorei(int parameterName, int value) {}

        /** {@code glGenerateMipmap}: builds the mip chain of the bound texture. */
        default void glGenerateMipmap(int target) {}

        // Fog. The values are the ones EntityRenderer already sets through GlStateManager's named setters; these are
        // the raw calls Thaumcraft 6 makes, and they were reaching real GL.

        /** {@code glFogi}: the fog mode, or {@code GL_NV_fog_distance}'s distance mode, by pname. */
        default void glFogi(int pname, int value) {}

        /** {@code glFogf}: one scalar fog value, by pname (density, start, end). */
        default void glFogf(int pname, float value) {}

        /** {@code glFog}: the fog colour, from a buffer. */
        default void glFog(int pname, java.nio.FloatBuffer params) {}

        /** {@code glDepthRange}: the mapping from clip depth to window depth. */
        default void glDepthRange(double near, double far) {}

        /** {@code glTexGeni}: a texture-coordinate generation mode. */
        default void glTexGeni(int coord, int pname, int value) {}

        /** {@code glTexGen}: the same, from a buffer (the plane coefficients). */
        default void glTexGen(int coord, int pname, java.nio.FloatBuffer params) {}

        /** {@code glColor3d}: the double form of {@link #glColor3f}, which TC6's tessellator uses. */
        default void glColor3d(double red, double green, double blue) {}

        /** {@code glNormal3d}: the double form of the current normal. */
        default void glNormal3d(double x, double y, double z) {}

        /** {@code glTexCoord2d}: the double form of the current texture coordinate. */
        default void glTexCoord2d(double u, double v) {}

        // Core GL 2.0 shader objects. The ARB block above answers the same object model under the extension's names;
        // these are the names a mod written against GL 2.0 core uses, and until now they reached real GL: measured
        // users are SmoothFont's font shader (about forty sites) and CodeChickenLib's ShaderProgram.

        default int glCreateProgram() { return 0; }

        default int glCreateShader(int type) { return 0; }

        default void glShaderSource(int shader, CharSequence source) {}

        default void glCompileShader(int shader) {}

        default void glAttachShader(int program, int shader) {}

        default void glLinkProgram(int program) {}

        default void glValidateProgram(int program) {}

        default void glUseProgram(int program) {}

        default void glDeleteProgram(int program) {}

        default void glDeleteShader(int shader) {}

        /** {@code glGetProgrami}: link status, attach counts and the like, by core enum. */
        default int glGetProgrami(int program, int parameterName) { return 0; }

        /** {@code glGetShaderi}: compile status and the like, by core enum. */
        default int glGetShaderi(int shader, int parameterName) { return 0; }

        default String glGetProgramInfoLog(int program, int maxLength) { return ""; }

        default String glGetShaderInfoLog(int shader, int maxLength) { return ""; }

        default int glGetUniformLocation(int program, CharSequence name) { return -1; }

        default void glUniform1i(int location, int value) {}

        default void glUniform1f(int location, float value) {}

        default void glUniform2f(int location, float v0, float v1) {}

        default void glUniform3f(int location, float v0, float v1, float v2) {}

        default void glUniform4f(int location, float v0, float v1, float v2, float v3) {}

        default void glUniform2i(int location, int v0, int v1) {}

        default void glUniform3i(int location, int v0, int v1, int v2) {}

        default void glUniform4i(int location, int v0, int v1, int v2, int v3) {}

        default void glUniformMatrix4(int location, boolean transpose, java.nio.FloatBuffer value) {}

        /** The three integer uniform forms the ARB block was missing; LibrarianLib's boolean-vector uniforms use them. */
        default void glUniform2iARB(int location, int v0, int v1) {}

        default void glUniform3iARB(int location, int v0, int v1, int v2) {}

        default void glUniform4iARB(int location, int v0, int v1, int v2, int v3) {}

        // Buffer objects by their GL 1.5 names. The same store the client-array path reads offsets out of.

        default int glGenBuffers() { return 0; }

        default void glBindBuffer(int target, int buffer) {}

        default void glBufferData(int target, java.nio.ByteBuffer data, int usage) {}

        default void glDeleteBuffers(int buffer) {}

        /** {@code glGetInteger} into an array: modularui's projection code reads four state values at once. */
        default void glGetInteger(int parameterName, java.nio.IntBuffer values) {}

        /** {@code glGetError}: there is no GL error queue behind this backend, so the answer is always NO_ERROR. */
        default int glGetError() { return 0; }

        /** {@code glGetString}: vendor, renderer and version strings. */
        default String glGetString(int name) { return null; }

        /** {@code glDrawBuffer}: which colour attachment a draw goes to. Only the default one is modelled. */
        default void glDrawBuffer(int mode) {}

        /** {@code glVertex2d}: the double form of {@link #glVertex2f}. */
        default void glVertex2d(double x, double y) {}

        /** {@code glFrustum}: the perspective block of the fixed-function projection. */
        default void glFrustum(double left, double right, double bottom, double top, double near, double far) {}

        /** {@code glClipPlane}: a user clip plane, which no pipeline stage of this backend has. */
        default void glClipPlane(int plane, java.nio.DoubleBuffer equation) {}

        /** {@code glFlush}: start the GL commands already issued. */
        default void glFlush() {}

        /** {@code glGetTexEnvi}/{@code glGetTexEnvf}: the fixed-function texture environment. */
        default int glGetTexEnvi(int target, int parameterName) { return 0; }

        default float glGetTexEnvf(int target, int parameterName) { return 0.0F; }

        /** {@code glGetTexParameteri}: a sampler parameter read back. */
        default int glGetTexParameteri(int target, int parameterName) { return 0; }

        /** {@code glGenTextures} into an array: several handles at once. */
        default void glGenTextures(java.nio.IntBuffer textures) {}

        default void glDeleteTextures(java.nio.IntBuffer textures) {}

        /** {@code glTexImage2D} from ints, which is the same byte contract the texture backend already speaks. */
        default void glTexImage2D(int target, int level, int internalFormat, int width, int height, int border,
                                  int format, int type, java.nio.IntBuffer pixels) {}

        default void glTexSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height,
                                     int format, int type, java.nio.IntBuffer pixels) {}

        /** {@code glTexImage1D}: one-dimensional textures, which this backend does not model. */
        default void glTexImage1D(int target, int level, int internalFormat, int width, int border, int format,
                                  int type, java.nio.FloatBuffer pixels) {}

        /** {@code glActiveTexture}: the unit the following binds and draws apply to. */
        default void glActiveTexture(int unit) {}

        /** {@code glBlendFuncSeparate}: separate factors for the alpha channel. */
        default void glBlendFuncSeparate(int sourceRgb, int destinationRgb, int sourceAlpha, int destinationAlpha) {}

        /** {@code glBlendEquationSeparate}: separate blend equations for colour and alpha. */
        default void glBlendEquationSeparate(int modeRgb, int modeAlpha) {}

        default void glDetachShader(int program, int shader) {}

        default String glGetActiveUniform(int program, int index, int maxLength) { return ""; }

        default int glGetActiveUniformType(int program, int index) { return 0; }

        default int glGetActiveUniformSize(int program, int index) { return 0; }

        default void glUniformMatrix2(int location, boolean transpose, java.nio.FloatBuffer value) {}

        default void glUniform2fARB(int location, float v0, float v1) {}

        default void glUniform4fARB(int location, float v0, float v1, float v2, float v3) {}

        // GL 3.0's framebuffer objects by their core names. Pregenerator's preview texture is the one caller, and the
        // answers are the ones OpenGlHelper's patch gives: a framebuffer a mod creates has no render target here.

        default int glGenFramebuffers() { return 0; }

        default void glBindFramebuffer(int target, int framebuffer) {}

        default void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {}

        default void glDeleteFramebuffers(int framebuffer) {}

        default void glLineWidth(float width) {}

        /** {@code glHint}: a rasterisation hint, which no pipeline has. */
        default void glHint(int target, int mode) {}

        /** {@code glIsEnabled}: whether a capability is currently on. */
        default boolean glIsEnabled(int cap) { return false; }

        /**
         * {@code glGetBoolean}: one boolean of state, by GL enum.
         *
         * <p>Separate from {@link #glIsEnabled} because the enums are not all capabilities: {@code GL_CULL_FACE}
         * is one, but the same call is also used for things like {@code GL_DEPTH_WRITEMASK}, which no
         * {@code glEnable} sets.</p>
         */
        default boolean glGetBoolean(int parameterName) { return false; }

        /** {@code glGetInteger}: one integer of state, by GL enum. */
        default int glGetInteger(int parameterName) { return 0; }

        /** {@code glGetFloat}: one float of state, by GL enum. */
        default float glGetFloat(int parameterName) { return 0.0F; }

        /** {@code glGetFloatv}: a float vector of state, written at the buffer's position without consuming it. */
        default void glGetFloat(int parameterName, java.nio.FloatBuffer out) {}

        /** {@code glLight}: one parameter of one fixed-function light. */
        default void glLight(int light, int parameterName, java.nio.FloatBuffer params) {}

        /** {@code glPointSize}: the rasterised diameter of a point primitive. */
        default void glPointSize(float size) {}

        /** {@code glStencilFunc}: the test a stencil comparison makes. */
        default void glStencilFunc(int comparison, int reference, int mask) {}

        /** {@code glStencilMask}: which stencil bits a write may change. */
        default void glStencilMask(int mask) {}

        /** {@code glStencilOp}: what a stencil test does on fail, on depth fail and on pass. */
        default void glStencilOp(int fail, int depthFail, int depthPass) {}

        default void glFinish() {}

        /** {@code glBlendFunc}. */
        default void glBlendFunc(int glSourceFactor, int glDestinationFactor) {}
        /** {@code glScissor}. */
        default void glScissor(int x, int y, int width, int height) {}
        /** {@code glDepthMask}. */
        default void glDepthMask(boolean enabled) {}
        /** {@code glShadeModel}. */
        default void glShadeModel(int glModel) {}
        /** {@code glClearColor}. */
        default void glClearColor(float red, float green, float blue, float alpha) {}
        /** {@code glColorMask}. */
        default void glColorMask(boolean red, boolean green, boolean blue, boolean alpha) {}
        /** {@code glPolygonMode}. */
        default void glPolygonMode(int glFace, int glMode) {}
        /** {@code glClearDepth}. */
        default void glClearDepth(double depth) {}
        /** {@code glLogicOp}. */
        default void glLogicOp(int glOpcode) {}
        /** {@code glTexEnvi}. */
        default void glTexEnvi(int target, int parameterName, int parameter) {}
        /** {@code glTexEnvf}. */
        default void glTexEnvf(int target, int parameterName, float parameter) {}
        /** {@code glTexEnv}. */
        default void glTexEnv(int target, int parameterName, java.nio.FloatBuffer parameters) {}
        /** {@code glLoadMatrix}. */
        default void glLoadMatrix(java.nio.FloatBuffer matrix) {}
        /** {@code glLoadMatrixf}. */
        default void glLoadMatrixf(java.nio.FloatBuffer matrix) {}
        /** {@code glMultMatrix}. */
        default void glMultMatrix(java.nio.FloatBuffer matrix) {}
        /** {@code glMultMatrixf}. */
        default void glMultMatrixf(java.nio.FloatBuffer matrix) {}
        /**
         * {@code glMultMatrix} with a double buffer.
         *
         * <p>Its own entry point rather than an overload of the float form, because a retarget may not change a
         * descriptor: the call site passes a {@code DoubleBuffer} and the facade has to accept one.</p>
         */
        default void glMultMatrix(java.nio.DoubleBuffer matrix) {}


        /** {@code glEnableClientState} (LWJGL 2 shape: {@code int array}). */
        default void glEnableClientState(int array) {}
        /** {@code glDisableClientState} (LWJGL 2 shape: {@code int array}). */
        default void glDisableClientState(int array) {}
        /** {@code glVertexPointer} (LWJGL 2 shape: {@code int size, int type, int stride, long offset}). */
        default void glVertexPointer(int size, int type, int stride, long offset) {}
        /** {@code glVertexPointer} (LWJGL 2 shape: {@code int size, int stride, java.nio.FloatBuffer buffer}). */
        default void glVertexPointer(int size, int stride, java.nio.FloatBuffer buffer) {}
        /** {@code glColorPointer} (LWJGL 2 shape: {@code int size, int type, int stride, long offset}). */
        default void glColorPointer(int size, int type, int stride, long offset) {}
        /** {@code glColorPointer} (LWJGL 2 shape: {@code int size, boolean normalized, int stride, java.nio.ByteBuffer buffer}). */
        default void glColorPointer(int size, boolean normalized, int stride, java.nio.ByteBuffer buffer) {}
        /** {@code glTexCoordPointer} (LWJGL 2 shape: {@code int size, int type, int stride, long offset}). */
        default void glTexCoordPointer(int size, int type, int stride, long offset) {}
        /** {@code glTexCoordPointer} (LWJGL 2 shape: {@code int size, int stride, java.nio.FloatBuffer buffer}). */
        default void glTexCoordPointer(int size, int stride, java.nio.FloatBuffer buffer) {}
        /** {@code glTexCoordPointer} (LWJGL 2 shape: {@code int size, int stride, java.nio.ShortBuffer buffer}). */
        default void glTexCoordPointer(int size, int stride, java.nio.ShortBuffer buffer) {}
        /** {@code glNormalPointer} (LWJGL 2 shape: {@code int type, int stride, long offset}). */
        default void glNormalPointer(int type, int stride, long offset) {}
        /**
         * {@code glNormalPointer} with a client-memory buffer.
         *
         * <p>LWJGL 2's two-argument form has no stride - the array is tightly packed - which is why this is not an
         * overload of the offset form above.</p>
         */
        default void glNormalPointer(int type, java.nio.ByteBuffer buffer) {}
        /** {@code glDrawArrays} (LWJGL 2 shape: {@code int mode, int first, int count}). */
        default void glDrawArrays(int mode, int first, int count) {}
        /** {@code glBindBufferARB} (LWJGL 2 shape: {@code int target, int buffer}). */
        default void glBindBufferARB(int target, int buffer) {}
        /** {@code glBufferDataARB} (LWJGL 2 shape: {@code int target, java.nio.ByteBuffer data, int usage}). */
        default void glBufferDataARB(int target, java.nio.ByteBuffer data, int usage) {}

        /** {@code glPushAttrib} (LWJGL 2 shape). */
        default void glPushAttrib(int mask) {}
        /** {@code glPopAttrib} (LWJGL 2 shape). */
        default void glPopAttrib() {}
        /** {@code glClearStencil} (LWJGL 2 shape). */
        default void glClearStencil(int value) {}
        /** {@code glReadPixels} (LWJGL 2 shape). */
        default void glReadPixels(int x, int y, int width, int height, int format, int type, java.nio.ByteBuffer pixels) {}
        /** {@code glReadPixels} (LWJGL 2 shape). */
        default void glReadPixels(int x, int y, int width, int height, int format, int type, java.nio.FloatBuffer pixels) {}
        /** {@code glReadPixels} (LWJGL 2 shape). */
        default void glReadPixels(int x, int y, int width, int height, int format, int type, java.nio.IntBuffer pixels) {}
        /** {@code glGetTexImage} (LWJGL 2 shape). */
        default void glGetTexImage(int target, int level, int format, int type, java.nio.ByteBuffer pixels) {}
        /** {@code glGetTexImage} (LWJGL 2 shape). */
        default void glGetTexImage(int target, int level, int format, int type, java.nio.IntBuffer pixels) {}
        /** {@code glReadBuffer}: which attachment a subsequent {@code glReadPixels} reads from. */
        default void glReadBuffer(int mode) {}

        /** {@code glBlitFramebuffer}. */
        default void glBlitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0,
                                       int dstX1, int dstY1, int mask, int filter) {}

        /** {@code glGetTexLevelParameteri}: {@code GL_TEXTURE_WIDTH}, {@code GL_TEXTURE_HEIGHT} and friends. */
        default int glGetTexLevelParameteri(int target, int level, int parameterName) { return 0; }

        /** {@code glCopyTexImage2D}. */
        default void glCopyTexImage2D(int target, int level, int internalFormat, int x, int y, int width, int height,
                                      int border) {}
        /**
         * {@code glBlendColor}: the constant colour the {@code GL_CONSTANT_*} blend factors read.
         *
         * <p>In GL 1.2's extension it was {@code GL14.glBlendColor}, and that is the class the call sites name - it
         * is also what vanilla's {@code GlStateManager.blendColor} calls, so one entry covers both.</p>
         */
        default void glBlendColor(float red, float green, float blue, float alpha) {}

        /**
         * {@code glNewList}: starts recording drawing commands into a list.
         *
         * @param glMode {@code GL_COMPILE} (4864); {@code GL_COMPILE_AND_EXECUTE} is not supported
         */
        default void glNewList(int list, int glMode) {}

        /** {@code glEndList}: stops recording. */
        default void glEndList() {}

        /** {@code glCallList}: replays a recorded list. */
        default void glCallList(int list) {}

        /**
         * {@code glLineStipple}: a fixed-function dash pattern for lines.
         *
         * <p>No pipeline equivalent, and not something this backend can imitate: the pattern is applied by the GL
         * rasteriser to line primitives, and lines here are expanded into triangles before anything could stipple
         * them. Reported once rather than silently reaching a GL context that does not exist.</p>
         */
        default void glLineStipple(int factor, short pattern) {}

        // region ARB_shader_objects
        // The same object model as the core entry points OpenGlHelper uses, under the names the ARB extension gave
        // it. A mod that was written against GL 2.0 calls these and nothing else, so without them its shaders never
        // reach the backend at all - which is what Immersive Engineering, immersivepetroleum and Immersive
        // Intelligence all do, in the same order, measured from their constant pools.

        /** {@code glCreateProgramObjectARB}: a new program name. */
        default int glCreateProgramObjectARB() { return 0; }

        /** {@code glCreateShaderObjectARB}: a new stage, {@code GL_VERTEX_SHADER_ARB} or {@code GL_FRAGMENT_SHADER_ARB}. */
        default int glCreateShaderObjectARB(int type) { return 0; }

        /** {@code glShaderSourceARB}: replaces a stage's source. */
        default void glShaderSourceARB(int shader, CharSequence source) {}

        /** {@code glCompileShaderARB}: compiles a stage, recording the outcome rather than throwing. */
        default void glCompileShaderARB(int shader) {}

        /** {@code glAttachObjectARB}: adds a compiled stage to a program. */
        default void glAttachObjectARB(int program, int shader) {}

        /** {@code glLinkProgramARB}: links a program, recording the outcome. */
        default void glLinkProgramARB(int program) {}

        /** {@code glValidateProgramARB}: asks whether the program would run in the current state. */
        default void glValidateProgramARB(int program) {}

        /** {@code glUseProgramObjectARB}: makes a program current, or none for 0. */
        default void glUseProgramObjectARB(int program) {}

        /** {@code glDeleteObjectARB}: releases a stage or a program, whichever the name belongs to. */
        default void glDeleteObjectARB(int object) {}

        /** {@code glGetObjectParameteriARB}: one integer about a stage or a program, by GL enum. */
        default int glGetObjectParameteriARB(int object, int parameterName) { return 0; }

        /** {@code glGetInfoLogARB}: the compile or link log, or the empty string. */
        default String glGetInfoLogARB(int object, int maxLength) { return ""; }

        /** {@code glGetUniformLocationARB}: the location a uniform name resolves to, or -1. */
        default int glGetUniformLocationARB(int program, CharSequence name) { return 0; }

        /** {@code glUniform1iARB}. */
        default void glUniform1iARB(int location, int value) {}

        /** {@code glUniform1fARB}. */
        default void glUniform1fARB(int location, float value) {}

        /** {@code glUniform3fARB}. */
        default void glUniform3fARB(int location, float x, float y, float z) {}
        // endregion
    }

    /** Passes straight through to real OpenGL. Always available. */
    public static final Impl REAL_GL = new Impl() {
        @Override
        public void glEnable(int cap) {
            GL11.glEnable(cap);
        }

        @Override
        public void glDisable(int cap) {
            GL11.glDisable(cap);
        }

        @Override
        public void glPushMatrix() {
            GL11.glPushMatrix();
        }

        @Override
        public void glPopMatrix() {
            GL11.glPopMatrix();
        }

        @Override
        public void glTranslatef(float x, float y, float z) {
            GL11.glTranslatef(x, y, z);
        }

        @Override
        public void glTranslated(double x, double y, double z) {
            GL11.glTranslated(x, y, z);
        }

        @Override
        public void glScalef(float x, float y, float z) {
            GL11.glScalef(x, y, z);
        }

        @Override
        public void glScaled(double x, double y, double z) {
            GL11.glScaled(x, y, z);
        }

        @Override
        public void glRotated(double angle, double x, double y, double z) {
            GL11.glRotated(angle, x, y, z);
        }

        @Override
        public void glVertex2f(float x, float y) {
            GL11.glVertex2f(x, y);
        }

        @Override
        public void glVertex3d(double x, double y, double z) {
            GL11.glVertex3d(x, y, z);
        }

        @Override
        public void glVertex2i(int x, int y) {
            GL11.glVertex2i(x, y);
        }

        @Override
        public void glTexCoord2f(float u, float v) {
            GL11.glTexCoord2f(u, v);
        }

        @Override
        public void glColor3f(float red, float green, float blue) {
            GL11.glColor3f(red, green, blue);
        }

        @Override
        public void glColor4ub(byte red, byte green, byte blue, byte alpha) {
            GL11.glColor4ub(red, green, blue, alpha);
        }

        @Override
        public void glColor4d(double red, double green, double blue, double alpha) {
            GL11.glColor4d(red, green, blue, alpha);
        }

        @Override
        public int glGenTextures() {
            return GL11.glGenTextures();
        }

        @Override
        public void glDeleteTextures(int texture) {
            GL11.glDeleteTextures(texture);
        }

        @Override
        public void glBindTexture(int target, int texture) {
            GL11.glBindTexture(target, texture);
        }

        @Override
        public void glTexParameteri(int target, int parameterName, int value) {
            GL11.glTexParameteri(target, parameterName, value);
        }

        @Override
        public void glTexParameterf(int target, int parameterName, float value) {
            GL11.glTexParameterf(target, parameterName, value);
        }

        @Override
        public void glTexImage2D(int target, int level, int internalFormat, int width, int height, int border,
                                 int format, int type, java.nio.ByteBuffer pixels) {
            GL11.glTexImage2D(target, level, internalFormat, width, height, border, format, type, pixels);
        }

        @Override
        public void glTexSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height,
                                    int format, int type, java.nio.ByteBuffer pixels) {
            GL11.glTexSubImage2D(target, level, xOffset, yOffset, width, height, format, type, pixels);
        }

        @Override
        public void glPixelStorei(int parameterName, int value) {
            GL11.glPixelStorei(parameterName, value);
        }

        @Override
        public void glGenerateMipmap(int target) {
            org.lwjgl.opengl.GL30.glGenerateMipmap(target);
        }

        @Override
        public void glFogi(int pname, int value) {
            GL11.glFogi(pname, value);
        }

        @Override
        public void glFogf(int pname, float value) {
            GL11.glFogf(pname, value);
        }

        @Override
        public void glFog(int pname, java.nio.FloatBuffer params) {
            GL11.glFogfv(pname, params);
        }

        @Override
        public void glDepthRange(double near, double far) {
            GL11.glDepthRange(near, far);
        }

        @Override
        public void glTexGeni(int coord, int pname, int value) {
            GL11.glTexGeni(coord, pname, value);
        }

        @Override
        public void glTexGen(int coord, int pname, java.nio.FloatBuffer params) {
            GL11.glTexGenfv(coord, pname, params);
        }

        @Override
        public void glColor3d(double red, double green, double blue) {
            GL11.glColor3d(red, green, blue);
        }

        @Override
        public void glNormal3d(double x, double y, double z) {
            GL11.glNormal3d(x, y, z);
        }

        @Override
        public void glTexCoord2d(double u, double v) {
            GL11.glTexCoord2d(u, v);
        }

        @Override
        public int glCreateProgram() {
            return org.lwjgl.opengl.GL20.glCreateProgram();
        }

        @Override
        public int glCreateShader(int type) {
            return org.lwjgl.opengl.GL20.glCreateShader(type);
        }

        @Override
        public void glShaderSource(int shader, CharSequence source) {
            org.lwjgl.opengl.GL20.glShaderSource(shader, source);
        }

        @Override
        public void glCompileShader(int shader) {
            org.lwjgl.opengl.GL20.glCompileShader(shader);
        }

        @Override
        public void glAttachShader(int program, int shader) {
            org.lwjgl.opengl.GL20.glAttachShader(program, shader);
        }

        @Override
        public void glLinkProgram(int program) {
            org.lwjgl.opengl.GL20.glLinkProgram(program);
        }

        @Override
        public void glValidateProgram(int program) {
            org.lwjgl.opengl.GL20.glValidateProgram(program);
        }

        @Override
        public void glUseProgram(int program) {
            org.lwjgl.opengl.GL20.glUseProgram(program);
        }

        @Override
        public void glDeleteProgram(int program) {
            org.lwjgl.opengl.GL20.glDeleteProgram(program);
        }

        @Override
        public void glDeleteShader(int shader) {
            org.lwjgl.opengl.GL20.glDeleteShader(shader);
        }

        @Override
        public int glGetProgrami(int program, int parameterName) {
            return org.lwjgl.opengl.GL20.glGetProgrami(program, parameterName);
        }

        @Override
        public int glGetShaderi(int shader, int parameterName) {
            return org.lwjgl.opengl.GL20.glGetShaderi(shader, parameterName);
        }

        @Override
        public String glGetProgramInfoLog(int program, int maxLength) {
            return org.lwjgl.opengl.GL20.glGetProgramInfoLog(program, maxLength);
        }

        @Override
        public String glGetShaderInfoLog(int shader, int maxLength) {
            return org.lwjgl.opengl.GL20.glGetShaderInfoLog(shader, maxLength);
        }

        @Override
        public int glGetUniformLocation(int program, CharSequence name) {
            return org.lwjgl.opengl.GL20.glGetUniformLocation(program, name);
        }

        @Override
        public void glUniform1i(int location, int value) {
            org.lwjgl.opengl.GL20.glUniform1i(location, value);
        }

        @Override
        public void glUniform1f(int location, float value) {
            org.lwjgl.opengl.GL20.glUniform1f(location, value);
        }

        @Override
        public void glUniform2f(int location, float v0, float v1) {
            org.lwjgl.opengl.GL20.glUniform2f(location, v0, v1);
        }

        @Override
        public void glUniform3f(int location, float v0, float v1, float v2) {
            org.lwjgl.opengl.GL20.glUniform3f(location, v0, v1, v2);
        }

        @Override
        public void glUniform4f(int location, float v0, float v1, float v2, float v3) {
            org.lwjgl.opengl.GL20.glUniform4f(location, v0, v1, v2, v3);
        }

        @Override
        public void glUniform2i(int location, int v0, int v1) {
            org.lwjgl.opengl.GL20.glUniform2i(location, v0, v1);
        }

        @Override
        public void glUniform3i(int location, int v0, int v1, int v2) {
            org.lwjgl.opengl.GL20.glUniform3i(location, v0, v1, v2);
        }

        @Override
        public void glUniform4i(int location, int v0, int v1, int v2, int v3) {
            org.lwjgl.opengl.GL20.glUniform4i(location, v0, v1, v2, v3);
        }

        @Override
        public void glUniformMatrix4(int location, boolean transpose, java.nio.FloatBuffer value) {
            org.lwjgl.opengl.GL20.glUniformMatrix4fv(location, transpose, value);
        }

        @Override
        public void glUniform2iARB(int location, int v0, int v1) {
            org.lwjgl.opengl.ARBShaderObjects.glUniform2iARB(location, v0, v1);
        }

        @Override
        public void glUniform3iARB(int location, int v0, int v1, int v2) {
            org.lwjgl.opengl.ARBShaderObjects.glUniform3iARB(location, v0, v1, v2);
        }

        @Override
        public void glUniform4iARB(int location, int v0, int v1, int v2, int v3) {
            org.lwjgl.opengl.ARBShaderObjects.glUniform4iARB(location, v0, v1, v2, v3);
        }

        @Override
        public int glGenBuffers() {
            return GL15.glGenBuffers();
        }

        @Override
        public void glBindBuffer(int target, int buffer) {
            GL15.glBindBuffer(target, buffer);
        }

        @Override
        public void glBufferData(int target, java.nio.ByteBuffer data, int usage) {
            GL15.glBufferData(target, data, usage);
        }

        @Override
        public void glDeleteBuffers(int buffer) {
            GL15.glDeleteBuffers(buffer);
        }

        @Override
        public void glGetInteger(int parameterName, java.nio.IntBuffer values) {
            GL11.glGetIntegerv(parameterName, values);
        }

        @Override
        public int glGetError() {
            return GL11.glGetError();
        }

        @Override
        public String glGetString(int name) {
            return GL11.glGetString(name);
        }

        @Override
        public void glDrawBuffer(int mode) {
            GL11.glDrawBuffer(mode);
        }

        @Override
        public void glVertex2d(double x, double y) {
            GL11.glVertex2d(x, y);
        }

        @Override
        public void glFrustum(double left, double right, double bottom, double top, double near, double far) {
            GL11.glFrustum(left, right, bottom, top, near, far);
        }

        @Override
        public void glClipPlane(int plane, java.nio.DoubleBuffer equation) {
            GL11.glClipPlane(plane, equation);
        }

        @Override
        public void glFlush() {
            GL11.glFlush();
        }

        @Override
        public int glGetTexEnvi(int target, int parameterName) {
            return GL11.glGetTexEnvi(target, parameterName);
        }

        @Override
        public float glGetTexEnvf(int target, int parameterName) {
            return GL11.glGetTexEnvf(target, parameterName);
        }

        @Override
        public int glGetTexParameteri(int target, int parameterName) {
            return GL11.glGetTexParameteri(target, parameterName);
        }

        @Override
        public void glGenTextures(java.nio.IntBuffer textures) {
            GL11.glGenTextures(textures);
        }

        @Override
        public void glDeleteTextures(java.nio.IntBuffer textures) {
            GL11.glDeleteTextures(textures);
        }

        @Override
        public void glTexImage2D(int target, int level, int internalFormat, int width, int height, int border,
                                 int format, int type, java.nio.IntBuffer pixels) {
            GL11.glTexImage2D(target, level, internalFormat, width, height, border, format, type, pixels);
        }

        @Override
        public void glTexSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height,
                                    int format, int type, java.nio.IntBuffer pixels) {
            GL11.glTexSubImage2D(target, level, xOffset, yOffset, width, height, format, type, pixels);
        }

        @Override
        public void glTexImage1D(int target, int level, int internalFormat, int width, int border, int format,
                                 int type, java.nio.FloatBuffer pixels) {
            GL11.glTexImage1D(target, level, internalFormat, width, border, format, type, pixels);
        }

        @Override
        public void glActiveTexture(int unit) {
            org.lwjgl.opengl.GL13.glActiveTexture(unit);
        }

        @Override
        public void glBlendFuncSeparate(int sourceRgb, int destinationRgb, int sourceAlpha, int destinationAlpha) {
            org.lwjgl.opengl.GL14.glBlendFuncSeparate(sourceRgb, destinationRgb, sourceAlpha, destinationAlpha);
        }

        @Override
        public void glBlendEquationSeparate(int modeRgb, int modeAlpha) {
            org.lwjgl.opengl.GL20.glBlendEquationSeparate(modeRgb, modeAlpha);
        }

        @Override
        public void glDetachShader(int program, int shader) {
            org.lwjgl.opengl.GL20.glDetachShader(program, shader);
        }

        @Override
        public String glGetActiveUniform(int program, int index, int maxLength) {
            // LWJGL 3's form takes the size and type outputs alongside the name; the LWJGL 2 call the mods make
            // returns the name alone, so they go into throwaway buffers.
            return org.lwjgl.opengl.GL20.glGetActiveUniform(program, index, maxLength,
                    java.nio.IntBuffer.allocate(1), java.nio.IntBuffer.allocate(1));
        }

        @Override
        public int glGetActiveUniformType(int program, int index) {
            // LWJGL 2 answered this with a name of its own; LWJGL 3 only has the GL 3.1 query behind it.
            return org.lwjgl.opengl.GL31.glGetActiveUniformsi(program, index, 0x8B86);
        }

        @Override
        public int glGetActiveUniformSize(int program, int index) {
            return org.lwjgl.opengl.GL31.glGetActiveUniformsi(program, index, 0x8B87);
        }

        @Override
        public void glUniformMatrix2(int location, boolean transpose, java.nio.FloatBuffer value) {
            org.lwjgl.opengl.GL20.glUniformMatrix2fv(location, transpose, value);
        }

        @Override
        public void glUniform2fARB(int location, float v0, float v1) {
            org.lwjgl.opengl.ARBShaderObjects.glUniform2fARB(location, v0, v1);
        }

        @Override
        public void glUniform4fARB(int location, float v0, float v1, float v2, float v3) {
            org.lwjgl.opengl.ARBShaderObjects.glUniform4fARB(location, v0, v1, v2, v3);
        }

        @Override
        public int glGenFramebuffers() {
            return org.lwjgl.opengl.GL30.glGenFramebuffers();
        }

        @Override
        public void glBindFramebuffer(int target, int framebuffer) {
            org.lwjgl.opengl.GL30.glBindFramebuffer(target, framebuffer);
        }

        @Override
        public void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
            org.lwjgl.opengl.GL30.glFramebufferTexture2D(target, attachment, textarget, texture, level);
        }

        @Override
        public void glDeleteFramebuffers(int framebuffer) {
            org.lwjgl.opengl.GL30.glDeleteFramebuffers(framebuffer);
        }

        @Override
        public void glRotatef(float angle, float x, float y, float z) {
            GL11.glRotatef(angle, x, y, z);
        }

        @Override
        public void glMatrixMode(int mode) {
            GL11.glMatrixMode(mode);
        }

        @Override
        public void glLoadIdentity() {
            GL11.glLoadIdentity();
        }

        @Override
        public void glOrtho(double left, double right, double bottom, double top, double zNear, double zFar) {
            GL11.glOrtho(left, right, bottom, top, zNear, zFar);
        }

        @Override
        public void glAlphaFunc(int comparison, float reference) {
            GL11.glAlphaFunc(comparison, reference);
        }

        @Override
        public void glDepthFunc(int comparison) {
            GL11.glDepthFunc(comparison);
        }

        @Override
        public void glViewport(int x, int y, int width, int height) {
            GL11.glViewport(x, y, width, height);
        }

        @Override
        public void glClear(int mask) {
            GL11.glClear(mask);
        }

        @Override
        public void glColor4f(float red, float green, float blue, float alpha) {
            GL11.glColor4f(red, green, blue, alpha);
        }

        @Override
        public void glColor3ub(byte red, byte green, byte blue) {
            GL11.glColor3ub(red, green, blue);
        }

        @Override
        public void glNormal3f(float x, float y, float z) {
            GL11.glNormal3f(x, y, z);
        }

        @Override
        public void glBegin(int mode) {
            GL11.glBegin(mode);
        }

        @Override
        public void glEnd() {
            GL11.glEnd();
        }

        @Override
        public void glVertex3f(float x, float y, float z) {
            GL11.glVertex3f(x, y, z);
        }

        @Override
        public void glLineWidth(float width) {
            GL11.glLineWidth(width);
        }

        @Override
        public void glHint(int target, int mode) {
            GL11.glHint(target, mode);
        }

        @Override
        public boolean glIsEnabled(int cap) {
            return GL11.glIsEnabled(cap);
        }

        @Override
        public int glGetInteger(int parameterName) {
            // LWJGL 3 has only the vector form; the single-value one was an LWJGL 2 convenience.
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                java.nio.IntBuffer out = stack.callocInt(16);
                GL11.glGetIntegerv(parameterName, out);
                return out.get(0);
            }
        }

        @Override
        public float glGetFloat(int parameterName) {
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                java.nio.FloatBuffer out = stack.callocFloat(16);
                GL11.glGetFloatv(parameterName, out);
                return out.get(0);
            }
        }

        @Override
        public void glGetFloat(int parameterName, java.nio.FloatBuffer out) {
            GL11.glGetFloatv(parameterName, out);
        }

        @Override
        public void glLight(int light, int parameterName, java.nio.FloatBuffer params) {
            // LWJGL 3 dropped the non-v form; the vector one is the same call.
            GL11.glLightfv(light, parameterName, params);
        }

        @Override
        public void glPointSize(float size) {
            GL11.glPointSize(size);
        }

        @Override
        public void glStencilFunc(int comparison, int reference, int mask) {
            GL11.glStencilFunc(comparison, reference, mask);
        }

        @Override
        public void glStencilMask(int mask) {
            GL11.glStencilMask(mask);
        }

        @Override
        public void glStencilOp(int fail, int depthFail, int depthPass) {
            GL11.glStencilOp(fail, depthFail, depthPass);
        }


        @Override
        public void glBlendFunc(int glSourceFactor, int glDestinationFactor) {
            GL11.glBlendFunc(glSourceFactor, glDestinationFactor);
        }
        @Override
        public void glScissor(int x, int y, int width, int height) {
            GL11.glScissor(x, y, width, height);
        }
        @Override
        public void glDepthMask(boolean enabled) {
            GL11.glDepthMask(enabled);
        }
        @Override
        public void glShadeModel(int glModel) {
            GL11.glShadeModel(glModel);
        }
        @Override
        public void glClearColor(float red, float green, float blue, float alpha) {
            GL11.glClearColor(red, green, blue, alpha);
        }
        @Override
        public void glColorMask(boolean red, boolean green, boolean blue, boolean alpha) {
            GL11.glColorMask(red, green, blue, alpha);
        }
        @Override
        public void glPolygonMode(int glFace, int glMode) {
            GL11.glPolygonMode(glFace, glMode);
        }
        @Override
        public void glClearDepth(double depth) {
            GL11.glClearDepth(depth);
        }
        @Override
        public void glLogicOp(int glOpcode) {
            GL11.glLogicOp(glOpcode);
        }
        @Override
        public void glTexEnvi(int target, int parameterName, int parameter) {
            GL11.glTexEnvi(target, parameterName, parameter);
        }
        @Override
        public void glTexEnvf(int target, int parameterName, float parameter) {
            GL11.glTexEnvf(target, parameterName, parameter);
        }
        @Override
        public void glTexEnv(int target, int parameterName, java.nio.FloatBuffer parameters) {
            GL11.glTexEnvfv(target, parameterName, parameters);
        }
        @Override
        public void glLoadMatrix(java.nio.FloatBuffer matrix) {
            GL11.glLoadMatrixf(matrix);
        }
        @Override
        public void glLoadMatrixf(java.nio.FloatBuffer matrix) {
            GL11.glLoadMatrixf(matrix);
        }
        @Override
        public void glMultMatrix(java.nio.FloatBuffer matrix) {
            GL11.glMultMatrixf(matrix);
        }
        @Override
        public void glMultMatrixf(java.nio.FloatBuffer matrix) {
            GL11.glMultMatrixf(matrix);
        }
        @Override
        public void glMultMatrix(java.nio.DoubleBuffer matrix) {
            GL11.glMultMatrixd(matrix);
        }



        @Override
        public void glBlendColor(float red, float green, float blue, float alpha) {
            org.lwjgl.opengl.GL14.glBlendColor(red, green, blue, alpha);
        }

        @Override
        public void glPushAttrib(int mask) {
            GL11.glPushAttrib(mask);
        }
        @Override
        public void glPopAttrib() {
            GL11.glPopAttrib();
        }
        @Override
        public void glClearStencil(int value) {
            GL11.glClearStencil(value);
        }
        @Override
        public void glReadPixels(int x, int y, int width, int height, int format, int type, java.nio.ByteBuffer pixels) {
            GL11.glReadPixels(x, y, width, height, format, type, pixels);
        }
        @Override
        public void glReadPixels(int x, int y, int width, int height, int format, int type, java.nio.FloatBuffer pixels) {
            GL11.glReadPixels(x, y, width, height, format, type, pixels);
        }
        @Override
        public void glReadPixels(int x, int y, int width, int height, int format, int type, java.nio.IntBuffer pixels) {
            GL11.glReadPixels(x, y, width, height, format, type, pixels);
        }
        @Override
        public void glGetTexImage(int target, int level, int format, int type, java.nio.ByteBuffer pixels) {
            GL11.glGetTexImage(target, level, format, type, pixels);
        }
        @Override
        public void glGetTexImage(int target, int level, int format, int type, java.nio.IntBuffer pixels) {
            GL11.glGetTexImage(target, level, format, type, pixels);
        }
        @Override
        public void glCopyTexImage2D(int target, int level, int internalFormat, int x, int y, int width, int height, int border) {
            GL11.glCopyTexImage2D(target, level, internalFormat, x, y, width, height, border);
        }

        @Override
        public void glReadBuffer(int mode) {
            GL11.glReadBuffer(mode);
        }

        @Override
        public void glBlitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1,
                                      int dstY1, int mask, int filter) {
            org.lwjgl.opengl.GL30.glBlitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask,
                    filter);
        }

        @Override
        public int glGetTexLevelParameteri(int target, int level, int parameterName) {
            return GL11.glGetTexLevelParameteri(target, level, parameterName);
        }
        @Override
        public void glEnableClientState(int array) {
            GL11.glEnableClientState(array);
        }
        @Override
        public void glDisableClientState(int array) {
            GL11.glDisableClientState(array);
        }
        @Override
        public void glVertexPointer(int size, int type, int stride, long offset) {
            GL11.glVertexPointer(size, type, stride, offset);
        }
        @Override
        public void glVertexPointer(int size, int stride, java.nio.FloatBuffer buffer) {
            GL11.glVertexPointer(size, GL_FLOAT, stride, buffer);
        }
        @Override
        public void glColorPointer(int size, int type, int stride, long offset) {
            GL11.glColorPointer(size, type, stride, offset);
        }
        @Override
        public void glColorPointer(int size, boolean normalized, int stride, java.nio.ByteBuffer buffer) {
            GL11.glColorPointer(size, normalized ? GL_UNSIGNED_BYTE : GL_BYTE, stride, buffer);
        }
        @Override
        public void glTexCoordPointer(int size, int type, int stride, long offset) {
            GL11.glTexCoordPointer(size, type, stride, offset);
        }
        @Override
        public void glTexCoordPointer(int size, int stride, java.nio.FloatBuffer buffer) {
            GL11.glTexCoordPointer(size, GL_FLOAT, stride, buffer);
        }
        @Override
        public void glTexCoordPointer(int size, int stride, java.nio.ShortBuffer buffer) {
            GL11.glTexCoordPointer(size, GL_SHORT, stride, buffer);
        }
        @Override
        public void glNormalPointer(int type, int stride, long offset) {
            GL11.glNormalPointer(type, stride, offset);
        }
        @Override
        public void glNormalPointer(int type, java.nio.ByteBuffer buffer) {
            GL11.glNormalPointer(type, 0, buffer);
        }
        @Override
        public void glDrawArrays(int mode, int first, int count) {
            GL11.glDrawArrays(mode, first, count);
        }
        @Override
        public void glBindBufferARB(int target, int buffer) {
            GL15.glBindBuffer(target, buffer);
        }
        @Override
        public void glBufferDataARB(int target, java.nio.ByteBuffer data, int usage) {
            GL15.glBufferData(target, data, usage);
        }
        @Override
        public void glNewList(int list, int glMode) {
            GL11.glNewList(list, glMode);
        }

        @Override
        public void glEndList() {
            GL11.glEndList();
        }

        @Override
        public void glCallList(int list) {
            GL11.glCallList(list);
        }

        @Override
        public void glLineStipple(int factor, short pattern) {
            GL11.glLineStipple(factor, pattern);
        }

        @Override
        public boolean glGetBoolean(int parameterName) {
            return GL11.glGetBoolean(parameterName);
        }

        @Override
        public int glCreateProgramObjectARB() {
            return org.lwjgl.opengl.ARBShaderObjects.glCreateProgramObjectARB();
        }

        @Override
        public int glCreateShaderObjectARB(int type) {
            return org.lwjgl.opengl.ARBShaderObjects.glCreateShaderObjectARB(type);
        }

        @Override
        public void glShaderSourceARB(int shader, CharSequence source) {
            org.lwjgl.opengl.ARBShaderObjects.glShaderSourceARB(shader, source);
        }

        @Override
        public void glCompileShaderARB(int shader) {
            org.lwjgl.opengl.ARBShaderObjects.glCompileShaderARB(shader);
        }

        @Override
        public void glAttachObjectARB(int program, int shader) {
            org.lwjgl.opengl.ARBShaderObjects.glAttachObjectARB(program, shader);
        }

        @Override
        public void glLinkProgramARB(int program) {
            org.lwjgl.opengl.ARBShaderObjects.glLinkProgramARB(program);
        }

        @Override
        public void glValidateProgramARB(int program) {
            org.lwjgl.opengl.ARBShaderObjects.glValidateProgramARB(program);
        }

        @Override
        public void glUseProgramObjectARB(int program) {
            org.lwjgl.opengl.ARBShaderObjects.glUseProgramObjectARB(program);
        }

        @Override
        public void glDeleteObjectARB(int object) {
            org.lwjgl.opengl.ARBShaderObjects.glDeleteObjectARB(object);
        }

        @Override
        public int glGetObjectParameteriARB(int object, int parameterName) {
            return org.lwjgl.opengl.ARBShaderObjects.glGetObjectParameteriARB(object, parameterName);
        }

        @Override
        public String glGetInfoLogARB(int object, int maxLength) {
            return org.lwjgl.opengl.ARBShaderObjects.glGetInfoLogARB(object, maxLength);
        }

        @Override
        public int glGetUniformLocationARB(int program, CharSequence name) {
            return org.lwjgl.opengl.ARBShaderObjects.glGetUniformLocationARB(program, name);
        }

        @Override
        public void glUniform1iARB(int location, int value) {
            org.lwjgl.opengl.ARBShaderObjects.glUniform1iARB(location, value);
        }

        @Override
        public void glUniform1fARB(int location, float value) {
            org.lwjgl.opengl.ARBShaderObjects.glUniform1fARB(location, value);
        }

        @Override
        public void glUniform3fARB(int location, float x, float y, float z) {
            org.lwjgl.opengl.ARBShaderObjects.glUniform3fARB(location, x, y, z);
        }

        @Override
        public void glFinish() {
            GL11.glFinish();
        }
    };

    /**
     * The active implementation. Starts as real GL so that a process whose backend never installs anything
     * still renders normally.
     *
     * <p>Volatile because the switch can happen on the thread that finishes backend setup while the render
     * thread is already calling through.</p>
     */
    private static volatile Impl impl = REAL_GL;

    private ShimGl() {}

    /**
     * Installs an implementation.
     *
     * <p><b>Why {@code null} no longer means real GL.</b> It used to, and that was a segfault: the Vulkan backend
     * calls {@code setImpl(null)} when it closes - including after a frame fails - and the next mod GL call then went
     * to {@code liblwjgl_opengl} with no GL context behind it. Measured: a SIGSEGV inside
     * {@code glUseProgramObjectARB} on the frame that drew a gun after a program failed to link.</p>
     *
     * <p>A closed renderer means there is nothing to call, so the calls are ignored and one line says so. A caller
     * that wants real GL - a test, or a session that never had a Vulkan window - installs {@link #REAL_GL}
     * explicitly.</p>
     *
     * @param next the implementation; {@code null} installs {@link #CLOSED}
     */
    public static void setImpl(Impl next) {
        if (next == null) {
            if (impl != CLOSED) {
                LOGGER.info("the renderer has closed; mod GL calls are ignored from here (they used to reach real"
                        + " GL, which has no context in a Vulkan window)");
            }
            impl = CLOSED;
            return;
        }
        impl = next;
    }

    /**
     * The implementation for a renderer that has closed: every call is ignored.
     *
     * <p>Not a fallback to OpenGL - that is what {@link #REAL_GL} is for, and it is only valid when the window has a
     * GL context. This is the "nothing can be done" answer, which is the truthful one after the device is gone.</p>
     */
    public static final Impl CLOSED = new Impl() {};

    /** The active implementation. Diagnostics and tests only. */
    public static Impl impl() {
        return impl;
    }

    /**
     * Whether the facade is currently passing through to real GL.
     *
     * <p>Useful for a backend that wants to know whether it still owns drawing.</p>
     */
    public static boolean isRealGl() {
        return impl == REAL_GL;
    }

    public static void glEnable(int cap) {
        impl.glEnable(cap);
    }

    public static void glDisable(int cap) {
        impl.glDisable(cap);
    }

    public static void glPushMatrix() {
        impl.glPushMatrix();
    }

    public static void glPopMatrix() {
        impl.glPopMatrix();
    }

    public static void glTranslatef(float x, float y, float z) {
        impl.glTranslatef(x, y, z);
    }

    public static void glTranslated(double x, double y, double z) {
        impl.glTranslated(x, y, z);
    }

    public static void glScalef(float x, float y, float z) {
        impl.glScalef(x, y, z);
    }

    public static void glScaled(double x, double y, double z) {
        impl.glScaled(x, y, z);
    }

    public static void glRotated(double angle, double x, double y, double z) {
        impl.glRotated(angle, x, y, z);
    }

    public static void glVertex2f(float x, float y) {
        impl.glVertex2f(x, y);
    }

    public static void glVertex3d(double x, double y, double z) {
        impl.glVertex3d(x, y, z);
    }

    public static void glVertex2i(int x, int y) {
        impl.glVertex2i(x, y);
    }

    public static void glTexCoord2f(float u, float v) {
        impl.glTexCoord2f(u, v);
    }

    public static void glColor3f(float red, float green, float blue) {
        impl.glColor3f(red, green, blue);
    }

    public static void glColor4ub(byte red, byte green, byte blue, byte alpha) {
        impl.glColor4ub(red, green, blue, alpha);
    }

    public static void glColor4d(double red, double green, double blue, double alpha) {
        impl.glColor4d(red, green, blue, alpha);
    }

    public static int glGenTextures() {
        return impl.glGenTextures();
    }

    public static void glDeleteTextures(int texture) {
        impl.glDeleteTextures(texture);
    }

    public static void glBindTexture(int target, int texture) {
        impl.glBindTexture(target, texture);
    }

    public static void glTexParameteri(int target, int parameterName, int value) {
        impl.glTexParameteri(target, parameterName, value);
    }

    public static void glTexParameterf(int target, int parameterName, float value) {
        impl.glTexParameterf(target, parameterName, value);
    }

    public static void glTexImage2D(int target, int level, int internalFormat, int width, int height, int border,
                                    int format, int type, java.nio.ByteBuffer pixels) {
        impl.glTexImage2D(target, level, internalFormat, width, height, border, format, type, pixels);
    }

    public static void glTexSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height,
                                       int format, int type, java.nio.ByteBuffer pixels) {
        impl.glTexSubImage2D(target, level, xOffset, yOffset, width, height, format, type, pixels);
    }

    public static void glPixelStorei(int parameterName, int value) {
        impl.glPixelStorei(parameterName, value);
    }

    public static void glGenerateMipmap(int target) {
        impl.glGenerateMipmap(target);
    }

    public static void glFogi(int pname, int value) {
        impl.glFogi(pname, value);
    }

    public static void glFogf(int pname, float value) {
        impl.glFogf(pname, value);
    }

    public static void glFog(int pname, java.nio.FloatBuffer params) {
        impl.glFog(pname, params);
    }

    public static void glDepthRange(double near, double far) {
        impl.glDepthRange(near, far);
    }

    public static void glTexGeni(int coord, int pname, int value) {
        impl.glTexGeni(coord, pname, value);
    }

    public static void glTexGen(int coord, int pname, java.nio.FloatBuffer params) {
        impl.glTexGen(coord, pname, params);
    }

    public static void glColor3d(double red, double green, double blue) {
        impl.glColor3d(red, green, blue);
    }

    public static void glNormal3d(double x, double y, double z) {
        impl.glNormal3d(x, y, z);
    }

    public static void glTexCoord2d(double u, double v) {
        impl.glTexCoord2d(u, v);
    }

    public static int glCreateProgram() {
        return impl.glCreateProgram();
    }

    public static int glCreateShader(int type) {
        return impl.glCreateShader(type);
    }

    public static void glShaderSource(int shader, CharSequence source) {
        impl.glShaderSource(shader, source);
    }

    public static void glCompileShader(int shader) {
        impl.glCompileShader(shader);
    }

    public static void glAttachShader(int program, int shader) {
        impl.glAttachShader(program, shader);
    }

    public static void glLinkProgram(int program) {
        impl.glLinkProgram(program);
    }

    public static void glValidateProgram(int program) {
        impl.glValidateProgram(program);
    }

    public static void glUseProgram(int program) {
        impl.glUseProgram(program);
    }

    public static void glDeleteProgram(int program) {
        impl.glDeleteProgram(program);
    }

    public static void glDeleteShader(int shader) {
        impl.glDeleteShader(shader);
    }

    public static int glGetProgrami(int program, int parameterName) {
        return impl.glGetProgrami(program, parameterName);
    }

    public static int glGetShaderi(int shader, int parameterName) {
        return impl.glGetShaderi(shader, parameterName);
    }

    public static String glGetProgramInfoLog(int program, int maxLength) {
        return impl.glGetProgramInfoLog(program, maxLength);
    }

    public static String glGetShaderInfoLog(int shader, int maxLength) {
        return impl.glGetShaderInfoLog(shader, maxLength);
    }

    public static int glGetUniformLocation(int program, CharSequence name) {
        return impl.glGetUniformLocation(program, name);
    }

    public static void glUniform1i(int location, int value) {
        impl.glUniform1i(location, value);
    }

    public static void glUniform1f(int location, float value) {
        impl.glUniform1f(location, value);
    }

    public static void glUniform2f(int location, float v0, float v1) {
        impl.glUniform2f(location, v0, v1);
    }

    public static void glUniform3f(int location, float v0, float v1, float v2) {
        impl.glUniform3f(location, v0, v1, v2);
    }

    public static void glUniform4f(int location, float v0, float v1, float v2, float v3) {
        impl.glUniform4f(location, v0, v1, v2, v3);
    }

    public static void glUniform2i(int location, int v0, int v1) {
        impl.glUniform2i(location, v0, v1);
    }

    public static void glUniform3i(int location, int v0, int v1, int v2) {
        impl.glUniform3i(location, v0, v1, v2);
    }

    public static void glUniform4i(int location, int v0, int v1, int v2, int v3) {
        impl.glUniform4i(location, v0, v1, v2, v3);
    }

    public static void glUniformMatrix4(int location, boolean transpose, java.nio.FloatBuffer value) {
        impl.glUniformMatrix4(location, transpose, value);
    }

    public static void glUniform2iARB(int location, int v0, int v1) {
        impl.glUniform2iARB(location, v0, v1);
    }

    public static void glUniform3iARB(int location, int v0, int v1, int v2) {
        impl.glUniform3iARB(location, v0, v1, v2);
    }

    public static void glUniform4iARB(int location, int v0, int v1, int v2, int v3) {
        impl.glUniform4iARB(location, v0, v1, v2, v3);
    }

    public static int glGenBuffers() {
        return impl.glGenBuffers();
    }

    public static void glBindBuffer(int target, int buffer) {
        impl.glBindBuffer(target, buffer);
    }

    public static void glBufferData(int target, java.nio.ByteBuffer data, int usage) {
        impl.glBufferData(target, data, usage);
    }

    public static void glDeleteBuffers(int buffer) {
        impl.glDeleteBuffers(buffer);
    }

    public static void glGetInteger(int parameterName, java.nio.IntBuffer values) {
        impl.glGetInteger(parameterName, values);
    }

    public static int glGetError() {
        return impl.glGetError();
    }

    public static String glGetString(int name) {
        return impl.glGetString(name);
    }

    public static void glDrawBuffer(int mode) {
        impl.glDrawBuffer(mode);
    }

    public static void glVertex2d(double x, double y) {
        impl.glVertex2d(x, y);
    }

    public static void glFrustum(double left, double right, double bottom, double top, double near, double far) {
        impl.glFrustum(left, right, bottom, top, near, far);
    }

    public static void glClipPlane(int plane, java.nio.DoubleBuffer equation) {
        impl.glClipPlane(plane, equation);
    }

    public static void glFlush() {
        impl.glFlush();
    }

    public static int glGetTexEnvi(int target, int parameterName) {
        return impl.glGetTexEnvi(target, parameterName);
    }

    public static float glGetTexEnvf(int target, int parameterName) {
        return impl.glGetTexEnvf(target, parameterName);
    }

    public static int glGetTexParameteri(int target, int parameterName) {
        return impl.glGetTexParameteri(target, parameterName);
    }

    public static void glGenTextures(java.nio.IntBuffer textures) {
        impl.glGenTextures(textures);
    }

    public static void glDeleteTextures(java.nio.IntBuffer textures) {
        impl.glDeleteTextures(textures);
    }

    public static void glTexImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, java.nio.IntBuffer pixels) {
        impl.glTexImage2D(target, level, internalFormat, width, height, border, format, type, pixels);
    }

    public static void glTexSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height, int format, int type, java.nio.IntBuffer pixels) {
        impl.glTexSubImage2D(target, level, xOffset, yOffset, width, height, format, type, pixels);
    }

    public static void glTexImage1D(int target, int level, int internalFormat, int width, int border, int format, int type, java.nio.FloatBuffer pixels) {
        impl.glTexImage1D(target, level, internalFormat, width, border, format, type, pixels);
    }

    public static void glActiveTexture(int unit) {
        impl.glActiveTexture(unit);
    }

    public static void glBlendFuncSeparate(int sourceRgb, int destinationRgb, int sourceAlpha, int destinationAlpha) {
        impl.glBlendFuncSeparate(sourceRgb, destinationRgb, sourceAlpha, destinationAlpha);
    }

    public static void glBlendEquationSeparate(int modeRgb, int modeAlpha) {
        impl.glBlendEquationSeparate(modeRgb, modeAlpha);
    }

    public static void glDetachShader(int program, int shader) {
        impl.glDetachShader(program, shader);
    }

    public static String glGetActiveUniform(int program, int index, int maxLength) {
        return impl.glGetActiveUniform(program, index, maxLength);
    }

    public static int glGetActiveUniformType(int program, int index) {
        return impl.glGetActiveUniformType(program, index);
    }

    public static int glGetActiveUniformSize(int program, int index) {
        return impl.glGetActiveUniformSize(program, index);
    }

    public static void glUniformMatrix2(int location, boolean transpose, java.nio.FloatBuffer value) {
        impl.glUniformMatrix2(location, transpose, value);
    }

    public static void glUniform2fARB(int location, float v0, float v1) {
        impl.glUniform2fARB(location, v0, v1);
    }

    public static void glUniform4fARB(int location, float v0, float v1, float v2, float v3) {
        impl.glUniform4fARB(location, v0, v1, v2, v3);
    }

    public static int glGenFramebuffers() {
        return impl.glGenFramebuffers();
    }

    public static void glBindFramebuffer(int target, int framebuffer) {
        impl.glBindFramebuffer(target, framebuffer);
    }

    public static void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
        impl.glFramebufferTexture2D(target, attachment, textarget, texture, level);
    }

    public static void glDeleteFramebuffers(int framebuffer) {
        impl.glDeleteFramebuffers(framebuffer);
    }

    public static void glRotatef(float angle, float x, float y, float z) {
        impl.glRotatef(angle, x, y, z);
    }

    public static void glMatrixMode(int mode) {
        impl.glMatrixMode(mode);
    }

    public static void glLoadIdentity() {
        impl.glLoadIdentity();
    }

    public static void glOrtho(double left, double right, double bottom, double top, double zNear, double zFar) {
        impl.glOrtho(left, right, bottom, top, zNear, zFar);
    }

    public static void glAlphaFunc(int comparison, float reference) {
        impl.glAlphaFunc(comparison, reference);
    }

    public static void glDepthFunc(int comparison) {
        impl.glDepthFunc(comparison);
    }

    public static void glViewport(int x, int y, int width, int height) {
        impl.glViewport(x, y, width, height);
    }

    public static void glClear(int mask) {
        impl.glClear(mask);
    }

    public static void glColor4f(float red, float green, float blue, float alpha) {
        impl.glColor4f(red, green, blue, alpha);
    }

    public static void glColor3ub(byte red, byte green, byte blue) {
        impl.glColor3ub(red, green, blue);
    }

    public static void glNormal3f(float x, float y, float z) {
        impl.glNormal3f(x, y, z);
    }

    public static void glBegin(int mode) {
        impl.glBegin(mode);
    }

    public static void glEnd() {
        impl.glEnd();
    }

    public static void glVertex3f(float x, float y, float z) {
        impl.glVertex3f(x, y, z);
    }

    public static void glLineWidth(float width) {
        impl.glLineWidth(width);
    }

    public static void glHint(int target, int mode) {
        impl.glHint(target, mode);
    }

    public static boolean glIsEnabled(int cap) {
        return impl.glIsEnabled(cap);
    }

    public static int glGetInteger(int parameterName) {
        return impl.glGetInteger(parameterName);
    }

    public static float glGetFloat(int parameterName) {
        return impl.glGetFloat(parameterName);
    }

    public static void glGetFloat(int parameterName, java.nio.FloatBuffer out) {
        impl.glGetFloat(parameterName, out);
    }

    public static void glLight(int light, int parameterName, java.nio.FloatBuffer params) {
        impl.glLight(light, parameterName, params);
    }

    public static void glPointSize(float size) {
        impl.glPointSize(size);
    }

    public static void glStencilFunc(int comparison, int reference, int mask) {
        impl.glStencilFunc(comparison, reference, mask);
    }

    public static void glStencilMask(int mask) {
        impl.glStencilMask(mask);
    }

    public static void glStencilOp(int fail, int depthFail, int depthPass) {
        impl.glStencilOp(fail, depthFail, depthPass);
    }

    public static void glFinish() {
        impl.glFinish();
    }


    public static void glBlendFunc(int glSourceFactor, int glDestinationFactor) {
        impl.glBlendFunc(glSourceFactor, glDestinationFactor);
    }
    public static void glScissor(int x, int y, int width, int height) {
        impl.glScissor(x, y, width, height);
    }
    public static void glDepthMask(boolean enabled) {
        impl.glDepthMask(enabled);
    }
    public static void glShadeModel(int glModel) {
        impl.glShadeModel(glModel);
    }
    public static void glClearColor(float red, float green, float blue, float alpha) {
        impl.glClearColor(red, green, blue, alpha);
    }
    public static void glColorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        impl.glColorMask(red, green, blue, alpha);
    }
    public static void glPolygonMode(int glFace, int glMode) {
        impl.glPolygonMode(glFace, glMode);
    }
    public static void glClearDepth(double depth) {
        impl.glClearDepth(depth);
    }
    public static void glLogicOp(int glOpcode) {
        impl.glLogicOp(glOpcode);
    }
    public static void glTexEnvi(int target, int parameterName, int parameter) {
        impl.glTexEnvi(target, parameterName, parameter);
    }
    public static void glTexEnvf(int target, int parameterName, float parameter) {
        impl.glTexEnvf(target, parameterName, parameter);
    }
    public static void glTexEnv(int target, int parameterName, java.nio.FloatBuffer parameters) {
        impl.glTexEnv(target, parameterName, parameters);
    }
    public static void glLoadMatrix(java.nio.FloatBuffer matrix) {
        impl.glLoadMatrix(matrix);
    }
    public static void glLoadMatrixf(java.nio.FloatBuffer matrix) {
        impl.glLoadMatrixf(matrix);
    }
    public static void glMultMatrix(java.nio.FloatBuffer matrix) {
        impl.glMultMatrix(matrix);
    }
    public static void glMultMatrixf(java.nio.FloatBuffer matrix) {
        impl.glMultMatrixf(matrix);
    }
    public static void glMultMatrix(java.nio.DoubleBuffer matrix) {
        impl.glMultMatrix(matrix);
    }



    public static void glBlendColor(float red, float green, float blue, float alpha) {
        impl.glBlendColor(red, green, blue, alpha);
    }

    public static void glPushAttrib(int mask) {
        impl.glPushAttrib(mask);
    }
    public static void glPopAttrib() {
        impl.glPopAttrib();
    }
    public static void glClearStencil(int value) {
        impl.glClearStencil(value);
    }
    public static void glReadPixels(int x, int y, int width, int height, int format, int type, java.nio.ByteBuffer pixels) {
        impl.glReadPixels(x, y, width, height, format, type, pixels);
    }
    public static void glReadPixels(int x, int y, int width, int height, int format, int type, java.nio.FloatBuffer pixels) {
        impl.glReadPixels(x, y, width, height, format, type, pixels);
    }
    public static void glReadPixels(int x, int y, int width, int height, int format, int type, java.nio.IntBuffer pixels) {
        impl.glReadPixels(x, y, width, height, format, type, pixels);
    }
    public static void glGetTexImage(int target, int level, int format, int type, java.nio.ByteBuffer pixels) {
        impl.glGetTexImage(target, level, format, type, pixels);
    }
    public static void glGetTexImage(int target, int level, int format, int type, java.nio.IntBuffer pixels) {
        impl.glGetTexImage(target, level, format, type, pixels);
    }
    public static void glCopyTexImage2D(int target, int level, int internalFormat, int x, int y, int width, int height, int border) {
        impl.glCopyTexImage2D(target, level, internalFormat, x, y, width, height, border);
    }
    public static void glReadBuffer(int mode) {
        impl.glReadBuffer(mode);
    }
    public static void glBlitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1,
                                         int dstY1, int mask, int filter) {
        impl.glBlitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
    }
    public static int glGetTexLevelParameteri(int target, int level, int parameterName) {
        return impl.glGetTexLevelParameteri(target, level, parameterName);
    }
    public static void glEnableClientState(int array) {
        impl.glEnableClientState(array);
    }
    public static void glDisableClientState(int array) {
        impl.glDisableClientState(array);
    }
    public static void glVertexPointer(int size, int type, int stride, long offset) {
        impl.glVertexPointer(size, type, stride, offset);
    }
    public static void glVertexPointer(int size, int stride, java.nio.FloatBuffer buffer) {
        impl.glVertexPointer(size, stride, buffer);
    }
    public static void glColorPointer(int size, int type, int stride, long offset) {
        impl.glColorPointer(size, type, stride, offset);
    }
    public static void glColorPointer(int size, boolean normalized, int stride, java.nio.ByteBuffer buffer) {
        impl.glColorPointer(size, normalized, stride, buffer);
    }
    public static void glTexCoordPointer(int size, int type, int stride, long offset) {
        impl.glTexCoordPointer(size, type, stride, offset);
    }
    public static void glTexCoordPointer(int size, int stride, java.nio.FloatBuffer buffer) {
        impl.glTexCoordPointer(size, stride, buffer);
    }
    public static void glTexCoordPointer(int size, int stride, java.nio.ShortBuffer buffer) {
        impl.glTexCoordPointer(size, stride, buffer);
    }
    public static void glNormalPointer(int type, int stride, long offset) {
        impl.glNormalPointer(type, stride, offset);
    }
    public static void glNormalPointer(int type, java.nio.ByteBuffer buffer) {
        impl.glNormalPointer(type, buffer);
    }
    public static void glDrawArrays(int mode, int first, int count) {
        impl.glDrawArrays(mode, first, count);
    }
    public static void glBindBufferARB(int target, int buffer) {
        impl.glBindBufferARB(target, buffer);
    }
    public static void glBufferDataARB(int target, java.nio.ByteBuffer data, int usage) {
        impl.glBufferDataARB(target, data, usage);
    }
    public static void glNewList(int list, int glMode) {
        impl.glNewList(list, glMode);
    }

    public static void glEndList() {
        impl.glEndList();
    }

    public static void glCallList(int list) {
        impl.glCallList(list);
    }

    public static void glLineStipple(int factor, short pattern) {
        impl.glLineStipple(factor, pattern);
    }

    public static boolean glGetBoolean(int parameterName) {
        return impl.glGetBoolean(parameterName);
    }

    public static int glCreateProgramObjectARB() {
        return impl.glCreateProgramObjectARB();
    }

    public static int glCreateShaderObjectARB(int type) {
        return impl.glCreateShaderObjectARB(type);
    }

    public static void glShaderSourceARB(int shader, CharSequence source) {
        impl.glShaderSourceARB(shader, source);
    }

    public static void glCompileShaderARB(int shader) {
        impl.glCompileShaderARB(shader);
    }

    public static void glAttachObjectARB(int program, int shader) {
        impl.glAttachObjectARB(program, shader);
    }

    public static void glLinkProgramARB(int program) {
        impl.glLinkProgramARB(program);
    }

    public static void glValidateProgramARB(int program) {
        impl.glValidateProgramARB(program);
    }

    public static void glUseProgramObjectARB(int program) {
        impl.glUseProgramObjectARB(program);
    }

    public static void glDeleteObjectARB(int object) {
        impl.glDeleteObjectARB(object);
    }

    public static int glGetObjectParameteriARB(int object, int parameterName) {
        return impl.glGetObjectParameteriARB(object, parameterName);
    }

    public static String glGetInfoLogARB(int object, int maxLength) {
        return impl.glGetInfoLogARB(object, maxLength);
    }

    public static int glGetUniformLocationARB(int program, CharSequence name) {
        return impl.glGetUniformLocationARB(program, name);
    }

    public static void glUniform1iARB(int location, int value) {
        impl.glUniform1iARB(location, value);
    }

    public static void glUniform1fARB(int location, float value) {
        impl.glUniform1fARB(location, value);
    }

    public static void glUniform3fARB(int location, float x, float y, float z) {
        impl.glUniform3fARB(location, x, y, z);
    }
}
