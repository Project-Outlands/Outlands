package net.minecraftforge.client.render.shim;

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
    public int glGetInteger(int parameterName) {
        FrameState state = state();
        if (state == null) {
            return ShimGl.REAL_GL.glGetInteger(parameterName);
        }
        return switch (parameterName) {
            case 2979 -> state.matrixStackDepth();     // GL_MODELVIEW_STACK_DEPTH
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
        if (state() == null) {
            ShimGl.REAL_GL.glStencilFunc(comparison, reference, mask);
            return;
        }
        skipStencil("glStencilFunc");
    }

    @Override
    public void glStencilMask(int mask) {
        if (state() == null) {
            ShimGl.REAL_GL.glStencilMask(mask);
            return;
        }
        skipStencil("glStencilMask");
    }

    @Override
    public void glStencilOp(int fail, int depthFail, int depthPass) {
        if (state() == null) {
            ShimGl.REAL_GL.glStencilOp(fail, depthFail, depthPass);
            return;
        }
        skipStencil("glStencilOp");
    }

    /**
     * The stencil family: recorded nowhere yet, because nothing consumes it.
     *
     * <p>The swapchain already has a stencil attachment ({@code D32_SFLOAT_S8_UINT}), so what is missing is the
     * pipeline state and the pass that sets it up, not an attachment. Until then a draw that asked to be clipped by
     * the stencil is drawn unclipped: visible, and reported once, rather than a crash.</p>
     */
    private static void skipStencil(String operation) {
        FrameStates.reportSkippedOnce("stencil (" + operation + ")",
                "stencil state is not part of the pipeline yet, so geometry that asked to be clipped by it is"
                        + " drawn unclipped");
    }

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
