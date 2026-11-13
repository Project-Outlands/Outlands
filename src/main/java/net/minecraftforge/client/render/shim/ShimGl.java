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
 * its slot with {@code glColor3ub}. The next mod that needs another one adds it here, in
 * {@code net.minecraftforge.lwjgl.shim.FrameShimRules}, and in {@link FrameShim}. {@code FrameShimRulesTest}
 * fails if those three disagree.</p>
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

        void glFinish();
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

    public static void glFinish() {
        impl.glFinish();
    }
}
