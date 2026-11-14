package net.minecraftforge.client.render.shim;

import org.lwjgl.opengl.GL11;

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

    /** One implementation of the facade. */
    public interface Impl {
        void glEnable(int cap);

        void glDisable(int cap);

        void glPushMatrix();

        void glPopMatrix();

        void glTranslatef(float x, float y, float z);

        void glTranslated(double x, double y, double z);

        void glScalef(float x, float y, float z);

        void glRotatef(float angle, float x, float y, float z);

        void glMatrixMode(int mode);

        void glLoadIdentity();

        void glOrtho(double left, double right, double bottom, double top, double zNear, double zFar);

        void glAlphaFunc(int comparison, float reference);

        void glDepthFunc(int comparison);

        void glViewport(int x, int y, int width, int height);

        void glClear(int mask);

        void glColor4f(float red, float green, float blue, float alpha);

        /** {@code glColor3ub}: the byte form of the current colour, with an implied opaque alpha. */
        void glColor3ub(byte red, byte green, byte blue);

        void glNormal3f(float x, float y, float z);

        /** {@code glBegin}: opens the immediate-mode accumulator. */
        void glBegin(int mode);

        /** {@code glEnd}: submits whatever the accumulator collected as one draw. */
        void glEnd();

        /** {@code glVertex3f}: one vertex of the open immediate-mode primitive. */
        void glVertex3f(float x, float y, float z);

        void glLineWidth(float width);

        /** {@code glHint}: a rasterisation hint, which no pipeline has. */
        void glHint(int target, int mode);

        /** {@code glIsEnabled}: whether a capability is currently on. */
        boolean glIsEnabled(int cap);

        /**
         * {@code glGetBoolean}: one boolean of state, by GL enum.
         *
         * <p>Separate from {@link #glIsEnabled} because the enums are not all capabilities: {@code GL_CULL_FACE}
         * is one, but the same call is also used for things like {@code GL_DEPTH_WRITEMASK}, which no
         * {@code glEnable} sets.</p>
         */
        boolean glGetBoolean(int parameterName);

        /** {@code glGetInteger}: one integer of state, by GL enum. */
        int glGetInteger(int parameterName);

        /** {@code glGetFloat}: one float of state, by GL enum. */
        float glGetFloat(int parameterName);

        /** {@code glGetFloatv}: a float vector of state, written at the buffer's position without consuming it. */
        void glGetFloat(int parameterName, java.nio.FloatBuffer out);

        /** {@code glLight}: one parameter of one fixed-function light. */
        void glLight(int light, int parameterName, java.nio.FloatBuffer params);

        /** {@code glPointSize}: the rasterised diameter of a point primitive. */
        void glPointSize(float size);

        /** {@code glStencilFunc}: the test a stencil comparison makes. */
        void glStencilFunc(int comparison, int reference, int mask);

        /** {@code glStencilMask}: which stencil bits a write may change. */
        void glStencilMask(int mask);

        /** {@code glStencilOp}: what a stencil test does on fail, on depth fail and on pass. */
        void glStencilOp(int fail, int depthFail, int depthPass);

        void glFinish();

        /** {@code glBlendFunc}. */
        void glBlendFunc(int glSourceFactor, int glDestinationFactor);
        /** {@code glScissor}. */
        void glScissor(int x, int y, int width, int height);
        /** {@code glDepthMask}. */
        void glDepthMask(boolean enabled);
        /** {@code glShadeModel}. */
        void glShadeModel(int glModel);
        /** {@code glClearColor}. */
        void glClearColor(float red, float green, float blue, float alpha);
        /** {@code glColorMask}. */
        void glColorMask(boolean red, boolean green, boolean blue, boolean alpha);
        /** {@code glPolygonMode}. */
        void glPolygonMode(int glFace, int glMode);
        /** {@code glClearDepth}. */
        void glClearDepth(double depth);
        /** {@code glLogicOp}. */
        void glLogicOp(int glOpcode);
        /** {@code glTexEnvi}. */
        void glTexEnvi(int target, int parameterName, int parameter);
        /** {@code glTexEnvf}. */
        void glTexEnvf(int target, int parameterName, float parameter);
        /** {@code glTexEnv}. */
        void glTexEnv(int target, int parameterName, java.nio.FloatBuffer parameters);
        /** {@code glLoadMatrix}. */
        void glLoadMatrix(java.nio.FloatBuffer matrix);
        /** {@code glLoadMatrixf}. */
        void glLoadMatrixf(java.nio.FloatBuffer matrix);
        /** {@code glMultMatrix}. */
        void glMultMatrix(java.nio.FloatBuffer matrix);
        /** {@code glMultMatrixf}. */
        void glMultMatrixf(java.nio.FloatBuffer matrix);
        /**
         * {@code glMultMatrix} with a double buffer.
         *
         * <p>Its own entry point rather than an overload of the float form, because a retarget may not change a
         * descriptor: the call site passes a {@code DoubleBuffer} and the facade has to accept one.</p>
         */
        void glMultMatrix(java.nio.DoubleBuffer matrix);

        /**
         * {@code glNewList}: starts recording drawing commands into a list.
         *
         * @param glMode {@code GL_COMPILE} (4864); {@code GL_COMPILE_AND_EXECUTE} is not supported
         */
        void glNewList(int list, int glMode);

        /** {@code glEndList}: stops recording. */
        void glEndList();

        /** {@code glCallList}: replays a recorded list. */
        void glCallList(int list);

        /**
         * {@code glLineStipple}: a fixed-function dash pattern for lines.
         *
         * <p>No pipeline equivalent, and not something this backend can imitate: the pattern is applied by the GL
         * rasteriser to line primitives, and lines here are expanded into triangles before anything could stipple
         * them. Reported once rather than silently reaching a GL context that does not exist.</p>
         */
        void glLineStipple(int factor, short pattern);

        // region ARB_shader_objects
        // The same object model as the core entry points OpenGlHelper uses, under the names the ARB extension gave
        // it. A mod that was written against GL 2.0 calls these and nothing else, so without them its shaders never
        // reach the backend at all - which is what Immersive Engineering, immersivepetroleum and Immersive
        // Intelligence all do, in the same order, measured from their constant pools.

        /** {@code glCreateProgramObjectARB}: a new program name. */
        int glCreateProgramObjectARB();

        /** {@code glCreateShaderObjectARB}: a new stage, {@code GL_VERTEX_SHADER_ARB} or {@code GL_FRAGMENT_SHADER_ARB}. */
        int glCreateShaderObjectARB(int type);

        /** {@code glShaderSourceARB}: replaces a stage's source. */
        void glShaderSourceARB(int shader, CharSequence source);

        /** {@code glCompileShaderARB}: compiles a stage, recording the outcome rather than throwing. */
        void glCompileShaderARB(int shader);

        /** {@code glAttachObjectARB}: adds a compiled stage to a program. */
        void glAttachObjectARB(int program, int shader);

        /** {@code glLinkProgramARB}: links a program, recording the outcome. */
        void glLinkProgramARB(int program);

        /** {@code glValidateProgramARB}: asks whether the program would run in the current state. */
        void glValidateProgramARB(int program);

        /** {@code glUseProgramObjectARB}: makes a program current, or none for 0. */
        void glUseProgramObjectARB(int program);

        /** {@code glDeleteObjectARB}: releases a stage or a program, whichever the name belongs to. */
        void glDeleteObjectARB(int object);

        /** {@code glGetObjectParameteriARB}: one integer about a stage or a program, by GL enum. */
        int glGetObjectParameteriARB(int object, int parameterName);

        /** {@code glGetInfoLogARB}: the compile or link log, or the empty string. */
        String glGetInfoLogARB(int object, int maxLength);

        /** {@code glGetUniformLocationARB}: the location a uniform name resolves to, or -1. */
        int glGetUniformLocationARB(int program, CharSequence name);

        /** {@code glUniform1iARB}. */
        void glUniform1iARB(int location, int value);

        /** {@code glUniform1fARB}. */
        void glUniform1fARB(int location, float value);

        /** {@code glUniform3fARB}. */
        void glUniform3fARB(int location, float x, float y, float z);
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
     * @param next the implementation; {@code null} restores {@link #REAL_GL}
     */
    public static void setImpl(Impl next) {
        impl = next == null ? REAL_GL : next;
    }

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
