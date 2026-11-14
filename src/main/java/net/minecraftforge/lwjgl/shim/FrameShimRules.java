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
            // Fixed-function dash patterns. No pipeline equivalent, but reaching real GL from a window that has no
            // GL context is worse than reporting once, so the entry exists to catch the call.
            entry("glLineStipple", "(IS)V"),
            // Immersive Intelligence asks whether culling is on before it changes the mode. The single-value form
            // exists in LWJGL 3 as well, so this is a retarget and not a table entry.
            entry("glGetBoolean", "(I)Z"),

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
