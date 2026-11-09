package net.minecraftforge.client.render.state.backend;

import net.minecraftforge.client.render.RenderBackendSelection;

import org.jspecify.annotations.Nullable;

/** Supplies the frame-state backend for a renderer that is not OpenGL. */
public final class FrameStates {

    private static final FrameStateVk VK = new FrameStateVk();
    private static final ImmediateMode IMMEDIATE = new ImmediateMode();

    private FrameStates() {}

    /**
     * The backend for the active renderer, or {@code null} when the vanilla GL code path applies.
     */
    public static @Nullable FrameState forNonGl() {
        return RenderBackendSelection.glPath() ? null : VK;
    }

    /**
     * Answers a {@code glGetFloatv} on the active backend, or returns {@code false} when the vanilla GL path applies.
     *
     * <p>Kept here rather than inlined into {@code GlStateManager} because the answer depends on which value was
     * asked for: the seam in the patched vanilla file stays one line, and the knowledge of what this backend can
     * report stays with the backend.</p>
     */
    public static boolean getFloat(int glPname, java.nio.FloatBuffer out) {
        FrameState state = forNonGl();
        if (state == null) {
            return false;
        }
        state.readMatrix(glPname, out);
        return true;
    }

    /**
     * Answers {@code glFogi} on the active backend, or returns {@code false} when the vanilla GL path applies.
     *
     * <p>The pname dispatch lives here rather than in the patched vanilla file: {@code glFogi} is one entry point
     * that sets several different values, and which one it sets is exactly the kind of question this class exists to
     * answer for the non-GL path.</p>
     */
    public static boolean fog(int glPname, int param) {
        FrameState state = forNonGl();
        if (state == null) {
            return false;
        }
        if (glPname == 2917) {   // GL_FOG_MODE
            state.fogMode(param);
            return true;
        }
        throw unimplemented("fog (glFogi " + glPname + ")",
                "GL_FOG_MODE, the only integer fog value vanilla sets");
    }

    /**
     * Answers {@code glFog} on the active backend, or returns {@code false} when the vanilla GL path applies.
     */
    public static boolean fog(int glPname, java.nio.FloatBuffer param) {
        FrameState state = forNonGl();
        if (state == null) {
            return false;
        }
        state.fog(glPname, param);
        return true;
    }

    /**
     * Answers {@code OpenGlHelper.glBlendFunc} on the active backend, or returns {@code false} when the vanilla GL path
     * applies.
     *
     * <p>The same state {@code GlStateManager.tryBlendFuncSeparate} sets, reached through a second entry point because
     * Forge's own HUD code calls {@code OpenGlHelper} directly. Two entry points for one piece of state is worth
     * keeping, since the backend records blend factors per draw and does not care which name the game used.</p>
     */
    public static boolean blendFuncSeparate(int sourceRgb, int destinationRgb, int sourceAlpha, int destinationAlpha) {
        FrameState state = forNonGl();
        if (state == null) {
            return false;
        }
        state.blendFuncSeparate(sourceRgb, destinationRgb, sourceAlpha, destinationAlpha);
        return true;
    }

    /**
     * Records one render layer of the world's terrain, at the point in the frame the game asked for it.
     *
     * <p>Called from the patched {@code RenderGlobal.renderBlockLayer}, which is the one place that has the layer, the
     * camera matrix and the order vanilla draws them in at once. On the GL path it returns {@code false} and the
     * caller runs vanilla's own draw.</p>
     *
     * <p>The matrix is composed here rather than taken from a draw because nothing was drawn: on this backend the chunk
     * meshes were captured when they finished building (they never reached a GL buffer), so the layer's draw has to be
     * issued from the frame's sequence instead of from a recorded draw call.</p>
     *
     * @return whether the layer was recorded, i.e. whether the backend is drawing terrain
     */
    public static boolean recordTerrainLayer(int layer) {
        return recordTerrainLayer(layer, 0.0, 0.0, 0.0);
    }

    /**
     * Records one terrain render layer with the camera viewpoint offset in world space.
     *
     * <p>Vanilla's modelview matrix during chunk rendering only carries camera rotation and eye height; the chunk
     * origins are translated relative to the viewpoint per-chunk ({@code blockpos - viewEntity}). Translating the
     * camera matrix by {@code (-viewX, -viewY, -viewZ)} puts the camera in world space so that chunk origins in
     * world coordinates align with the camera and view frustum.</p>
     */
    public static boolean recordTerrainLayer(int layer, double viewX, double viewY, double viewZ) {
        FrameState state = forNonGl();
        if (state == null) {
            return false;
        }
        org.joml.Matrix4f view = new org.joml.Matrix4f(state.modelView())
                .translate((float) -viewX, (float) -viewY, (float) -viewZ);
        org.joml.Matrix4f mvp = new org.joml.Matrix4f(state.projection()).mul(view);
        float[] values = new float[16];
        mvp.get(values);
        return net.minecraftforge.client.render.vertex.FrameDraws.recordTerrainLayer(layer, values);
    }

    /**
     * Whether a GL call describing how vertex data is fed is this backend's to ignore.
     *
     * <p>Three groups sit under this: the client-memory attribute pointers ({@code glVertexPointer},
     * {@code glColorPointer}, {@code glTexCoordPointer}, {@code glNormalPointer}), the
     * {@code glEnableClientState}/{@code glDisableClientState} pair and {@code glClientActiveTexture} that choose
     * which arrays and which unit they apply to, and {@code glBindBuffer}'s global binding. All of them describe the
     * layout of whatever buffer is bound, one attribute at a time, and the draw then reads that description.</p>
     *
     * <p>On this backend none of that exists as state: vanilla's own {@code VertexBuffer} already carries its
     * {@code VertexFormat}, the vertex buffer backend builds the pipeline's input from it, and every draw names the
     * buffer it reads. So these calls have nothing left to say here.</p>
     *
     * <p>They are answered rather than left to throw because vanilla calls them around every buffer it draws - the
     * chunk VBOs in {@code VboRenderList}, the sky and star VBOs in {@code RenderGlobal}, and the per-element setup
     * {@code Tessellator.draw} runs in {@code ForgeHooksClient.preDraw}. A reader who finds them missing will assume
     * they were forgotten, which is why this method exists and says so. Leaving them unguarded is not an option: raw
     * LWJGL with no GL context does not throw, it kills the process - which is how this was found, in
     * {@code glClientActiveTexture} on the first frame that drew clouds.</p>
     */
    public static boolean ignoresVertexInputState() {
        return forNonGl() != null;
    }

    /**
     * Answers {@code OpenGlHelper.setLightmapTextureCoords}: the constant light a draw without a lightmap attribute
     * gets.
     *
     * <p>Vanilla sets it per entity and per item, so it is state that travel with the draw rather than a per-frame
     * value - which is why it is recorded into {@link FrameState} and read when the draw is captured, exactly as the
     * matrix is.</p>
     *
     * @return whether the backend took it, i.e. whether the vanilla GL path should be skipped
     */
    public static boolean lightmapTexture(int texture) {
        FrameState state = forNonGl();
        if (state == null) {
            return false;
        }
        state.lightmapTexture(texture);
        return true;
    }

    public static boolean lightmap(float u, float v) {
        FrameState state = forNonGl();
        if (state == null) {
            return false;
        }
        state.lightmap(u, v);
        return true;
    }

    /**
     * Answers {@code glLight}, {@code glLightModel} and {@code glColorMaterial}: recorded as a skipped effect.
     *
     * <p>This is the fixed-function lighting model - two directional lights and a light model the game configures
     * through {@code RenderHelper.enableStandardItemLighting}, plus per-frame light setup for entities, clouds and
     * items. On this backend nothing consumes it: the shader takes the vertex colour as final, which is correct for
     * terrain (vanilla bakes directional shade and ambient occlusion into the vertex before it is written) and flat
     * for entity models, which expect the light model to shade them.</p>
     *
     * <p>Not a throw, because it does not stop a frame from rendering: it makes lit geometry flat. Reported once per
     * entry point so the log says which of them are still doing nothing.</p>
     */
    public static boolean skipLighting(String operation) {
        if (forNonGl() == null) {
            return false;
        }
        reportSkippedOnce("fixed-function lighting (" + operation + ")",
                "geometry whose vertices carry no shade is drawn flat rather than lit");
        return true;
    }

    /**
     * Answers {@code glTexEnv}: recorded as a skipped effect.
     *
     * <p>The texture environment is GL's per-unit combine stage. Vanilla uses it twice: to build entity brightness out
     * of the lightmap unit's constant colour and the previous unit ({@code RenderLivingBase}), and to switch a unit to
     * {@code REPLACE} or {@code COMBINE} for overlays. This backend samples one texture and multiplies it by the
     * vertex colour, so a draw that asked for a different combine mode is drawn with the modulate it always does.</p>
     */
    public static boolean skipTextureEnv(String operation) {
        if (forNonGl() == null) {
            return false;
        }
        reportSkippedOnce("the texture environment (" + operation + ")",
                "a draw asking for a combine or replace mode is sampled as texture times colour");
        return true;
    }

    /**
     * Reports a fixed-function or immediate-mode operation this backend does not implement yet.
     *
     * <p>Always throws. It exists so the guards read as "this is a known gap with a name" rather than as a silent
     * no-op, and so the message says what would be needed. Calling it is a programming error in the guard, not
     * something a caller can catch and continue from.</p>
     */
    public static UnsupportedOperationException unimplemented(String operation, String needs) {
        return new UnsupportedOperationException("the Vulkan backend does not implement " + operation + " yet. "
                + "Needed: " + needs + ". Vanilla's own use of it is small and enumerated in"
                + " docs/vulkan-backend-plan.md 8.4.1, but the fixed-function and immediate-mode groups are a"
                + " shader-variant and vertex-accumulation problem, not a translation.");
    }

    private static final java.util.Set<String> REPORTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Records, once per operation, that an operation was skipped.
     *
     * <p>For operations whose absence changes how the game looks but not whether it runs. Reported once because a
     * per-frame line would be unreadable, and recorded because an omitted effect with no trace is indistinguishable
     * from a bug. Everything that changes whether the game runs correctly throws instead - see
     * {@link #unimplemented}.</p>
     *
     * <p><b>Once per operation, not once per process.</b> A single latch used to guard all of them, which made the
     * first skip in a session the only one ever logged and hid every later one - including the ones a reader would
     * most want, since the first is usually a detail and the last is usually the large missing effect. There are only
     * a handful of these operations, so the set stays small.</p>
     */
    public static void reportSkippedOnce(String operation, String consequence) {
        if (REPORTED.add(operation)) {
            org.apache.logging.log4j.LogManager.getLogger("Outlands|render").warn(
                    "Skipping {}: the Vulkan backend does not implement it yet, so {}. This is reported once.",
                    operation, consequence);
        }
    }

    /**
     * The immediate-mode accumulator.
     *
     * <p>Always available; callers are the {@code glBegin}/{@code glEnd} guards, which only reach it when the GL path
     * does not apply.</p>
     */
    public static ImmediateMode immediate() {
        return IMMEDIATE;
    }

    /** The Vulkan instance, for diagnostics and tests. {@code null} when the GL path is active. */
    public static @Nullable FrameState instance() {
        return forNonGl();
    }
}
