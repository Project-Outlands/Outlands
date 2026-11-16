package net.minecraftforge.lwjgl.shim;

import java.util.List;
import java.util.Set;

/**
 * The ruleset that routes mod GL call sites to {@code net.minecraftforge.client.render.shim.ShimGl}.
 *
 * <p><b>Why the entries are here and not next to the facade.</b> Registration has to happen during the coremod
 * stage, before the first mod class loads, and rewriting is frozen at that moment. A ruleset that lived in the
 * renderer package would make the class-loading layer depend on the renderer, which is the reverse of the
 * direction everything else runs in. Only internal names cross the boundary, so nothing is lost: the facade is
 * named by string and the compiler cannot check the pairing — which is what
 * {@code FrameShimRulesTest} exists for.</p>
 *
 * <p><b>Why exactly these and nothing else.</b> Every entry was measured by scanning a mod's constant pool for
 * the call sites it actually has: IC2Classic 1.12-1.5.8 gave the matrix stack and the state bits, JEI the
 * immediate-mode path, Baubles one colour form, Immersive Engineering its queries and the raster/stencil family,
 * and the ARB block the shader objects of Immersive Engineering, immersivepetroleum and Immersive Intelligence.
 * Adding an entry is cheap, but an entry whose facade method does not exist fails as a
 * {@code NoSuchMethodError} on first execution rather than at load time, so the set is kept to what has been
 * measured and the bijection test keeps it honest.</p>
 *
 * <p><b>Why these are RETARGETs and not table entries.</b> LWJGL3 still ships all of these as natives in its own
 * {@code org.lwjgl.opengl.GL11}, so they are not missing members and nothing about them is broken at link time:
 * they link, execute against real GL, and silently do nothing to the frame state. That is the failure this
 * ruleset exists to close.</p>
 *
 * @see net.minecraftforge.client.render.shim.ShimGl
 */
public final class FrameShimRules implements ShimRewriteRules {

    /** Stable id, used in configuration and logs. */
    public static final String ID = "vulkan-frame";

    /**
     * The backend id this ruleset applies to.
     *
     * <p>Spelled out rather than imported: {@code VulkanBackend.ID} lives in the renderer package, which this
     * layer must not depend on. {@code FrameShimRulesTest} asserts the two agree, so the duplication cannot
     * drift.</p>
     */
    public static final String VULKAN_ID = "vulkan";

    /** The class the plain entries retarget away from. */
    public static final String GL11 = "org/lwjgl/opengl/GL11";

    /**
     * The other class this ruleset retargets away from: the ARB extension's shader object calls.
     *
     * <p>A mod written against GL 2.0 uses these and not the core names, so the same facade has to be reachable
     * from both owners. Three mods in the pack (Immersive Engineering, immersivepetroleum, Immersive Intelligence)
     * call this class and nothing else for shaders, with the same sequence of fifteen methods.</p>
     */
    public static final String ARB_SHADER_OBJECTS = "org/lwjgl/opengl/ARBShaderObjects";

    /**
     * The buffer-object extension's class, which LWJGL 3 no longer ships.
     *
     * <p>Its calls are aliased to {@code GL15} by the rewrite table, but the shim runs before that table, so a call
     * site still says {@code ARBBufferObject} when it gets here. The facade's real-GL body is where the alias
     * happens instead.</p>
     */
    public static final String ARB_BUFFER_OBJECT = "org/lwjgl/opengl/ARBBufferObject";

    /** GL 3.0's class, where {@code glBlitFramebuffer} lives in the LWJGL 2 the mods were compiled against. */
    public static final String GL30 = "org/lwjgl/opengl/GL30";

    /** GL 2.0's class: the core shader objects, which SmoothFont and CodeChickenLib use instead of the ARB names. */
    public static final String GL20 = "org/lwjgl/opengl/GL20";

    /** GL 1.3's class, where {@code glActiveTexture} lives; SmoothFont's font shader binds its texture this way. */
    public static final String GL13 = "org/lwjgl/opengl/GL13";

    /** GL 1.5's class: buffer objects by their core names, which is what IvToolkit's model loader calls. */
    public static final String GL15 = "org/lwjgl/opengl/GL15";

    /** GL 1.2's class, where {@code glBlendColor} lives in the LWJGL 2 the mods were compiled against. */
    public static final String GL14 = "org/lwjgl/opengl/GL14";

    /** The facade every entry retargets to. */
    public static final String FACADE = "net/minecraftforge/client/render/shim/ShimGl";

    /**
     * The single instance. Registering the same instance twice is a no-op in {@link ShimRegistry}, which is what
     * lets the coremod stage and a test both call {@link #registerBuiltin()} without ordering rules.
     */
    public static final FrameShimRules INSTANCE = new FrameShimRules();

    private static final List<RetargetEntry> ENTRIES = List.of(
            // Matrix stack: 45 of IC2C's 63 sites, and the largest single group in every other mod surveyed.
            entry("glPushMatrix", "()V"),
            // The double and integer spellings of the calls above. They were absent because the first survey counted
            // the float forms; the pack scan found these next to them - glScaled 15 sites in CodeChicken's microblock
            // renderers, glRotated 7, glVertex2f 10 in modernsplash, glVertex2i 3 in JEI Utilities, glVertex3d 4.
            entry("glScaled", "(DDD)V"),
            entry("glRotated", "(DDDD)V"),
            entry("glVertex2f", "(FF)V"),
            entry("glVertex3d", "(DDD)V"),
            entry("glVertex2i", "(II)V"),
            entry("glTexCoord2f", "(FF)V"),
            entry("glColor3f", "(FFF)V"),
            entry("glColor4ub", "(BBBB)V"),
            entry("glColor4d", "(DDDD)V"),
            entry("glPopMatrix", "()V"),
            entry("glTranslatef", "(FFF)V"),
            entry("glTranslated", "(DDD)V"),
            entry("glScalef", "(FFF)V"),
            entry("glRotatef", "(FFFF)V"),
            // Matrix mode and the error screen's own projection: only IC2C's fatal-error path uses these, but they
            // are the same three lines as the rest, and leaving them out would make that path render nowhere.
            entry("glMatrixMode", "(I)V"),
            entry("glLoadIdentity", "()V"),
            entry("glOrtho", "(DDDDDD)V"),
            // State bits. GL_RESCALE_NORMAL (32826) is in the set for a reason: IC2C enables it around two entity
            // renderers, and it is a fixed-function concept with no pipeline equivalent, so it must be recorded
            // rather than left to reach real GL.
            entry("glEnable", "(I)V"),
            entry("glDisable", "(I)V"),
            entry("glAlphaFunc", "(IF)V"),
            entry("glDepthFunc", "(I)V"),
            entry("glViewport", "(IIII)V"),
            entry("glClear", "(I)V"),
            // Current colour and current normal. Colour is real state; normal has no consumer without lighting.
            entry("glColor4f", "(FFFF)V"),
            entry("glNormal3f", "(FFF)V"),
            // Immediate mode and line state: JEI's ghost-ingredient drag draws the line from the source slot to the
            // cursor with glBegin/glVertex3f/glEnd, and asks for a thin, smooth line on the way in.
            entry("glBegin", "(I)V"),
            entry("glEnd", "()V"),
            entry("glVertex3f", "(FFF)V"),
            entry("glLineWidth", "(F)V"),
            entry("glHint", "(II)V"),
            // Baubles tints its inventory slot with the byte form of the current colour.
            entry("glColor3ub", "(BBB)V"),
            // Immersive Engineering asks what the state already is rather than setting it: the Tesla coil saves and
            // restores the line width, its manual page unwinds the matrix stack by reading its depth, its text
            // renderer restores the current colour, and two renderers branch on whether lighting or texturing is on.
            entry("glIsEnabled", "(I)Z"),
            entry("glGetInteger", "(I)I"),
            entry("glGetFloat", "(I)F"),
            entry("glGetFloat", "(ILjava/nio/FloatBuffer;)V"),
            // A light parameter set from a shader item's render callback, which the lighting model already carries.
            entry("glLight", "(IILjava/nio/FloatBuffer;)V"),
            // Point size and the stencil family: reached by IE's fractal particles, its blueprint lines and the
            // bottling machine's fluid clipping. Neither has a pipeline equivalent yet, so both are reported once
            // and ignored - the alternative was a crash on first use.
            entry("glPointSize", "(F)V"),
            entry("glStencilFunc", "(III)V"),
            entry("glStencilMask", "(I)V"),
            entry("glStencilOp", "(III)V"),
            // Only ever called on IC2C's error path, before the process exits.
            entry("glFinish", "()V"),

            // State setters the pack calls directly, measured across all 231 jars in the instance: 156 raw-GL
            // state-setting call sites, of which these are the ones the frame state already implements. The names
            // are LWJGL 2's, because the shim pass runs before the table that renames them - a call site is brought
            // to its final shape only after the retarget has had its chance (see CompatRewriter).
            entry("glBlendFunc", "(II)V"),          // 31 sites, the largest group: HeatAndClimate, RebornCore
            entry("glScissor", "(IIII)V"),          // 18 sites: Immersive Intelligence's GUI clipping, JourneyMap
            entry("glDepthMask", "(Z)V"),           // 6 sites
            entry("glShadeModel", "(I)V"),          // 6 sites
            entry("glClearColor", "(FFFF)V"),       // 5 sites
            entry("glColorMask", "(ZZZZ)V"),        // 1 site, Actinium
            entry("glPolygonMode", "(II)V"),        // 1 site, Actinium's wireframe pass
            entry("glClearDepth", "(D)V"),          // 1 site, Actinium
            entry("glLogicOp", "(I)V"),             // 1 site, subpocket
            entry("glTexEnvi", "(III)V"),           // 2 sites
            entry("glTexEnvf", "(IIF)V"),           // 1 site, mcjtylib
            entry("glTexEnv", "(IILjava/nio/FloatBuffer;)V"),   // 1 site, subpocket
            // Matrices loaded rather than composed: modularui and Immersive Intelligence's mine renderers.
            entry("glLoadMatrix", "(Ljava/nio/FloatBuffer;)V"),
            entry("glLoadMatrixf", "(Ljava/nio/FloatBuffer;)V"),
            entry("glMultMatrix", "(Ljava/nio/FloatBuffer;)V"),
            entry("glMultMatrixf", "(Ljava/nio/FloatBuffer;)V"),
            entry("glMultMatrix", "(Ljava/nio/DoubleBuffer;)V"),
            // Fixed-function client arrays. Immersive Intelligence's TmtTessellator points four arrays at one
            // 40-byte-stride buffer, enables them, draws and disables them again - per chunk of 64k vertices, every
            // frame. The descriptors are LWJGL 2's, which is why the two-int overloads appear next to the explicit
            // ones: glVertexPointer(3, 40, floatBuffer) leaves the type to the buffer, and the facade infers it.
            entry("glEnableClientState", "(I)V"),
            entry("glDisableClientState", "(I)V"),
            entry("glVertexPointer", "(IIIJ)V"),
            entry("glVertexPointer", "(IILjava/nio/FloatBuffer;)V"),
            entry("glColorPointer", "(IIIJ)V"),
            entry("glColorPointer", "(IZILjava/nio/ByteBuffer;)V"),
            entry("glTexCoordPointer", "(IIIJ)V"),
            entry("glTexCoordPointer", "(IILjava/nio/FloatBuffer;)V"),
            entry("glTexCoordPointer", "(IILjava/nio/ShortBuffer;)V"),
            entry("glNormalPointer", "(IIJ)V"),
            entry("glNormalPointer", "(ILjava/nio/ByteBuffer;)V"),
            entry("glDrawArrays", "(III)V"),
            // The buffer objects those offsets point into. In LWJGL 3 the ARB class is gone and these are GL15, but
            // the mods' call sites still name ARB, so the retarget has to name it too.
            arbBuffer("glBindBufferARB", "(II)V"),
            arbBuffer("glBufferDataARB", "(ILjava/nio/ByteBuffer;I)V"),
            // The blend colour, which the GL_CONSTANT_* factors read. GL14's class, because that is where the
            // call sites name it - including vanilla's own GlStateManager.blendColor.
            gl14("glBlendColor", "(FFFF)V"),
            // The attribute stack. Astral Sorcery calls this 66 times and TC6 33+33; in this pack it is 11+11
            // (uniquecrops, base, Immersive Intelligence). Until now the backend only counted the depth, so a mod
            // that wrapped a block in push/pop got no restore at all and its state leaked into the next draw.
            entry("glPushAttrib", "(I)V"),
            entry("glPopAttrib", "()V"),
            // The stencil clear value. Three mods set it, and the draw stream used to hard-code zero for it.
            entry("glClearStencil", "(I)V"),
            // Reading pixels back. Retargeted so the calls stop reaching a GL context that does not exist, and
            // reported once: a faithful implementation needs a staged copy out of the image plus a fence, which is a
            // feature rather than a leftover. Fifteen call sites in the pack, all of them mod-side exports.
            entry("glReadPixels", "(IIIIIILjava/nio/ByteBuffer;)V"),
            entry("glReadPixels", "(IIIIIILjava/nio/FloatBuffer;)V"),
            entry("glReadPixels", "(IIIIIILjava/nio/IntBuffer;)V"),
            entry("glGetTexImage", "(IIIILjava/nio/ByteBuffer;)V"),
            entry("glGetTexImage", "(IIIILjava/nio/IntBuffer;)V"),
            entry("glCopyTexImage2D", "(IIIIIIII)V"),
            // The read-back family's neighbours, which were reaching real GL - a segfault in a Vulkan window rather
            // than a reported gap. Measured users: journeymap blits, FTBLib and IvToolkit select a read buffer, and
            // four mods ask a texture for its size.
            entry("glReadBuffer", "(I)V"),
            entry("glGetTexLevelParameteri", "(III)I"),
            new RetargetEntry(GL30, "glBlitFramebuffer", "(IIIIIIIIII)V", FACADE),
            // Display lists. Immersive Intelligence's AMT model renderers compile their quads once and replay them
            // per frame; the names come from GLAllocation, which the patch already routes, so only the three calls
            // the mod makes itself need entries. glGenLists/glDeleteLists are deliberately absent: no mod in the
            // pack calls them directly, and the ones that allocate a list go through GLAllocation.
            entry("glNewList", "(II)V"),
            entry("glEndList", "()V"),
            entry("glCallList", "(I)V"),
            // Fixed-function dash patterns. No pipeline equivalent, but reaching real GL from a window that has no
            // GL context is worse than reporting once, so the entry exists to catch the call.
            entry("glLineStipple", "(IS)V"),
            // Immersive Intelligence asks whether culling is on before it changes the mode. The single-value form
            // exists in LWJGL 3 as well, so this is a retarget and not a table entry.
            entry("glGetBoolean", "(I)Z"),

            // Texture objects. The whole family was missing, and a mod calling any of it reached real GL: TurretMod's
            // turret camera allocates its own texture this way and the process died inside GL11C.nglGenTextures.
            // Measured across the pack: glGenTextures 3, glDeleteTextures 3, glBindTexture 9, glTexParameteri 11,
            // glTexParameterf 2, glTexImage2D 6, glTexSubImage2D 2, glPixelStorei 4, glGenerateMipmap 2.
            entry("glGenTextures", "()I"),
            entry("glDeleteTextures", "(I)V"),
            entry("glBindTexture", "(II)V"),
            entry("glTexParameteri", "(III)V"),
            entry("glTexParameterf", "(IIF)V"),
            entry("glTexImage2D", "(IIIIIIIILjava/nio/ByteBuffer;)V"),
            entry("glTexSubImage2D", "(IIIIIIIILjava/nio/ByteBuffer;)V"),
            entry("glPixelStorei", "(II)V"),
            gl30("glGenerateMipmap", "(I)V"),

            // Core GL 2.0 shader objects, the same object model as the ARB block under the names a mod written against
            // GL 2.0 core uses. Measured: SmoothFont's font shader (about forty sites) and CodeChickenLib's
            // ShaderProgram. Their absence was a segfault in liblwjgl_opengl, not a reported gap.
            gl20("glCreateProgram", "()I"),
            gl20("glCreateShader", "(I)I"),
            gl20("glShaderSource", "(ILjava/lang/CharSequence;)V"),
            gl20("glCompileShader", "(I)V"),
            gl20("glAttachShader", "(II)V"),
            gl20("glLinkProgram", "(I)V"),
            gl20("glValidateProgram", "(I)V"),
            gl20("glUseProgram", "(I)V"),
            gl20("glDeleteProgram", "(I)V"),
            gl20("glDeleteShader", "(I)V"),
            gl20("glGetProgrami", "(II)I"),
            gl20("glGetShaderi", "(II)I"),
            gl20("glGetProgramInfoLog", "(II)Ljava/lang/String;"),
            gl20("glGetShaderInfoLog", "(II)Ljava/lang/String;"),
            gl20("glGetUniformLocation", "(ILjava/lang/CharSequence;)I"),
            gl20("glUniform1i", "(II)V"),
            gl20("glUniform1f", "(IF)V"),
            gl20("glUniform2f", "(IFF)V"),
            gl20("glUniform3f", "(IFFF)V"),
            gl20("glUniform4f", "(IFFFF)V"),
            gl20("glUniform2i", "(III)V"),
            gl20("glUniform3i", "(IIII)V"),
            gl20("glUniform4i", "(IIIII)V"),
            gl20("glUniformMatrix4", "(IZLjava/nio/FloatBuffer;)V"),
            // The three integer uniform forms the ARB block was missing. LibrarianLib's boolean-vector uniforms are
            // the only users, and they reached real GL.
            arb("glUniform2iARB", "(III)V"),
            arb("glUniform3iARB", "(IIII)V"),
            arb("glUniform4iARB", "(IIIII)V"),
            // Buffer objects by their GL 1.5 names: IvToolkit's model loader uploads geometry this way and then points
            // client arrays at offsets inside it, which the client-array path already understands.
            gl15("glGenBuffers", "()I"),
            gl15("glBindBuffer", "(II)V"),
            gl15("glBufferData", "(ILjava/nio/ByteBuffer;I)V"),
            gl15("glDeleteBuffers", "(I)V"),
            // The remaining state queries. modularui's projection reads four values at once, SmoothFont asks whether
            // its shader compiled, and Cleanroom's own hook helper prints the GL strings.
            entry("glGetInteger", "(ILjava/nio/IntBuffer;)V"),
            entry("glGetError", "()I"),
            entry("glGetString", "(I)Ljava/lang/String;"),
            // IvToolkit's depth buffer selects a draw buffer before rendering into its own target.
            entry("glDrawBuffer", "(I)V"),

            // Thaumcraft 6's fog, and the double-precision vertex forms its bundled glelwjgl tessellator uses. The fog
            // setters exist on FrameState already (EntityRenderer drives them through GlStateManager's named setters,
            // and IE's "flashed" effect through the NV distance mode); what was missing was the raw entry point.
            // Measured: glFogi/glFogf in TC6's render event handler, glColor3d/glNormal3d/glTexCoord2d in
            // com.sasmaster.glelwjgl.
            entry("glFogi", "(II)V"),
            entry("glFogf", "(IF)V"),
            entry("glFog", "(ILjava/nio/FloatBuffer;)V"),
            entry("glColor3d", "(DDD)V"),
            entry("glNormal3d", "(DDD)V"),
            entry("glTexCoord2d", "(DD)V"),
            // glDepthRange (Thaumic Attempts' GUI) and glTexGen (TC6's mirror block) are recorded as reported gaps:
            // Vulkan's clip depth always maps to [0, 1], and no stage here generates texture coordinates.
            entry("glDepthRange", "(DD)V"),
            entry("glTexGeni", "(III)V"),
            entry("glTexGen", "(IILjava/nio/FloatBuffer;)V"),

            // The long tail the pack-wide scan turned up: one or two call sites each, all of them real GL calls that
            // would have reached liblwjgl_opengl. Measured owners and users:
            //   glVertex2d - jeresources; glFrustum - modularui's schema renderer; glClipPlane - Immersive
            //   Intelligence's AMT item; glFlush - modernsplash; glGetTexEnvi/f - SmoothFont; glGetTexParameteri -
            //   journeymap; the IntBuffer texture forms - modernsplash and mcjty's TrueTypeFont; glTexImage1D -
            //   LibrarianLib's blur; glActiveTexture - SmoothFont; glBlendFuncSeparate - LibrarianLib's particles;
            //   glBlendEquationSeparate, glDetachShader and the active-uniform queries - LibrarianLib and SmoothFont.
            entry("glVertex2d", "(DD)V"),
            entry("glFrustum", "(DDDDDD)V"),
            entry("glClipPlane", "(ILjava/nio/DoubleBuffer;)V"),
            entry("glFlush", "()V"),
            entry("glGetTexEnvi", "(II)I"),
            entry("glGetTexEnvf", "(II)F"),
            entry("glGetTexParameteri", "(II)I"),
            entry("glGenTextures", "(Ljava/nio/IntBuffer;)V"),
            entry("glDeleteTextures", "(Ljava/nio/IntBuffer;)V"),
            entry("glTexImage2D", "(IIIIIIIILjava/nio/IntBuffer;)V"),
            entry("glTexSubImage2D", "(IIIIIIIILjava/nio/IntBuffer;)V"),
            entry("glTexImage1D", "(IIIIIIILjava/nio/FloatBuffer;)V"),
            gl13("glActiveTexture", "(I)V"),
            gl14("glBlendFuncSeparate", "(IIII)V"),
            gl20("glBlendEquationSeparate", "(II)V"),
            gl20("glDetachShader", "(II)V"),
            gl20("glGetActiveUniform", "(III)Ljava/lang/String;"),
            gl20("glGetActiveUniformType", "(II)I"),
            gl20("glGetActiveUniformSize", "(II)I"),
            gl20("glUniformMatrix2", "(IZLjava/nio/FloatBuffer;)V"),
            arb("glUniform2fARB", "(IFF)V"),
            arb("glUniform4fARB", "(IFFFF)V"),
            // GL 3.0's framebuffer objects by their core names: pregenerator's preview texture creates one.
            gl30("glGenFramebuffers", "()I"),
            gl30("glBindFramebuffer", "(II)V"),
            gl30("glFramebufferTexture2D", "(IIIII)V"),
            gl30("glDeleteFramebuffers", "(I)V"),

            // ARB_shader_objects: the whole shader surface of the three mods that use shaders at all. The
            // sequence is the same in each of them - create, source, compile, attach, link, ask the status, look up
            // a uniform, write it, use the program - so one set of entries covers all three. The two extra
            // uniform forms and the validate call are Immersive Intelligence's.
            arb("glCreateProgramObjectARB", "()I"),
            arb("glCreateShaderObjectARB", "(I)I"),
            arb("glShaderSourceARB", "(ILjava/lang/CharSequence;)V"),
            arb("glCompileShaderARB", "(I)V"),
            arb("glAttachObjectARB", "(II)V"),
            arb("glLinkProgramARB", "(I)V"),
            arb("glValidateProgramARB", "(I)V"),
            arb("glUseProgramObjectARB", "(I)V"),
            arb("glDeleteObjectARB", "(I)V"),
            arb("glGetObjectParameteriARB", "(II)I"),
            arb("glGetInfoLogARB", "(II)Ljava/lang/String;"),
            arb("glGetUniformLocationARB", "(ILjava/lang/CharSequence;)I"),
            arb("glUniform1iARB", "(II)V"),
            arb("glUniform1fARB", "(IF)V"),
            arb("glUniform3fARB", "(IFFF)V"));

    /**
     * Packages whose classes keep real GL.
     *
     * <p><b>The first entry is mandatory, not defensive.</b> The facade's own pass-through implementation calls
     * {@code GL11}, and it lives in this package: without the exemption the rewrite would send those calls back
     * into the facade, which is unbounded recursion ending in a stack overflow at the first GL call. The other
     * three are the places under the renderer package that legitimately issue real GL; measured by grepping
     * {@code net/minecraftforge/client/render/} for direct GL calls, which finds exactly these.</p>
     *
     * <p>Vanilla ({@code net/minecraft/**}) is deliberately <b>not</b> exempt. Its GL calls are already guarded
     * at source, so a retarget there is dead code — but it is dead code that would start working if a later patch
     * forgets a guard, which is worth more than the saved bytes.</p>
     */
    private static final Set<String> EXEMPT = Set.of(
            "net/minecraftforge/client/render/shim/",
            "net/minecraftforge/client/render/shader/",
            "net/minecraftforge/client/render/GlAnswers",
            "net/minecraftforge/client/render/state/backend/");

    private FrameShimRules() {}

    /**
     * Registers the built-in ruleset, tolerating a previous registration of the same instance.
     *
     * <p>Called from {@code Lwjgl2CompatTransformer}'s static initialiser: that class is constructed during the
     * coremod stage, which is the last moment registration can still affect the rewrite.</p>
     */
    public static void registerBuiltin() {
        ShimRegistry.register(INSTANCE);
    }

    private static RetargetEntry entry(String name, String desc) {
        return new RetargetEntry(GL11, name, desc, FACADE);
    }

    private static RetargetEntry arb(String name, String desc) {
        return new RetargetEntry(ARB_SHADER_OBJECTS, name, desc, FACADE);
    }

    private static RetargetEntry arbBuffer(String name, String desc) {
        return new RetargetEntry(ARB_BUFFER_OBJECT, name, desc, FACADE);
    }

    private static RetargetEntry gl14(String name, String desc) {
        return new RetargetEntry(GL14, name, desc, FACADE);
    }

    private static RetargetEntry gl30(String name, String desc) {
        return new RetargetEntry(GL30, name, desc, FACADE);
    }

    private static RetargetEntry gl20(String name, String desc) {
        return new RetargetEntry(GL20, name, desc, FACADE);
    }

    private static RetargetEntry gl15(String name, String desc) {
        return new RetargetEntry(GL15, name, desc, FACADE);
    }

    private static RetargetEntry gl13(String name, String desc) {
        return new RetargetEntry(GL13, name, desc, FACADE);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean appliesTo(ShimEnvironment env) {
        return VULKAN_ID.equals(env.backendId());
    }

    @Override
    public List<RetargetEntry> entries() {
        return ENTRIES;
    }

    @Override
    public Set<String> exemptPrefixes() {
        return EXEMPT;
    }
}
