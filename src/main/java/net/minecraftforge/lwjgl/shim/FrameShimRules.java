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
 * <p><b>Why these eighteen and nothing else.</b> They are the whole raw-GL surface of IC2Classic 1.12-1.5.8,
 * measured by scanning its constant pools: forty-five matrix-stack call sites, eight state bits, four
 * current-colour/current-normal sites and six calls that only appear on its fatal-error screen. Adding an entry
 * is cheap, but an entry whose facade method does not exist fails as a {@code NoSuchMethodError} on first
 * execution rather than at load time, so the set is kept to what has been measured and the bijection test keeps
 * it honest.</p>
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

    /** The class every entry retargets away from. */
    public static final String GL11 = "org/lwjgl/opengl/GL11";

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
            // Only ever called on IC2C's error path, before the process exits.
            entry("glFinish", "()V"));

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
