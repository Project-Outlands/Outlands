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

    /** One visible-chunk list per render layer, indexed by the layer's ordinal. */
    private static final it.unimi.dsi.fastutil.longs.LongArrayList[] LAYER_CHUNK_LISTS =
            new it.unimi.dsi.fastutil.longs.LongArrayList[net.minecraft.util.BlockRenderLayer.values().length];
    private static int nextLayerListIdx = 0;

    static {
        for (int i = 0; i < LAYER_CHUNK_LISTS.length; i++) {
            LAYER_CHUNK_LISTS[i] = new it.unimi.dsi.fastutil.longs.LongArrayList(4096);
        }
    }

    /**
     * The visible-chunk list belonging to a render layer.
     *
     * <p>Keyed by the layer's ordinal, which is what the terrain layer and the chunk classification both use, so the
     * three cannot disagree about how many layers there are.</p>
     */
    public static it.unimi.dsi.fastutil.longs.LongArrayList beginLayerChunkList(int layer) {
        it.unimi.dsi.fastutil.longs.LongArrayList list = layerList(layer);
        if (list == null) {
            return beginLayerChunkList();
        }
        list.clear();
        return list;
    }

    /**
     * A scratch list for a caller that does not name a layer.
     *
     * <p>Rotates through the per-layer lists because a single shared one would be cleared by the next call while the
     * previous caller still held it. Callers that know their layer should use {@link #beginLayerChunkList(int)}.</p>
     */
    public static it.unimi.dsi.fastutil.longs.LongArrayList beginLayerChunkList() {
        it.unimi.dsi.fastutil.longs.LongArrayList list = LAYER_CHUNK_LISTS[nextLayerListIdx];
        nextLayerListIdx = (nextLayerListIdx + 1) % LAYER_CHUNK_LISTS.length;
        list.clear();
        return list;
    }

    public static it.unimi.dsi.fastutil.longs.LongArrayList[] getLayerChunkLists() {
        return LAYER_CHUNK_LISTS;
    }

    public static it.unimi.dsi.fastutil.longs.LongArrayList getLayerChunkList(int layer) {
        return layerList(layer);
    }

    private static it.unimi.dsi.fastutil.longs.LongArrayList layerList(int layer) {
        return layer >= 0 && layer < LAYER_CHUNK_LISTS.length ? LAYER_CHUNK_LISTS[layer] : null;
    }

    public static void clearLayerChunkLists() {
        for (int l = 0; l < LAYER_CHUNK_LISTS.length; l++) {
            LAYER_CHUNK_LISTS[l].clear();
        }
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
        return recordTerrainLayer(layer, viewX, viewY, viewZ, null);
    }

    private static final org.joml.Matrix4f SCRATCH_VIEW = new org.joml.Matrix4f();
    private static final org.joml.Matrix4f SCRATCH_MVP = new org.joml.Matrix4f();

    public static boolean recordTerrainLayer(int layer, double viewX, double viewY, double viewZ,
                                             it.unimi.dsi.fastutil.longs.LongArrayList visibleChunks) {
        FrameState state = forNonGl();
        if (state == null) {
            return false;
        }
        SCRATCH_VIEW.set(state.modelView()).translate((float) -viewX, (float) -viewY, (float) -viewZ);
        SCRATCH_MVP.set(state.projection()).mul(SCRATCH_VIEW);
        float[] values = new float[16];
        SCRATCH_MVP.get(values);
        boolean fogEnabled = state.isEnabled(Capability.FOG);
        int fogMode = fogEnabled ? state.fogMode() : 0;
        float[] fogColor = state.fogColor();
        return net.minecraftforge.client.render.vertex.FrameDraws.recordTerrainLayer(
                layer, values, viewX, viewY, viewZ,
                fogMode, state.fogStart(), state.fogEnd(), state.fogDensity(), fogColor, visibleChunks);
    }

    public static void texEnv(int target, int parameterName, java.nio.FloatBuffer parameters) {
        FrameState state = forNonGl();
        if (state != null) {
            state.texEnv(target, parameterName, parameters);
        }
    }

    public static void texEnvi(int target, int parameterName, int parameter) {
        FrameState state = forNonGl();
        if (state != null) {
            state.texEnvi(target, parameterName, parameter);
        }
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
     * Answers {@code glLight}: a light's ambient colour, diffuse colour and position reach the lighting model.
     *
     * <p>Everything else on a light is reported once and left alone - specular needs a material specular the model
     * does not carry (GL's default is black, so the term is zero for every draw the game makes), and spot/attenuation
     * parameters would need terms the shader does not have.</p>
     */
    public static boolean glLight(int light, int pname, java.nio.FloatBuffer params) {
        FrameState state = forNonGl();
        if (state == null) {
            return false;
        }
        switch (pname) {
            case 4608, 4609, 4611 -> state.glLight(light, pname, params);   // GL_AMBIENT, GL_DIFFUSE, GL_POSITION
            default -> reportSkippedOnce("fixed-function lighting (glLight " + pname + ")",
                    "only a light's ambient colour, diffuse colour and position are modelled; a spot light or"
                            + " attenuation would need terms the shader does not have");
        }
        return true;
    }

    /**
     * Answers {@code glLightModel}: the model ambient is modelled, everything else is reported once.
     *
     * <p>{@code GL_LIGHT_MODEL_TWO_SIDE} is the one that would otherwise be silently wrong: it lights back faces with
     * the reverse normal, which needs a second normal per vertex. Vanilla never sets it.</p>
     */
    public static boolean glLightModel(int pname, java.nio.FloatBuffer params) {
        FrameState state = forNonGl();
        if (state == null) {
            return false;
        }
        if (pname == 2899) {    // GL_LIGHT_MODEL_AMBIENT
            state.lightModelAmbient(params);
        } else {
            reportSkippedOnce("fixed-function lighting (glLightModel " + pname + ")",
                    "only the model ambient is modelled; two-sided lighting would need a second normal per vertex");
        }
        return true;
    }

    /**
     * Answers {@code glColorMaterial}: the mode decides which material components the vertex colour stands in for.
     *
     * <p>Only {@code GL_AMBIENT_AND_DIFFUSE} is acted on, which is the mode vanilla sets and the only one it uses. The
     * other modes make the colour drive one component at a time, which would need the per-component material the
     * model does not carry; reported once rather than quietly treated as the mode that is implemented.</p>
     */
    public static boolean colorMaterial(int face, int mode) {
        FrameState state = forNonGl();
        if (state == null) {
            return false;
        }
        if (mode != 5634) {     // GL_AMBIENT_AND_DIFFUSE
            reportSkippedOnce("fixed-function lighting (glColorMaterial mode " + mode + ")",
                    "only GL_AMBIENT_AND_DIFFUSE is modelled, which is the mode the game uses");
            return true;
        }
        state.colorMaterial(face, mode);
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
