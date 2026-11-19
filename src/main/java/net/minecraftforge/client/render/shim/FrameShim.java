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
import org.lwjgl.opengl.GL15;

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

    /** {@code GL_TEXTURE_WIDTH}. */
    /** {@code GL_FOG_MODE}: the fog equation (EXP, EXP2, LINEAR). */
    private static final int GL_FOG_MODE = 2917;
    /** {@code GL_FOG_DISTANCE_MODE_NV}: how the fog coordinate is measured, from GL_NV_fog_distance. */
    private static final int GL_FOG_DISTANCE_MODE_NV = 34138;
    /** {@code GL_TEXTURE_2D}: the only target the backend models. */
    private static final int GL_TEXTURE_2D = 3553;
    private static final int GL_TEXTURE_WIDTH = 4096;
    /** {@code GL_TEXTURE_HEIGHT}. */
    private static final int GL_TEXTURE_HEIGHT = 4097;

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

    /**
     * The double form of {@link #glScalef}.
     *
     * <p>Worth its own entry rather than being left to the table: CodeChicken's microblock renderers scale with this
     * one, fifteen call sites, and a scale that reaches real GL is a block drawn at the wrong size rather than a
     * visible error.</p>
     */
    @Override
    public void glScaled(double x, double y, double z) {
        FrameState state = state();
        if (state == null) {
            GL11.glScaled(x, y, z);
            return;
        }
        state.scale((float) x, (float) y, (float) z);
    }

    @Override
    public void glRotated(double angle, double x, double y, double z) {
        FrameState state = state();
        if (state == null) {
            GL11.glRotated(angle, x, y, z);
            return;
        }
        state.rotate((float) angle, (float) x, (float) y, (float) z);
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
    public void glVertex2f(float x, float y) {
        this.glVertex3f(x, y, 0.0F);
    }

    @Override
    public void glVertex3d(double x, double y, double z) {
        this.glVertex3f((float) x, (float) y, (float) z);
    }

    @Override
    public void glVertex2i(int x, int y) {
        this.glVertex3f(x, y, 0.0F);
    }

    @Override
    public void glTexCoord2f(float u, float v) {
        if (state() == null) {
            GL11.glTexCoord2f(u, v);
            return;
        }
        FrameStates.immediate().texCoord(u, v);
    }

    @Override
    public void glColor3f(float red, float green, float blue) {
        this.glColor4f(red, green, blue, 1.0F);
    }

    @Override
    public void glColor4ub(byte red, byte green, byte blue, byte alpha) {
        this.glColor4f((red & 0xFF) / 255.0F, (green & 0xFF) / 255.0F, (blue & 0xFF) / 255.0F,
                (alpha & 0xFF) / 255.0F);
    }

    @Override
    public void glColor4d(double red, double green, double blue, double alpha) {
        this.glColor4f((float) red, (float) green, (float) blue, (float) alpha);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Texture objects. The whole family reaches real GL from a mod that calls it directly - measured: TurretMod's
    // turret camera allocates its own texture with glGenTextures and takes the process down inside glActiveTexture's
    // neighbour, a SIGSEGV with no Java frame to point at. Vanilla's own path already goes through GlStateManager,
    // which is patched, so these forward there rather than keeping a second copy of the same bookkeeping.
    // ---------------------------------------------------------------------------------------------------------------

    @Override
    public int glGenTextures() {
        if (state() == null) {
            return GL11.glGenTextures();
        }
        return net.minecraft.client.renderer.GlStateManager.generateTexture();
    }

    @Override
    public void glDeleteTextures(int texture) {
        if (state() == null) {
            GL11.glDeleteTextures(texture);
            return;
        }
        net.minecraft.client.renderer.GlStateManager.deleteTexture(texture);
    }

    @Override
    public void glBindTexture(int target, int texture) {
        if (state() == null) {
            GL11.glBindTexture(target, texture);
            return;
        }
        if (target != GL_TEXTURE_2D) {
            FrameStates.reportSkippedOnce("glBindTexture(" + target + ")",
                    "only GL_TEXTURE_2D is modelled; the backend has one kind of texture");
            return;
        }
        net.minecraft.client.renderer.GlStateManager.bindTexture(texture);
    }

    @Override
    public void glTexParameteri(int target, int parameterName, int value) {
        if (state() == null) {
            GL11.glTexParameteri(target, parameterName, value);
            return;
        }
        net.minecraft.client.renderer.GlStateManager.glTexParameteri(target, parameterName, value);
    }

    @Override
    public void glTexParameterf(int target, int parameterName, float value) {
        if (state() == null) {
            GL11.glTexParameterf(target, parameterName, value);
            return;
        }
        net.minecraft.client.renderer.GlStateManager.glTexParameterf(target, parameterName, value);
    }

    @Override
    public void glTexImage2D(int target, int level, int internalFormat, int width, int height, int border,
                             int format, int type, java.nio.ByteBuffer pixels) {
        if (state() == null) {
            GL11.glTexImage2D(target, level, internalFormat, width, height, border, format, type, pixels);
            return;
        }
        int[] argb = pixels == null ? null : TexturePixels.read(width, height, format, type, pixels);
        if (pixels != null && argb == null) {
            FrameStates.reportSkippedOnce("glTexImage2D(format " + format + ", type " + type + ")",
                    "that pixel layout is not one the byte contract converts");
            return;
        }
        net.minecraft.client.renderer.GlStateManager.glTexImage2D(target, level, internalFormat, width, height, border,
                format, type, argb == null ? null : java.nio.IntBuffer.wrap(argb));
    }

    @Override
    public void glTexSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height,
                                int format, int type, java.nio.ByteBuffer pixels) {
        if (state() == null) {
            GL11.glTexSubImage2D(target, level, xOffset, yOffset, width, height, format, type, pixels);
            return;
        }
        int[] argb = TexturePixels.read(width, height, format, type, pixels);
        if (argb == null) {
            FrameStates.reportSkippedOnce("glTexSubImage2D(format " + format + ", type " + type + ")",
                    "that pixel layout is not one the byte contract converts");
            return;
        }
        net.minecraft.client.renderer.GlStateManager.glTexSubImage2D(target, level, xOffset, yOffset, width, height,
                format, type, java.nio.IntBuffer.wrap(argb));
    }

    @Override
    public void glPixelStorei(int parameterName, int value) {
        if (state() == null) {
            GL11.glPixelStorei(parameterName, value);
            return;
        }
        // GlStateManager's own body reports it once and says why: the alignment it configures belongs to GL's strided
        // unpack path, and this backend hands its data over tightly packed.
        net.minecraft.client.renderer.GlStateManager.glPixelStorei(parameterName, value);
    }

    @Override
    public void glGenerateMipmap(int target) {
        if (state() == null) {
            org.lwjgl.opengl.GL30.glGenerateMipmap(target);
            return;
        }
        FrameStates.reportSkippedOnce("glGenerateMipmap",
                "mip levels are uploaded, not generated: the backend keeps the levels a mod hands it");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Fog and the double-precision vertex forms. Measured users are Thaumcraft 6 (glFogi/glFogf in its render event
    // handler, and glelwjgl's tessellator for the doubles), Thaumic Attempts (glDepthRange) and TC6's mirror block
    // (glTexGen, sphere mapping).
    // ---------------------------------------------------------------------------------------------------------------

    @Override
    public void glFogi(int pname, int value) {
        FrameState state = state();
        if (state == null) {
            GL11.glFogi(pname, value);
            return;
        }
        if (pname == GL_FOG_MODE) {
            state.fogMode(value);
        } else if (pname == GL_FOG_DISTANCE_MODE_NV) {
            state.fogDistanceMode(value);
        } else {
            // GL_FOG_* integer parameters beyond those two (the coordinate source) have no pipeline equivalent.
            FrameStates.reportSkippedOnce("glFogi(" + pname + ")",
                    "that integer fog parameter has no pipeline equivalent; the mode and distance mode are recorded");
        }
    }

    @Override
    public void glFogf(int pname, float value) {
        FrameState state = state();
        if (state == null) {
            GL11.glFogf(pname, value);
            return;
        }
        state.fog(pname, value);
    }

    @Override
    public void glFog(int pname, java.nio.FloatBuffer params) {
        FrameState state = state();
        if (state == null) {
            GL11.glFogfv(pname, params);
            return;
        }
        state.fog(pname, params);
    }

    @Override
    public void glDepthRange(double near, double far) {
        if (state() == null) {
            GL11.glDepthRange(near, far);
            return;
        }
        // Vulkan's depth range is always [0, 1]; only VK_EXT_depth_range_unrestricted moves it, and nothing here needs
        // that. Thaumic Attempts sets it around one GUI, which draws at the same depth either way.
        FrameStates.reportSkippedOnce("glDepthRange",
                "Vulkan's clip depth always maps to [0, 1]; the range is not a piece of state this backend has");
    }

    @Override
    public void glTexGeni(int coord, int pname, int value) {
        if (state() == null) {
            GL11.glTexGeni(coord, pname, value);
            return;
        }
        // TC6's mirror block asks for GL_SPHERE_MAP, which computes a reflection vector per vertex from the eye-space
        // normal. No stage of this pipeline generates texture coordinates; the mirror draws with the coordinates its
        // vertices already carry, which is the flat texture rather than the reflection.
        FrameStates.reportSkippedOnce("glTexGeni",
                "texture-coordinate generation has no pipeline equivalent; the coordinates come from the vertices");
    }

    @Override
    public void glTexGen(int coord, int pname, java.nio.FloatBuffer params) {
        if (state() == null) {
            GL11.glTexGenfv(coord, pname, params);
            return;
        }
        FrameStates.reportSkippedOnce("glTexGeni",
                "texture-coordinate generation has no pipeline equivalent; the coordinates come from the vertices");
    }

    @Override
    public void glColor3d(double red, double green, double blue) {
        this.glColor4f((float) red, (float) green, (float) blue, 1.0F);
    }

    @Override
    public void glNormal3d(double x, double y, double z) {
        this.glNormal3f((float) x, (float) y, (float) z);
    }

    @Override
    public void glTexCoord2d(double u, double v) {
        this.glTexCoord2f((float) u, (float) v);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Core GL 2.0 shader objects. The ARB block above and this one are the same object model under two sets of names,
    // and the pack has both: Immersive Engineering and Immersive Intelligence use ARB, SmoothFont and CodeChickenLib's
    // ShaderProgram use the core names. Delegating to the ARB implementations keeps one namespace - a second
    // implementation would hand out two kinds of program id and every draw would be recorded against the wrong one.
    // ---------------------------------------------------------------------------------------------------------------

    @Override
    public int glCreateProgram() {
        return this.glCreateProgramObjectARB();
    }

    @Override
    public int glCreateShader(int type) {
        return this.glCreateShaderObjectARB(type);
    }

    @Override
    public void glShaderSource(int shader, CharSequence source) {
        this.glShaderSourceARB(shader, source);
    }

    @Override
    public void glCompileShader(int shader) {
        this.glCompileShaderARB(shader);
    }

    @Override
    public void glAttachShader(int program, int shader) {
        this.glAttachObjectARB(program, shader);
    }

    @Override
    public void glLinkProgram(int program) {
        this.glLinkProgramARB(program);
    }

    @Override
    public void glValidateProgram(int program) {
        this.glValidateProgramARB(program);
    }

    @Override
    public void glUseProgram(int program) {
        this.glUseProgramObjectARB(program);
    }

    @Override
    public void glDeleteProgram(int program) {
        this.glDeleteObjectARB(program);
    }

    @Override
    public void glDeleteShader(int shader) {
        this.glDeleteObjectARB(shader);
    }

    @Override
    public int glGetProgrami(int program, int parameterName) {
        return this.glGetObjectParameteriARB(program, parameterName);
    }

    @Override
    public int glGetShaderi(int shader, int parameterName) {
        return this.glGetObjectParameteriARB(shader, parameterName);
    }

    @Override
    public String glGetProgramInfoLog(int program, int maxLength) {
        return this.glGetInfoLogARB(program, maxLength);
    }

    @Override
    public String glGetShaderInfoLog(int shader, int maxLength) {
        return this.glGetInfoLogARB(shader, maxLength);
    }

    @Override
    public int glGetUniformLocation(int program, CharSequence name) {
        return this.glGetUniformLocationARB(program, name);
    }

    @Override
    public void glUniform1i(int location, int value) {
        this.glUniform1iARB(location, value);
    }

    @Override
    public void glUniform1f(int location, float value) {
        this.glUniform1fARB(location, value);
    }

    @Override
    public void glUniform3f(int location, float v0, float v1, float v2) {
        this.glUniform3fARB(location, v0, v1, v2);
    }

    @Override
    public void glUniform2f(int location, float v0, float v1) {
        if (!shaders()) {
            ShimGl.REAL_GL.glUniform2f(location, v0, v1);
            return;
        }
        FrameStates.reportSkippedOnce("glUniform2f",
                "the two-component float uniform has no facade entry yet; the one, three and four component forms do");
    }

    @Override
    public void glUniform4f(int location, float v0, float v1, float v2, float v3) {
        if (!shaders()) {
            ShimGl.REAL_GL.glUniform4f(location, v0, v1, v2, v3);
            return;
        }
        FrameStates.reportSkippedOnce("glUniform4f",
                "the four-component float uniform has no facade entry yet; the one and three component forms do");
    }

    @Override
    public void glUniform2i(int location, int v0, int v1) {
        this.glUniform2iARB(location, v0, v1);
    }

    @Override
    public void glUniform3i(int location, int v0, int v1, int v2) {
        this.glUniform3iARB(location, v0, v1, v2);
    }

    @Override
    public void glUniform4i(int location, int v0, int v1, int v2, int v3) {
        this.glUniform4iARB(location, v0, v1, v2, v3);
    }

    @Override
    public void glUniformMatrix4(int location, boolean transpose, java.nio.FloatBuffer value) {
        if (!shaders()) {
            ShimGl.REAL_GL.glUniformMatrix4(location, transpose, value);
            return;
        }
        FrameStates.reportSkippedOnce("glUniformMatrix4",
                "matrix uniforms are not carried through the facade yet");
    }

    @Override
    public void glUniform2iARB(int location, int v0, int v1) {
        if (!shaders()) {
            ShimGl.REAL_GL.glUniform2iARB(location, v0, v1);
            return;
        }
        FrameStates.reportSkippedOnce("glUniform2iARB", "the two-component integer uniform is not carried yet");
    }

    @Override
    public void glUniform3iARB(int location, int v0, int v1, int v2) {
        if (!shaders()) {
            ShimGl.REAL_GL.glUniform3iARB(location, v0, v1, v2);
            return;
        }
        FrameStates.reportSkippedOnce("glUniform3iARB", "the three-component integer uniform is not carried yet");
    }

    @Override
    public void glUniform4iARB(int location, int v0, int v1, int v2, int v3) {
        if (!shaders()) {
            ShimGl.REAL_GL.glUniform4iARB(location, v0, v1, v2, v3);
            return;
        }
        FrameStates.reportSkippedOnce("glUniform4iARB", "the four-component integer uniform is not carried yet");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Buffer objects by their GL 1.5 names, which is where IvToolkit's model loader uploads its geometry. The store is
    // the one the client-array path already reads offsets out of, so the draw that follows sees these bytes.
    // ---------------------------------------------------------------------------------------------------------------

    @Override
    public int glGenBuffers() {
        net.minecraftforge.client.render.state.backend.BufferObjects buffers =
                net.minecraftforge.client.render.state.backend.BufferObjects.forNonGl();
        if (buffers == null) {
            return GL15.glGenBuffers();
        }
        return buffers.generate(1);
    }

    @Override
    public void glBindBuffer(int target, int buffer) {
        net.minecraftforge.client.render.state.backend.BufferObjects buffers =
                net.minecraftforge.client.render.state.backend.BufferObjects.forNonGl();
        if (buffers == null) {
            GL15.glBindBuffer(target, buffer);
            return;
        }
        buffers.bind(target, buffer);
    }

    @Override
    public void glBufferData(int target, java.nio.ByteBuffer data, int usage) {
        net.minecraftforge.client.render.state.backend.BufferObjects buffers =
                net.minecraftforge.client.render.state.backend.BufferObjects.forNonGl();
        if (buffers == null) {
            GL15.glBufferData(target, data, usage);
            return;
        }
        buffers.data(target, data);
    }

    @Override
    public void glDeleteBuffers(int buffer) {
        net.minecraftforge.client.render.state.backend.BufferObjects buffers =
                net.minecraftforge.client.render.state.backend.BufferObjects.forNonGl();
        if (buffers == null) {
            GL15.glDeleteBuffers(buffer);
            return;
        }
        buffers.delete(buffer, 1);
    }

    @Override
    public void glGetInteger(int parameterName, java.nio.IntBuffer values) {
        FrameState state = state();
        if (state == null) {
            GL11.glGetIntegerv(parameterName, values);
            return;
        }
        // One value at a time through the same switch the scalar form uses, so the two cannot disagree. The scalar
        // form is what modularui's projection code would have called had it known the count.
        values.put(values.position(), this.glGetInteger(parameterName));
    }

    @Override
    public int glGetError() {
        if (state() == null) {
            return GL11.glGetError();
        }
        // There is no error queue behind a recorded frame, and reporting a stale one would be worse than none: the
        // mods that check it (SmoothFont) use it to decide whether their shader compiled, and that question is
        // answered by the status query instead.
        return 0;
    }

    @Override
    public String glGetString(int name) {
        if (state() == null) {
            return GL11.glGetString(name);
        }
        return net.minecraftforge.client.render.GlAnswers.glString(name);
    }

    @Override
    public void glDrawBuffer(int mode) {
        if (state() == null) {
            GL11.glDrawBuffer(mode);
            return;
        }
        FrameStates.reportSkippedOnce("glDrawBuffer",
                "a draw goes to the target's only colour attachment; there is no second buffer to select");
    }

    @Override
    public void glVertex2d(double x, double y) {
        this.glVertex3f((float) x, (float) y, 0.0F);
    }

    @Override
    public void glFrustum(double left, double right, double bottom, double top, double near, double far) {
        if (state() == null) {
            GL11.glFrustum(left, right, bottom, top, near, far);
            return;
        }
        // modularui's schema renderer builds a perspective projection this way. The matrix stack is recorded, so this
        // is a composition like glOrtho's - reported until it has one, because drawing with the wrong projection is a
        // picture that looks broken rather than an error that says so.
        FrameStates.reportSkippedOnce("glFrustum",
                "the perspective block of the fixed-function projection is not composed into the recorded matrix yet");
    }

    @Override
    public void glClipPlane(int plane, java.nio.DoubleBuffer equation) {
        if (state() == null) {
            GL11.glClipPlane(plane, equation);
            return;
        }
        // Immersive Intelligence's AMT item clips its model with this. No stage of the pipeline has a user clip plane,
        // so the model is drawn whole rather than not at all.
        FrameStates.reportSkippedOnce("glClipPlane",
                "a user clip plane has no pipeline equivalent here, so the geometry is drawn unclipped");
    }

    @Override
    public void glFlush() {
        if (state() == null) {
            GL11.glFlush();
            return;
        }
        // Same answer as glFinish: the frame is submitted whole, so there is nothing queued to start.
        FrameStates.reportSkippedOnce("glFlush", "draws are submitted with the frame, so there is nothing to start");
    }

    @Override
    public int glGetTexEnvi(int target, int parameterName) {
        if (state() == null) {
            return GL11.glGetTexEnvi(target, parameterName);
        }
        FrameStates.reportSkippedOnce("glGetTexEnvi",
                "the fixed-function texture environment is recorded as a draw's state, not as a value to read back");
        return 0;
    }

    @Override
    public float glGetTexEnvf(int target, int parameterName) {
        if (state() == null) {
            return GL11.glGetTexEnvf(target, parameterName);
        }
        FrameStates.reportSkippedOnce("glGetTexEnvf",
                "the fixed-function texture environment is recorded as a draw's state, not as a value to read back");
        return 0.0F;
    }

    @Override
    public int glGetTexParameteri(int target, int parameterName) {
        if (state() == null) {
            return GL11.glGetTexParameteri(target, parameterName);
        }
        // journeymap asks a texture for its wrap mode before copying it. The parameter store records what mods set, but
        // has no reader yet, so the answer is GL's default rather than a guess at what was set.
        FrameStates.reportSkippedOnce("glGetTexParameteri",
                "sampler parameters are recorded but not readable yet; the answer is the GL default");
        return 0;
    }

    @Override
    public void glGenTextures(java.nio.IntBuffer textures) {
        net.minecraftforge.client.render.texture.TextureBackend backend =
                net.minecraftforge.client.render.texture.TextureBackends.forNonGl();
        if (backend == null) {
            GL11.glGenTextures(textures);
            return;
        }
        for (int i = 0; i < textures.remaining(); i++) {
            textures.put(textures.position() + i, backend.create());
        }
    }

    @Override
    public void glDeleteTextures(java.nio.IntBuffer textures) {
        net.minecraftforge.client.render.texture.TextureBackend backend =
                net.minecraftforge.client.render.texture.TextureBackends.forNonGl();
        if (backend == null) {
            GL11.glDeleteTextures(textures);
            return;
        }
        for (int i = 0; i < textures.remaining(); i++) {
            backend.delete(textures.get(textures.position() + i));
        }
    }

    @Override
    public void glTexImage2D(int target, int level, int internalFormat, int width, int height, int border,
                             int format, int type, java.nio.IntBuffer pixels) {
        if (state() == null) {
            GL11.glTexImage2D(target, level, internalFormat, width, height, border, format, type, pixels);
            return;
        }
        // Already ARGB ints: the same contract the texture backend speaks, and the same call vanilla's patched
        // GlStateManager.glTexImage2D makes.
        net.minecraft.client.renderer.GlStateManager.glTexImage2D(target, level, internalFormat, width, height, border,
                format, type, pixels);
    }

    @Override
    public void glTexSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height,
                                int format, int type, java.nio.IntBuffer pixels) {
        if (state() == null) {
            GL11.glTexSubImage2D(target, level, xOffset, yOffset, width, height, format, type, pixels);
            return;
        }
        net.minecraft.client.renderer.GlStateManager.glTexSubImage2D(target, level, xOffset, yOffset, width, height,
                format, type, pixels);
    }

    @Override
    public void glTexImage1D(int target, int level, int internalFormat, int width, int border, int format,
                             int type, java.nio.FloatBuffer pixels) {
        if (state() == null) {
            GL11.glTexImage1D(target, level, internalFormat, width, border, format, type, pixels);
            return;
        }
        // LibrarianLib's Gaussian blur uses this for its lookup table. One-dimensional textures are not modelled, so
        // the blur samples nothing rather than the process dying.
        FrameStates.reportSkippedOnce("glTexImage1D", "one-dimensional textures are not modelled");
    }

    @Override
    public void glActiveTexture(int unit) {
        if (state() == null) {
            org.lwjgl.opengl.GL13.glActiveTexture(unit);
            return;
        }
        // Through GlStateManager so its own counter stays in step, exactly like OpenGlHelper.setActiveTexture.
        net.minecraft.client.renderer.GlStateManager.setActiveTexture(unit);
    }

    @Override
    public void glBlendFuncSeparate(int sourceRgb, int destinationRgb, int sourceAlpha, int destinationAlpha) {
        if (state() == null) {
            org.lwjgl.opengl.GL14.glBlendFuncSeparate(sourceRgb, destinationRgb, sourceAlpha, destinationAlpha);
            return;
        }
        net.minecraft.client.renderer.GlStateManager.tryBlendFuncSeparate(sourceRgb, destinationRgb, sourceAlpha,
                destinationAlpha);
    }

    @Override
    public void glBlendEquationSeparate(int modeRgb, int modeAlpha) {
        FrameState state = state();
        if (state == null) {
            org.lwjgl.opengl.GL20.glBlendEquationSeparate(modeRgb, modeAlpha);
            return;
        }
        if (modeRgb != modeAlpha) {
            FrameStates.reportSkippedOnce("glBlendEquationSeparate",
                    "colour and alpha share one blend equation here; the colour equation is the one recorded");
        }
        state.blendEquation(modeRgb);
    }

    @Override
    public void glDetachShader(int program, int shader) {
        if (!shaders()) {
            org.lwjgl.opengl.GL20.glDetachShader(program, shader);
            return;
        }
        FrameStates.reportSkippedOnce("glDetachShader",
                "shader objects are reference-counted by deletion, so detaching changes nothing here");
    }

    @Override
    public String glGetActiveUniform(int program, int index, int maxLength) {
        if (!shaders()) {
            return org.lwjgl.opengl.GL20.glGetActiveUniform(program, index, maxLength,
                    java.nio.IntBuffer.allocate(1), java.nio.IntBuffer.allocate(1));
        }
        FrameStates.reportSkippedOnce("glGetActiveUniform",
                "uniforms are looked up by name; enumerating them is not modelled");
        return "";
    }

    @Override
    public int glGetActiveUniformType(int program, int index) {
        if (!shaders()) {
            return org.lwjgl.opengl.GL31.glGetActiveUniformsi(program, index, 0x8B86);
        }
        FrameStates.reportSkippedOnce("glGetActiveUniformType",
                "uniforms are looked up by name; enumerating them is not modelled");
        return 0;
    }

    @Override
    public int glGetActiveUniformSize(int program, int index) {
        if (!shaders()) {
            return org.lwjgl.opengl.GL31.glGetActiveUniformsi(program, index, 0x8B87);
        }
        FrameStates.reportSkippedOnce("glGetActiveUniformSize",
                "uniforms are looked up by name; enumerating them is not modelled");
        return 0;
    }

    @Override
    public void glUniformMatrix2(int location, boolean transpose, java.nio.FloatBuffer value) {
        if (!shaders()) {
            org.lwjgl.opengl.GL20.glUniformMatrix2fv(location, transpose, value);
            return;
        }
        FrameStates.reportSkippedOnce("glUniformMatrix2", "matrix uniforms are not carried through the facade yet");
    }

    @Override
    public void glUniform2fARB(int location, float v0, float v1) {
        this.glUniform2f(location, v0, v1);
    }

    @Override
    public void glUniform4fARB(int location, float v0, float v1, float v2, float v3) {
        this.glUniform4f(location, v0, v1, v2, v3);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // GL 3.0 framebuffer objects by their core names. Pregenerator's preview texture is the only caller in the pack,
    // and the answers are OpenGlHelper's patch's answers: a framebuffer a mod creates has no render target here.
    // ---------------------------------------------------------------------------------------------------------------

    @Override
    public int glGenFramebuffers() {
        if (state() == null) {
            return org.lwjgl.opengl.GL30.glGenFramebuffers();
        }
        FrameStates.reportSkippedOnce("glGenFramebuffers",
                "a framebuffer created by the mod has no render target here, so it cannot be rendered into");
        return 0;
    }

    @Override
    public void glBindFramebuffer(int target, int framebuffer) {
        if (state() == null) {
            org.lwjgl.opengl.GL30.glBindFramebuffer(target, framebuffer);
            return;
        }
        FrameStates.reportSkippedOnce("glBindFramebuffer",
                "a framebuffer the mod created has no render target here, so the binding is ignored");
    }

    @Override
    public void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
        if (state() == null) {
            org.lwjgl.opengl.GL30.glFramebufferTexture2D(target, attachment, textarget, texture, level);
            return;
        }
        FrameStates.reportSkippedOnce("glFramebufferTexture2D", "nothing to attach it to: see glGenFramebuffers");
    }

    @Override
    public void glDeleteFramebuffers(int framebuffer) {
        if (state() == null) {
            org.lwjgl.opengl.GL30.glDeleteFramebuffers(framebuffer);
            return;
        }
        // Nothing was created, so nothing to release.
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



    @Override
    public void glBlendColor(float red, float green, float blue, float alpha) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glBlendColor(red, green, blue, alpha);
            return;
        }
        state.blendColor(red, green, blue, alpha);
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

    /**
     * {@code glReadPixels}: the framebuffer into the caller's buffer.
     *
     * <p>Recorded at its place in the frame, like a framebuffer copy, because the backend has not drawn anything yet
     * when the game asks. The copy therefore sees exactly what GL would - the draws before it, none after - and the
     * buffer is filled once the frame's fence signals.</p>
     */
    @Override
    public void glReadPixels(int x, int y, int width, int height, int format, int type, java.nio.ByteBuffer pixels) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glReadPixels(x, y, width, height, format, type, pixels);
            return;
        }
        if (pixels == null || !net.minecraftforge.client.render.vertex.FrameDraws.recordReadPixels(x, y, width, height,
                format, type, pixels)) {
            FrameStates.reportSkippedOnce("glReadPixels outside a frame",
                    "there is no framebuffer to read from, so the destination is left as it was");
        }
    }
    @Override
    public void glReadPixels(int x, int y, int width, int height, int format, int type, java.nio.FloatBuffer pixels) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glReadPixels(x, y, width, height, format, type, pixels);
            return;
        }
        FrameStates.reportSkippedOnce("glReadPixels(FloatBuffer/IntBuffer)",
                "the byte form is the one the pack uses; the typed forms would need the same copy plus a widening");
    }
    @Override
    public void glReadPixels(int x, int y, int width, int height, int format, int type, java.nio.IntBuffer pixels) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glReadPixels(x, y, width, height, format, type, pixels);
            return;
        }
        FrameStates.reportSkippedOnce("glReadPixels(FloatBuffer/IntBuffer)",
                "the byte form is the one the pack uses; the typed forms would need the same copy plus a widening");
    }
    /**
     * {@code glGetTexImage}: a texture's pixels, answered from the copy the backend uploaded.
     *
     * <p><b>Why this one needs no read-back path at all.</b> A texture's pixels arrive from the game - the backend is
     * the thing that received them - so they are already in host memory as {@code TextureImage.levels}. Reading a
     * texture is therefore a format conversion, not a GPU copy, and it can answer synchronously like GL does. The
     * mods that ask are exporters and icon builders: Bookshelf, LibrarianLib's {@code TextureMapExporter}, Quark's
     * {@code TextureDump} and TurretMod.</p>
     */
    @Override
    public void glGetTexImage(int target, int level, int format, int type, java.nio.ByteBuffer pixels) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glGetTexImage(target, level, format, type, pixels);
            return;
        }
        if (pixels == null) {
            return;
        }
        net.minecraftforge.client.render.texture.TextureBackend textures =
                net.minecraftforge.client.render.texture.TextureBackends.forNonGl();
        net.minecraftforge.client.render.texture.TextureImage image =
                textures == null ? null : textures.image(textures.boundTexture());
        if (image == null || level < 0 || level >= image.levelCount()) {
            FrameStates.reportSkippedOnce("glGetTexImage(level " + level + ")",
                    "only the mip levels the game itself uploaded are kept here, and that level is not one of them");
            return;
        }
        int[] argb = image.level(level);
        int width = image.levelWidth(level);
        int height = image.levelHeight(level);
        int at = pixels.position();
        if (!TexturePixels.write(argb, width, height, format, type, pixels, at)) {
            FrameStates.reportSkippedOnce("glGetTexImage(format " + format + ", type " + type + ")",
                    "only the RGBA and BGRA byte forms are converted here, which is what the pack's exporters ask for");
        }
    }

    @Override
    public void glGetTexImage(int target, int level, int format, int type, java.nio.IntBuffer pixels) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glGetTexImage(target, level, format, type, pixels);
            return;
        }
        if (pixels == null) {
            return;
        }
        net.minecraftforge.client.render.texture.TextureBackend textures =
                net.minecraftforge.client.render.texture.TextureBackends.forNonGl();
        net.minecraftforge.client.render.texture.TextureImage image =
                textures == null ? null : textures.image(textures.boundTexture());
        if (image == null || level < 0 || level >= image.levelCount()) {
            // A render target's attachment is not one of these: its pixels were written by drawing, so they are only
            // on the device. The world icon is the caller that hits this, and it is routed earlier for exactly that
            // reason - see WorldIcon.
            FrameStates.reportSkippedOnce("glGetTexImage(IntBuffer, level " + level + ")",
                    "these pixels were written by drawing rather than uploaded, so they are only on the device");
            return;
        }
        if (!TexturePixels.writeInts(image.level(level), image.levelWidth(level), image.levelHeight(level),
                format, type, pixels, pixels.position())) {
            FrameStates.reportSkippedOnce("glGetTexImage(IntBuffer, format " + format + ", type " + type + ")",
                    "only the BGRA and RGBA byte forms are converted here, which is what the game's own screenshot"
                            + " helper and the pack's exporters ask for");
        }
    }
    /**
     * {@code glReadBuffer}: which attachment a later {@code glReadPixels} reads from.
     *
     * <p>Recorded nowhere, because the only reader is {@code glReadPixels} - which this backend does not implement
     * yet, and reports as such. Answering the call instead of letting it reach real GL is the point: in a Vulkan
     * window there is no GL context behind it, so a mod that asks FTBLib's question would take the process down.</p>
     */
    @Override
    public void glReadBuffer(int mode) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glReadBuffer(mode);
            return;
        }
        FrameStates.reportSkippedOnce("glReadBuffer",
                "it selects the attachment a read-back would use, and read-back is not implemented yet");
    }

    /**
     * {@code glBlitFramebuffer}.
     *
     * <p>Caught rather than passed through, which is the whole point: journeymap calls it, and a call into
     * {@code liblwjgl_opengl} with no GL context is a segfault, not an exception - the same shape as the crash this
     * backend already had once. A real implementation is a copy between attachments, which the render target layer
     * already knows how to do for {@code glCopyTexSubImage2D}.</p>
     */
    @Override
    public void glBlitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1,
                                  int dstY1, int mask, int filter) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glBlitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
            return;
        }
        FrameStates.reportSkippedOnce("glBlitFramebuffer",
                "a blit between attachments needs the render target layer's copy path, which only"
                        + " glCopyTexSubImage2D uses today; nothing is copied");
    }

    /**
     * {@code glGetTexLevelParameteri}: the size of a texture the game uploaded.
     *
     * <p><b>Answered rather than refused.</b> Bookshelf and journeymap ask the bound texture for its width and height
     * before copying it, and a 0 there is a wrong answer that makes them skip the feature - while the backend has the
     * size, because it is the thing that uploaded the pixels. Only level 0 and only the size queries are answered;
     * anything else reports once, since a wrong number is worse than a reported gap.</p>
     */
    @Override
    public int glGetTexLevelParameteri(int target, int level, int parameterName) {
        FrameState state = state();
        if (state == null) {
            return ShimGl.REAL_GL.glGetTexLevelParameteri(target, level, parameterName);
        }
        net.minecraftforge.client.render.texture.TextureBackend textures =
                net.minecraftforge.client.render.texture.TextureBackends.forNonGl();
        if (textures != null && level >= 0) {
            net.minecraftforge.client.render.texture.TextureImage image = textures.image(textures.boundTexture());
            if (image != null && level < image.levelCount()) {
                return switch (parameterName) {
                    case GL_TEXTURE_WIDTH -> image.levelWidth(level);
                    case GL_TEXTURE_HEIGHT -> image.levelHeight(level);
                    default -> 0;
                };
            }
        }
        FrameStates.reportSkippedOnce("glGetTexLevelParameteri(" + parameterName + ")",
                "only the width and height of a level the game uploaded are known here; anything else would be a guess");
        return 0;
    }

    /**
     * {@code glCopyTexImage2D}: the framebuffer into a texture, allocating it first.
     *
     * <p>The same copy {@code glCopyTexSubImage2D} already does - which is what makes the main menu's panorama blur
     * work - with one difference: the destination has to be created at the source's size before it can be written.
     * That is exactly what TurretMod's turret camera needs: render the camera's view, copy it into a texture, and draw
     * that texture on the monitor block.</p>
     */
    @Override
    public void glCopyTexImage2D(int target, int level, int internalFormat, int x, int y, int width, int height, int border) {
        FrameState state = state();
        if (state == null) {
            ShimGl.REAL_GL.glCopyTexImage2D(target, level, internalFormat, x, y, width, height, border);
            return;
        }
        net.minecraftforge.client.render.texture.TextureBackend textures =
                net.minecraftforge.client.render.texture.TextureBackends.forNonGl();
        if (textures == null || textures.boundTexture() == 0) {
            FrameStates.reportSkippedOnce("glCopyTexImage2D with no texture bound",
                    "the copy has no destination");
            return;
        }
        if (level != 0) {
            FrameStates.reportSkippedOnce("glCopyTexImage2D(level " + level + ")",
                    "only level 0 is a destination for a framebuffer copy here");
            return;
        }
        // Allocate, then copy into it at the origin: GL defines this call as "create the level from the framebuffer".
        textures.upload(textures.boundTexture(), 0, width, height, null);
        textures.copyFromFramebuffer(0, 0, 0, x, y, width, height);
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
        // Validation asks whether a program would run in the current GL state. There is no such state to ask about:
        // the pipeline is built from the recorded draw state, so a program that linked is a program that runs - and
        // that is what GL_OBJECT_VALIDATE_STATUS_ARB answers below. Reported once, because a mod that branches on the
        // answer now gets "valid" and should be told why that is the whole truth here.
        GlShaders.validateProgram(program);
        FrameStates.reportSkippedOnce("glValidateProgramARB",
                "validation describes GL state, and this backend builds its pipeline from the draw instead, so the"
                        + " validate status answers the link status");
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
            // One namespace for both kinds of object, so the query asks whichever has it.
            case GlShaders.INFO_LOG_LENGTH -> GlShaders.objectLogLength(object);
            case GlShaders.LINK_STATUS, GlShaders.VALIDATE_STATUS -> GlShaders.programStatus(object, parameterName);
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
