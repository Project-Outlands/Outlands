package net.minecraftforge.client.render.shim;

import net.minecraftforge.client.render.shader.GlShaders;
import net.minecraftforge.client.render.state.backend.BufferObjects;
import net.minecraftforge.client.render.state.backend.ClientArrays;
import net.minecraftforge.client.render.state.backend.DisplayListBackend;
import net.minecraftforge.client.render.state.backend.DisplayLists;
import net.minecraftforge.client.render.state.backend.Capability;
import net.minecraftforge.client.render.state.backend.FrameState;
import net.minecraftforge.client.render.state.backend.FrameStates;

import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11;

/**
 * {@link ShimGl}'s implementation for a renderer that is not OpenGL: mod GL calls land in the same
 * {@link FrameState} that vanilla's patched {@code GlStateManager} writes to.
 *
 * <p><b>Why it forwards instead of implementing.</b> There is no second set of semantics here. Vanilla already
 * decided what {@code glPushMatrix} means on this backend, in its own patch: one guard, then
 * {@code state.pushMatrix()}. A mod calling the same entry point must reach the same object, or the two
 * diverge - the mod's transform would apply to nothing while vanilla's {@code ModelRenderer} drew in the
 * original frame, which looks like a renderer bug rather than a missing route. So each method below is the
 * patched vanilla method, one level down.</p>
 *
 * <p><b>Why the real-GL fallback is not defensive coding.</b> {@link FrameStates#forNonGl()} returns
 * {@code null} when the vanilla GL path applies, which includes the window before the backend is installed and
 * any process that fell back. A mod can call GL during {@code preInit}, before there is any frame state, and
 * the same is true of vanilla: the guard in the patch exists for exactly that reason. Passing through to
 * {@code GL11} keeps those calls working instead of throwing from inside mod initialisation.</p>
 *
 * <p><b>Cost.</b> One null check and one interface call per GL call, which is what the vanilla patch pays too.</p>
 */
public final class FrameShim implements ShimGl.Impl {

    /** {@code GL_CULL_FACE_MODE}: which face culling removes. */
    private static final int GL_CULL_FACE_MODE = 2885;
    /** {@code GL_TEXTURE_BINDING_2D}: the texture bound to the active unit. */
    private static final int GL_TEXTURE_BINDING_2D = 32873;
    /** {@code GL_DEPTH_WRITEMASK}: whether depth writes are on, which glIsEnabled does not answer. */
    private static final int GL_DEPTH_WRITEMASK = 2930;
    /** {@code GL_MODELVIEW_MATRIX}, and the other two readback targets. */
    private static final int GL_MODELVIEW_MATRIX = 2982;
    private static final int GL_PROJECTION_MATRIX = 2983;
    private static final int GL_TEXTURE_MATRIX = 2984;

    /**
     * Scratch for the double-precision matrix form, so {@code glMultMatrixd} does not allocate per call.
     *
     * <p>Safe as a static because matrices are only ever composed on the render thread, the same thread the frame
     * state lives on.</p>
     */
    private static final java.nio.FloatBuffer MATRIX_SCRATCH = java.nio.FloatBuffer.allocate(16);

    /**
     * Calls that reached the frame state instead of real GL.
     *
     * <p>Counted in one place - {@link #state()} - because that is the single decision every method makes, and
     * because the alternative (a counter per method) would be eighteen copies of the same increment. Printed when
     * the backend closes: with a mod installed, zero means the call sites are still going somewhere else, which is
     * the one failure this whole path can have without an exception.</p>
     */
    private static final java.util.concurrent.atomic.LongAdder ROUTED =
            new java.util.concurrent.atomic.LongAdder();

    /** How many GL calls have been routed to the frame state since the shim was installed. */
    public static long routedCalls() {
        return ROUTED.sum();
    }

    /**
     * The display-list backend, or {@code null} when the vanilla GL path applies.
     *
     * <p>Not {@link #state()}: recording a list needs no frame - that is the point of it, and it is how the sky and
     * cloud lists are built during startup. Only the replay needs one, and the backend says so itself.</p>
     */
    /**
     * The client-array state, or {@code null} when the vanilla GL path applies.
     *
     * <p>Vertex input is not frame state: a mod declares its arrays and draws in the same breath, and the arrays
     * outlive no frame. It is kept apart from {@link #state()} for the same reason {@code GlStateManager} treats the
     * declarations as a separate concern.</p>
     */
    private static @Nullable ClientArrays arrays() {
        ClientArrays arrays = ClientArrays.forNonGl();
        if (arrays != null) {
            ROUTED.increment();
        }
        return arrays;
    }

    /** The buffer-object store, or {@code null} when the vanilla GL path applies. */
    private static @Nullable BufferObjects buffers() {
        BufferObjects buffers = BufferObjects.forNonGl();
        if (buffers != null) {
            ROUTED.increment();
        }
        return buffers;
    }

    private static @Nullable DisplayListBackend lists() {
        DisplayListBackend lists = DisplayLists.forNonGl();
        if (lists != null) {
            ROUTED.increment();
        }
        return lists;
    }

    private static @Nullable FrameState state() {
        FrameState state = FrameStates.forNonGl();
        if (state != null) {
            ROUTED.increment();
        }
        return state;
    }

    @Override
    public void glEnable(int cap) {
        FrameState state = state();
        if (state == null) {
            GL11.glEnable(cap);
            return;
        }
        Capability capability = Capability.tryOf(cap);
        if (capability == null) {
            // A mod may toggle bits outside the set vanilla uses (GL_COLOR_ARRAY, GL_LINE_SMOOTH, ...). Throwing
            // would take the process down from inside a render loop over a bit nothing reads, so it is recorded
            // once per constant and ignored. Vanilla keeps the throwing overload: an unknown constant there is a
            // programming error, not a mod's choice.
            FrameStates.reportSkippedOnce("glEnable(" + cap + ")",
                    "the capability is outside the set vanilla toggles, so it is ignored");
            return;
        }
        state.capability(capability, true);
    }

    @Override
    public void glDisable(int cap) {
        FrameState state = state();
        if (state == null) {
            GL11.glDisable(cap);
            return;
        }
        Capability capability = Capability.tryOf(cap);
        if (capability == null) {
            FrameStates.reportSkippedOnce("glDisable(" + cap + ")",
                    "the capability is outside the set vanilla toggles, so it is ignored");
            return;
        }
        state.capability(capability, false);
    }

    @Override
    public void glPushMatrix() {
        FrameState state = state();
        if (state == null) {
            GL11.glPushMatrix();
            return;
        }
        state.pushMatrix();
    }

    @Override
    public void glPopMatrix() {
        FrameState state = state();
        if (state == null) {
            GL11.glPopMatrix();
            return;
        }
        state.popMatrix();
    }

    @Override
    public void glTranslatef(float x, float y, float z) {
        FrameState state = state();
        if (state == null) {
            GL11.glTranslatef(x, y, z);
            return;
        }
        state.translate(x, y, z);
    }

    @Override
    public void glTranslated(double x, double y, double z) {
        FrameState state = state();
        if (state == null) {
            GL11.glTranslated(x, y, z);
            return;
        }
        state.translate(x, y, z);
    }

    @Override
    public void glScalef(float x, float y, float z) {
        FrameState state = state();
        if (state == null) {
            GL11.glScalef(x, y, z);
            return;
        }
        state.scale(x, y, z);
    }

    @Override
    public void glRotatef(float angle, float x, float y, float z) {
        FrameState state = state();
        if (state == null) {
            GL11.glRotatef(angle, x, y, z);
            return;
        }
        state.rotate(angle, x, y, z);
    }

    @Override
    public void glMatrixMode(int mode) {
        FrameState state = state();
        if (state == null) {
            GL11.glMatrixMode(mode);
            return;
        }
        state.matrixMode(mode);
    }

    @Override
    public void glLoadIdentity() {
        FrameState state = state();
        if (state == null) {
            GL11.glLoadIdentity();
            return;
        }
        state.loadIdentity();
    }

    @Override
    public void glOrtho(double left, double right, double bottom, double top, double zNear, double zFar) {
        FrameState state = state();
        if (state == null) {
            GL11.glOrtho(left, right, bottom, top, zNear, zFar);
            return;
        }
        state.ortho(left, right, bottom, top, zNear, zFar);
    }

    @Override
    public void glAlphaFunc(int comparison, float reference) {
        FrameState state = state();
        if (state == null) {
            GL11.glAlphaFunc(comparison, reference);
            return;
        }
        state.alphaFunc(comparison, reference);
    }

    @Override
    public void glDepthFunc(int comparison) {
        FrameState state = state();
        if (state == null) {
            GL11.glDepthFunc(comparison);
            return;
        }
        state.depthFunc(comparison);
    }

    @Override
    public void glViewport(int x, int y, int width, int height) {
        FrameState state = state();
        if (state == null) {
            GL11.glViewport(x, y, width, height);
            return;
        }
        state.viewport(x, y, width, height);
    }

    @Override
    public void glClear(int mask) {
        FrameState state = state();
        if (state == null) {
            GL11.glClear(mask);
            return;
        }
        state.clear(mask);
    }

    @Override
    public void glColor4f(float red, float green, float blue, float alpha) {
        FrameState state = state();
        if (state == null) {
            GL11.glColor4f(red, green, blue, alpha);
            return;
        }
        state.color(red, green, blue, alpha);
    }

    /**
     * Records and drops the current normal.
     *
     * <p><b>Why dropping is the correct implementation rather than a gap.</b> A fixed-function normal only has
     * a consumer when {@code GL_LIGHTING} is on, and nothing in 1.12 turns it on: vanilla has drawn with
     * lightmaps since 1.6, and IC2Classic - the mod this exists for - never enables {@code GL_LIGHTING} either
     * (measured: its only capability bits are texture, alpha test, depth test and rescale-normal). A normal
     * that reaches a shader-per-draw pipeline would need a vertex attribute the layout has no room for, so
     * inventing a consumer now would be inventing the wrong one.</p>
     *
     * <p>The log line is the trigger for revisiting this: a mod that enables lighting will name itself here,
     * and the normal becomes a shader variant plus a {@code FrameState} field at that point.</p>
     */
    @Override
    public void glNormal3f(float x, float y, float z) {
        FrameState state = state();
        if (state == null) {
            GL11.glNormal3f(x, y, z);
            return;
        }
        FrameStates.reportSkippedOnce("glNormal3f",
                "a fixed-function normal has no consumer while lighting is off, so it is dropped");
    }

    /**
     * Drops the synchronisation request.
     *
     * <p>Measured use: one call, on IC2Classic's fatal-error screen, immediately before the process exits. There
     * is no flush primitive on the frame state to map it to - a draw is recorded into the frame's command buffer
     * and submitted once - so a "finish" would mean a submit, which is a frame boundary decision rather than a
     * mod's. Nothing waits on the result here, so dropping it changes no pixel. If a {@code glFinish} ever
     * appears on a live path that reads back, this becomes "submit the frame so far".</p>
     */
    @Override
    public void glColor3ub(byte red, byte green, byte blue) {
        FrameState state = state();
        if (state == null) {
            GL11.glColor3ub(red, green, blue);
            return;
        }
        // GL's byte form is unsigned: the alpha is implied to be opaque, which is what GL does.
        state.color((red & 0xFF) / 255.0F, (green & 0xFF) / 255.0F, (blue & 0xFF) / 255.0F, 1.0F);
    }

    @Override
    public void glBegin(int mode) {
        if (state() == null) {
            GL11.glBegin(mode);
            return;
        }
        // Same accumulator vanilla's patched FontRenderer writes through: a mod drawing a line and the game drawing
        // text are the same immediate-mode primitive, and giving them two accumulators would reorder one of them.
        FrameStates.immediate().begin(mode);
    }

    @Override
    public void glEnd() {
        if (state() == null) {
            GL11.glEnd();
            return;
        }
        FrameStates.immediate().end();
    }

    @Override
    public void glVertex3f(float x, float y, float z) {
        FrameState state = state();
        if (state == null) {
            GL11.glVertex3f(x, y, z);
            return;
        }
        // The colour is read here rather than passed in because that is where GL keeps it: glColor* sets it once and
        // it applies to every vertex until it changes.
        FrameStates.immediate().vertex(x, y, z, state.currentColor());
    }

    @Override
    public void glLineWidth(float width) {
        FrameState state = state();
        if (state == null) {
            GL11.glLineWidth(width);
            return;
        }
        state.lineWidth(width);
    }

    @Override
    public void glHint(int target, int mode) {
        if (state() == null) {
            GL11.glHint(target, mode);
            return;
        }
        // A hint is permission to trade quality for speed, never a command: GL itself is allowed to ignore it, and
        // this backend has one rasterisation path to apply it to. Reported once so the log shows who asked.
        FrameStates.reportSkippedOnce("glHint(" + target + ")",
                "a rasterisation hint has no pipeline equivalent, so the one rasterisation path is used");
    }

    @Override
    public boolean glIsEnabled(int cap) {
        FrameState state = state();
        if (state == null) {
            return GL11.glIsEnabled(cap);
        }
        Capability capability = Capability.tryOf(cap);
        if (capability == null) {
            // GL answers false for a capability it does not know (after GL_INVALID_ENUM). Answering the same keeps a
            // mod's branch on "is this on" from taking the path for a feature this backend does not have.
            FrameStates.reportSkippedOnce("glIsEnabled(" + cap + ")",
                    "the capability is outside the set vanilla toggles, so it is reported as off");
            return false;
        }
        return state.isEnabled(capability);
    }

    @Override
    public boolean glGetBoolean(int parameterName) {
        FrameState state = state();
        if (state == null) {
            return GL11.glGetBoolean(parameterName);
        }
        Capability capability = Capability.tryOf(parameterName);
        if (capability != null) {
            return state.isEnabled(capability);
        }
        if (parameterName == GL_DEPTH_WRITEMASK) {
            // Not a capability: glEnable cannot set it, and glIsEnabled does not answer for it. Immersive
            // Intelligence's clip-plane helper reads it before switching depth writes off and back on.
            return state.depthWriteEnabled();
        }
        FrameStates.reportSkippedOnce("glGetBoolean(" + parameterName + ")",
                "that piece of state is not kept in a form this backend can report");
        return false;
    }

    @Override
    public int glGetInteger(int parameterName) {
        FrameState state = state();
        if (state == null) {
            return ShimGl.REAL_GL.glGetInteger(parameterName);
        }
        return switch (parameterName) {
            case 2979 -> state.matrixStackDepth();     // GL_MODELVIEW_STACK_DEPTH
            case GL_CULL_FACE_MODE -> state.cullFace();
            case GL_TEXTURE_BINDING_2D -> {
                // Answered from the texture backend rather than from the frame state, which is where the binding
                // actually lives: it is the same store the draw reads its sampler from, so the answer cannot
                // disagree with what would be sampled.
                net.minecraftforge.client.render.texture.TextureBackend textures =
                        net.minecraftforge.client.render.texture.TextureBackends.forNonGl();
                yield textures == null ? 0 : textures.boundTexture(state.activeTextureUnit());
            }
            default -> {
                FrameStates.reportSkippedOnce("glGetInteger(" + parameterName + ")",
                        "that piece of state is not kept in a form this backend can report");
                yield 0;
            }
        };
    }

    @Override
    public float glGetFloat(int parameterName) {
        FrameState state = state();
        if (state == null) {
            return ShimGl.REAL_GL.glGetFloat(parameterName);
        }
        return switch (parameterName) {
            case 2849 -> state.lineWidth();            // GL_LINE_WIDTH
            default -> {
                FrameStates.reportSkippedOnce("glGetFloat(" + parameterName + ")",
                        "that piece of state is not kept in a form this backend can report");
                yield 0.0F;
            }
        };
    }

    @Override
    public void glGetFloat(int parameterName, java.nio.FloatBuffer out) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glGetFloat(parameterName, out);
            return;
        }
        if (parameterName == GL_MODELVIEW_MATRIX || parameterName == GL_PROJECTION_MATRIX
                || parameterName == GL_TEXTURE_MATRIX) {
            // Immersive Intelligence's setClipPlane reads the modelview matrix back to transform the plane, so this
            // is not a diagnostic: answering nothing leaves the clip plane in the wrong space. The frame state
            // already keeps all three matrices for exactly this readback.
            state.readMatrix(parameterName, out);
            return;
        }
        if (parameterName != 2816) {                   // GL_CURRENT_COLOR
            FrameStates.reportSkippedOnce("glGetFloat(" + parameterName + ", buffer)",
                    "that piece of state is not kept in a form this backend can report");
            return;
        }
        // Written at the caller's position and leaving it alone, which is glGetFloatv's contract: it fills the
        // storage the caller points at. Advancing would consume a buffer the caller reuses every frame.
        float[] color = state.currentColor();
        int at = out.position();
        for (int i = 0; i < 4; i++) {
            out.put(at + i, color[i]);
        }
    }

    @Override
    public void glLight(int light, int parameterName, java.nio.FloatBuffer params) {
        if (state() == null) {
            ShimGl.REAL_GL.glLight(light, parameterName, params);
            return;
        }
        // Same model the patched GlStateManager writes to, so a mod's light and the game's light are the same
        // light - including the two entry points' shared opinion about which parameters are modelled.
        FrameStates.glLight(light, parameterName, params);
    }

    @Override
    public void glPointSize(float size) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glPointSize(size);
            return;
        }
        // Vulkan has no point size state: the vertex shader writes gl_PointSize, so the value travels with the draw
        // and the stream shader reads it from the push constant.
        state.pointSize(size);
    }

    @Override
    public void glStencilFunc(int comparison, int reference, int mask) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glStencilFunc(comparison, reference, mask);
            return;
        }
        state.stencilFunc(comparison, reference, mask);
    }

    @Override
    public void glStencilMask(int mask) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glStencilMask(mask);
            return;
        }
        state.stencilMask(mask);
    }

    @Override
    public void glStencilOp(int fail, int depthFail, int depthPass) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glStencilOp(fail, depthFail, depthPass);
            return;
        }
        state.stencilOp(fail, depthFail, depthPass);
    }


    @Override
    public void glBlendFunc(int glSourceFactor, int glDestinationFactor) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glBlendFunc(glSourceFactor, glDestinationFactor);
            return;
        }
        state.blendFunc(glSourceFactor, glDestinationFactor);
    }
    @Override
    public void glScissor(int x, int y, int width, int height) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glScissor(x, y, width, height);
            return;
        }
        state.scissor(x, y, width, height);
    }
    @Override
    public void glDepthMask(boolean enabled) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glDepthMask(enabled);
            return;
        }
        state.depthMask(enabled);
    }
    @Override
    public void glShadeModel(int glModel) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glShadeModel(glModel);
            return;
        }
        state.shadeModel(glModel);
    }
    @Override
    public void glClearColor(float red, float green, float blue, float alpha) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glClearColor(red, green, blue, alpha);
            return;
        }
        state.clearColor(red, green, blue, alpha);
    }
    @Override
    public void glColorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glColorMask(red, green, blue, alpha);
            return;
        }
        state.colorMask(red, green, blue, alpha);
    }
    @Override
    public void glPolygonMode(int glFace, int glMode) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glPolygonMode(glFace, glMode);
            return;
        }
        state.polygonMode(glFace, glMode);
    }
    @Override
    public void glClearDepth(double depth) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glClearDepth(depth);
            return;
        }
        state.clearDepth(depth);
    }
    @Override
    public void glLogicOp(int glOpcode) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glLogicOp(glOpcode);
            return;
        }
        state.logicOp(glOpcode);
    }
    @Override
    public void glTexEnvi(int target, int parameterName, int parameter) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glTexEnvi(target, parameterName, parameter);
            return;
        }
        state.texEnvi(target, parameterName, parameter);
    }
    @Override
    public void glTexEnvf(int target, int parameterName, float parameter) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glTexEnvf(target, parameterName, parameter);
            return;
        }
        state.texEnvf(target, parameterName, parameter);
    }
    @Override
    public void glTexEnv(int target, int parameterName, java.nio.FloatBuffer parameters) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glTexEnv(target, parameterName, parameters);
            return;
        }
        state.texEnv(target, parameterName, parameters);
    }
    @Override
    public void glLoadMatrix(java.nio.FloatBuffer matrix) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glLoadMatrix(matrix);
            return;
        }
        state.loadMatrix(matrix);
    }
    @Override
    public void glLoadMatrixf(java.nio.FloatBuffer matrix) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glLoadMatrixf(matrix);
            return;
        }
        state.loadMatrix(matrix);
    }
    @Override
    public void glMultMatrix(java.nio.FloatBuffer matrix) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glMultMatrix(matrix);
            return;
        }
        state.multiply(matrix);
    }
    @Override
    public void glMultMatrixf(java.nio.FloatBuffer matrix) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glMultMatrixf(matrix);
            return;
        }
        state.multiply(matrix);
    }
    @Override
    public void glMultMatrix(java.nio.DoubleBuffer matrix) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glMultMatrix(matrix);
            return;
        }
        // Converted into a reusable float buffer rather than allocating one per call: the stack is float32, and the
        // caller's buffer is left alone (position included), which is what glMultMatrixd promises.
        java.nio.DoubleBuffer view = matrix.duplicate();
        view.rewind();
        if (view.remaining() < 16) {
            throw new IllegalArgumentException("a 4x4 matrix needs 16 doubles, the buffer has " + view.remaining());
        }
        for (int i = 0; i < 16; i++) {
            MATRIX_SCRATCH.put(i, (float) view.get(i));
        }
        state.multiply(MATRIX_SCRATCH);
    }



    /** {@code GL_ALL_ATTRIB_BITS}, the mask every measured {@code glPushAttrib} passes. */
    private static final int GL_ALL_ATTRIB_BITS = 0xFFFFF;

    @Override
    public void glPushAttrib(int mask) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glPushAttrib(mask);
            return;
        }
        if (mask != GL_ALL_ATTRIB_BITS) {
            // The backend saves the whole set and restores the whole set, which is what every caller in the pack
            // asks for. A narrower mask would have GL restore less; restoring more is the safe direction (state ends
            // up as the mod left it, never as a half-restored mixture), but it is not what GL would do, so it is
            // reported rather than assumed.
            FrameStates.reportSkippedOnce("glPushAttrib(" + mask + ")",
                    "the attribute mask is ignored: the whole state is saved and restored, which is what"
                            + " GL_ALL_ATTRIB_BITS asks for and what every measured caller passes");
        }
        state.pushAttributes();
    }

    @Override
    public void glPopAttrib() {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glPopAttrib();
            return;
        }
        state.popAttributes();
    }

    @Override
    public void glClearStencil(int value) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glClearStencil(value);
            return;
        }
        state.clearStencil(value);
    }

    @Override
    public void glReadPixels(int x, int y, int width, int height, int format, int type, java.nio.ByteBuffer pixels) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glReadPixels(x, y, width, height, format, type, pixels);
            return;
        }
        FrameStates.reportSkippedOnce("glReadPixels",
                "reading pixels back needs a staged copy out of the image and a fence to wait on, and nothing in the"
                        + " backend does that yet; the destination is left as it was");
    }
    @Override
    public void glReadPixels(int x, int y, int width, int height, int format, int type, java.nio.FloatBuffer pixels) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glReadPixels(x, y, width, height, format, type, pixels);
            return;
        }
        FrameStates.reportSkippedOnce("glReadPixels",
                "reading pixels back needs a staged copy out of the image and a fence to wait on, and nothing in the"
                        + " backend does that yet; the destination is left as it was");
    }
    @Override
    public void glReadPixels(int x, int y, int width, int height, int format, int type, java.nio.IntBuffer pixels) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glReadPixels(x, y, width, height, format, type, pixels);
            return;
        }
        FrameStates.reportSkippedOnce("glReadPixels",
                "reading pixels back needs a staged copy out of the image and a fence to wait on, and nothing in the"
                        + " backend does that yet; the destination is left as it was");
    }
    @Override
    public void glGetTexImage(int target, int level, int format, int type, java.nio.ByteBuffer pixels) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glGetTexImage(target, level, format, type, pixels);
            return;
        }
        FrameStates.reportSkippedOnce("glGetTexImage",
                "reading pixels back needs a staged copy out of the image and a fence to wait on, and nothing in the"
                        + " backend does that yet; the destination is left as it was");
    }
    @Override
    public void glGetTexImage(int target, int level, int format, int type, java.nio.IntBuffer pixels) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glGetTexImage(target, level, format, type, pixels);
            return;
        }
        FrameStates.reportSkippedOnce("glGetTexImage",
                "reading pixels back needs a staged copy out of the image and a fence to wait on, and nothing in the"
                        + " backend does that yet; the destination is left as it was");
    }
    @Override
    public void glCopyTexImage2D(int target, int level, int internalFormat, int x, int y, int width, int height, int border) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glCopyTexImage2D(target, level, internalFormat, x, y, width, height, border);
            return;
        }
        FrameStates.reportSkippedOnce("glCopyTexImage2D",
                "reading pixels back needs a staged copy out of the image and a fence to wait on, and nothing in the"
                        + " backend does that yet; the destination is left as it was");
    }
    @Override
    public void glEnableClientState(int array) {
        ClientArrays arrays = arrays();
        if (arrays == null) {
            ShimGl.REAL_GL.glEnableClientState(array);
            return;
        }
        arrays.enable(array);
    }
    @Override
    public void glDisableClientState(int array) {
        ClientArrays arrays = arrays();
        if (arrays == null) {
            ShimGl.REAL_GL.glDisableClientState(array);
            return;
        }
        arrays.disable(array);
    }
    @Override
    public void glVertexPointer(int size, int type, int stride, long offset) {
        ClientArrays arrays = arrays();
        if (arrays == null) {
            ShimGl.REAL_GL.glVertexPointer(size, type, stride, offset);
            return;
        }
        arrays.vertexPointer(size, type, stride, offset);
    }
    @Override
    public void glVertexPointer(int size, int stride, java.nio.FloatBuffer buffer) {
        ClientArrays arrays = arrays();
        if (arrays == null) {
            ShimGl.REAL_GL.glVertexPointer(size, stride, buffer);
            return;
        }
        arrays.vertexPointer(size, stride, buffer);
    }
    @Override
    public void glColorPointer(int size, int type, int stride, long offset) {
        ClientArrays arrays = arrays();
        if (arrays == null) {
            ShimGl.REAL_GL.glColorPointer(size, type, stride, offset);
            return;
        }
        arrays.colorPointer(size, type, stride, offset);
    }
    @Override
    public void glColorPointer(int size, boolean normalized, int stride, java.nio.ByteBuffer buffer) {
        ClientArrays arrays = arrays();
        if (arrays == null) {
            ShimGl.REAL_GL.glColorPointer(size, normalized, stride, buffer);
            return;
        }
        arrays.colorPointer(size, normalized, stride, buffer);
    }
    @Override
    public void glTexCoordPointer(int size, int type, int stride, long offset) {
        ClientArrays arrays = arrays();
        if (arrays == null) {
            ShimGl.REAL_GL.glTexCoordPointer(size, type, stride, offset);
            return;
        }
        arrays.texCoordPointer(size, type, stride, offset);
    }
    @Override
    public void glTexCoordPointer(int size, int stride, java.nio.FloatBuffer buffer) {
        ClientArrays arrays = arrays();
        if (arrays == null) {
            ShimGl.REAL_GL.glTexCoordPointer(size, stride, buffer);
            return;
        }
        arrays.texCoordPointer(size, stride, buffer);
    }
    @Override
    public void glTexCoordPointer(int size, int stride, java.nio.ShortBuffer buffer) {
        ClientArrays arrays = arrays();
        if (arrays == null) {
            ShimGl.REAL_GL.glTexCoordPointer(size, stride, buffer);
            return;
        }
        arrays.texCoordPointer(size, stride, buffer);
    }
    @Override
    public void glNormalPointer(int type, int stride, long offset) {
        ClientArrays arrays = arrays();
        if (arrays == null) {
            ShimGl.REAL_GL.glNormalPointer(type, stride, offset);
            return;
        }
        arrays.normalPointer(type, stride, offset);
    }
    @Override
    public void glNormalPointer(int type, java.nio.ByteBuffer buffer) {
        ClientArrays arrays = arrays();
        if (arrays == null) {
            ShimGl.REAL_GL.glNormalPointer(type, buffer);
            return;
        }
        // No stride in this form: GL's two-argument normal pointer reads a tightly packed array.
        arrays.normalPointer(type, 0, buffer);
    }
    @Override
    public void glDrawArrays(int mode, int first, int count) {
        ClientArrays arrays = arrays();
        if (arrays == null) {
            ShimGl.REAL_GL.glDrawArrays(mode, first, count);
            return;
        }
        arrays.drawArrays(mode, first, count);
    }
    @Override
    public void glBindBufferARB(int target, int buffer) {
        BufferObjects buffers = buffers();
        if (buffers == null) {
            ShimGl.REAL_GL.glBindBufferARB(target, buffer);
            return;
        }
        buffers.bind(target, buffer);
    }
    @Override
    public void glBufferDataARB(int target, java.nio.ByteBuffer data, int usage) {
        BufferObjects buffers = buffers();
        if (buffers == null) {
            ShimGl.REAL_GL.glBufferDataARB(target, data, usage);
            return;
        }
        buffers.data(target, data);
    }
    @Override
    public void glNewList(int list, int glMode) {
        DisplayListBackend lists = lists();
        if (lists == null) {
            ShimGl.REAL_GL.glNewList(list, glMode);
            return;
        }
        lists.begin(list, glMode);
    }

    @Override
    public void glEndList() {
        DisplayListBackend lists = lists();
        if (lists == null) {
            ShimGl.REAL_GL.glEndList();
            return;
        }
        lists.end();
    }

    @Override
    public void glCallList(int list) {
        DisplayListBackend lists = lists();
        if (lists == null) {
            ShimGl.REAL_GL.glCallList(list);
            return;
        }
        lists.call(list);
    }

    @Override
    public void glLineStipple(int factor, short pattern) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glLineStipple(factor, pattern);
            return;
        }
        // A dash pattern applied by the GL rasteriser to line primitives. Lines here are expanded into triangles
        // before anything could stipple them, so the pattern has nowhere to go; the line is drawn solid instead.
        FrameStates.reportSkippedOnce("glLineStipple(" + factor + ")",
                "a dash pattern needs the GL line rasteriser, and lines are triangles here");
    }

    // region ARB_shader_objects
    // Routed to GlShaders rather than to the frame state, because that is the object model the backend already
    // owns: the patched OpenGlHelper writes to the same maps, so a program a mod creates with the ARB names can be
    // the program vanilla draws with, and glUseProgramObjectARB moves the same current-program pointer the draw
    // stream reads. A separate implementation would put the two in different namespaces and every draw would be
    // recorded against the wrong program.

    /**
     * Whether these calls are answered by the backend's shader objects instead of real GL.
     *
     * <p>Deliberately not {@link #state()}: shader objects outlive a frame and are created during mod
     * construction, long before the first frame state exists - the same reason the OpenGlHelper patch asks
     * {@code GlShaders.active()} rather than looking for a frame.</p>
     */
    private static boolean shaders() {
        if (!GlShaders.active()) {
            return false;
        }
        ROUTED.increment();
        return true;
    }

    @Override
    public int glCreateProgramObjectARB() {
        return shaders() ? GlShaders.createProgram() : ShimGl.REAL_GL.glCreateProgramObjectARB();
    }

    @Override
    public int glCreateShaderObjectARB(int type) {
        return shaders() ? GlShaders.createShader(type) : ShimGl.REAL_GL.glCreateShaderObjectARB(type);
    }

    @Override
    public void glShaderSourceARB(int shader, CharSequence source) {
        if (!shaders()) {
            ShimGl.REAL_GL.glShaderSourceARB(shader, source);
            return;
        }
        GlShaders.shaderSource(shader, source == null ? "" : source.toString());
    }

    @Override
    public void glCompileShaderARB(int shader) {
        if (!shaders()) {
            ShimGl.REAL_GL.glCompileShaderARB(shader);
            return;
        }
        GlShaders.compileShader(shader);
    }

    @Override
    public void glAttachObjectARB(int program, int shader) {
        if (!shaders()) {
            ShimGl.REAL_GL.glAttachObjectARB(program, shader);
            return;
        }
        GlShaders.attachShader(program, shader);
    }

    @Override
    public void glLinkProgramARB(int program) {
        if (!shaders()) {
            ShimGl.REAL_GL.glLinkProgramARB(program);
            return;
        }
        GlShaders.linkProgram(program);
    }

    @Override
    public void glValidateProgramARB(int program) {
        if (!shaders()) {
            ShimGl.REAL_GL.glValidateProgramARB(program);
            return;
        }
        // Validation asks whether a program would run in the current GL state. There is no such state to ask
        // about: the pipeline is built from the recorded draw state, so a program that linked is a program that
        // runs. Reported once rather than silently ignored, because a mod that branches on the answer would be
        // branching on nothing.
        FrameStates.reportSkippedOnce("glValidateProgramARB",
                "validation describes GL state, and this backend builds its pipeline from the draw instead");
    }

    @Override
    public void glUseProgramObjectARB(int program) {
        if (!shaders()) {
            ShimGl.REAL_GL.glUseProgramObjectARB(program);
            return;
        }
        GlShaders.useProgram(program);
    }

    @Override
    public void glDeleteObjectARB(int object) {
        if (!shaders()) {
            ShimGl.REAL_GL.glDeleteObjectARB(object);
            return;
        }
        // One name space, so the name decides what is being released. GlShaders hands shaders and programs out of
        // one counter for this reason; with two, a program and a shader could both be 1.
        if (GlShaders.program(object) != null) {
            GlShaders.deleteProgram(object);
        } else {
            GlShaders.deleteShader(object);
        }
    }

    @Override
    public int glGetObjectParameteriARB(int object, int parameterName) {
        if (!shaders()) {
            return ShimGl.REAL_GL.glGetObjectParameteriARB(object, parameterName);
        }
        return switch (parameterName) {
            // The ARB constants are the core ones under another name: GL_OBJECT_COMPILE_STATUS_ARB is 35713 and
            // GL_OBJECT_LINK_STATUS_ARB is 35714, the same values OpenGlHelper's patch passes through.
            case GlShaders.COMPILE_STATUS -> GlShaders.shaderStatus(object, parameterName);
            case GlShaders.LINK_STATUS -> GlShaders.programStatus(object, parameterName);
            default -> {
                FrameStates.reportSkippedOnce("glGetObjectParameteriARB(" + parameterName + ")",
                        "that question about a shader object has no answer here; the status queries do");
                yield 0;
            }
        };
    }

    @Override
    public String glGetInfoLogARB(int object, int maxLength) {
        if (!shaders()) {
            return ShimGl.REAL_GL.glGetInfoLogARB(object, maxLength);
        }
        String log = GlShaders.program(object) != null ? GlShaders.programLog(object)
                : GlShaders.shaderLog(object);
        // glGetInfoLogARB's contract: at most maxLength characters, the whole log when maxLength is 0 or larger
        // than the log. Truncating here rather than in the caller keeps a broken shader from logging a megabyte.
        return maxLength > 0 && log.length() > maxLength ? log.substring(0, maxLength) : log;
    }

    @Override
    public int glGetUniformLocationARB(int program, CharSequence name) {
        if (!shaders()) {
            return ShimGl.REAL_GL.glGetUniformLocationARB(program, name);
        }
        return GlShaders.uniformLocation(program, name);
    }

    @Override
    public void glUniform1iARB(int location, int value) {
        if (!shaders()) {
            ShimGl.REAL_GL.glUniform1iARB(location, value);
            return;
        }
        GlShaders.uniform1i(location, value);
    }

    @Override
    public void glUniform1fARB(int location, float value) {
        if (!shaders()) {
            ShimGl.REAL_GL.glUniform1fARB(location, value);
            return;
        }
        GlShaders.uniform1f(location, value);
    }

    @Override
    public void glUniform3fARB(int location, float x, float y, float z) {
        if (!shaders()) {
            ShimGl.REAL_GL.glUniform3fARB(location, x, y, z);
            return;
        }
        GlShaders.uniform3f(location, x, y, z);
    }
    // endregion

    @Override
    public void glFinish() {
        FrameState state = state();
        if (state == null) {
            GL11.glFinish();
            return;
        }
        FrameStates.reportSkippedOnce("glFinish",
                "draws are submitted with the frame, so there is nothing to synchronise yet");
    }
}
