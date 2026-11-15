package net.minecraftforge.client.render.vertex;

/**
 * The fixed-function lighting model as it stood when a draw was issued.
 *
 * <p><b>What was wrong before this existed.</b> The converter shaded lit vertices with the numbers vanilla's
 * {@code RenderHelper.enableStandardItemLighting} happens to set - ambient 0.4, diffuse 0.6 on both lights - hard
 * coded. Vanilla's own geometry therefore looked right, and anything that changed the model did not: a mod that
 * tints a light, drops one of the two, or turns colour material off got vanilla's lighting anyway. Those are exactly
 * the calls {@code glLightModel} and {@code glColorMaterial} make, and both were being dropped with a warning.</p>
 *
 * <p><b>The model, as GL defines it.</b> Per vertex, with {@code a} ambient, {@code d} diffuse, {@code L} the
 * eye-space direction to the light and {@code N} the eye-space normal:</p>
 *
 * <pre>
 * colour = materialAmbient  * (modelAmbient + sum over enabled lights of lightAmbient)
 *        + materialDiffuse  * sum over enabled lights of lightDiffuse * max(0, N . L)
 * </pre>
 *
 * <p>Emission and specular are not in that formula because nothing sets them: GL's default emission is black and its
 * default material specular is black, so the terms are zero for every draw the game makes. {@code glMaterial} - the
 * only call that could change them - is not made by vanilla, and a mod that makes it still gets this model rather
 * than a wrong one, because a zero term is omitted rather than approximated.</p>
 *
 * <p><b>Colour material.</b> {@code GL_COLOR_MATERIAL} makes the current vertex colour stand in for the material
 * components named by {@code glColorMaterial}'s mode, which is what vanilla turns on for items and entities. With it
 * off, GL's default material applies: ambient 0.2, diffuse 0.8. Both cases are reproduced, because the difference is
 * visible - an unlit-looking model versus a flat one.</p>
 *
 * <p><b>Immutable, and shared between draws.</b> A change replaces the instance rather than mutating it, so a
 * recorded draw can hold a reference to the model it was issued under without copying 100 floats, which is what the
 * per-draw snapshot had to do for the two light directions alone.</p>
 */
public final class LightingModel {

    /** The fixed-function lights GL guarantees: {@code GL_LIGHT0} through {@code GL_LIGHT7}. */
    public static final int LIGHTS = 8;

    /** {@code GL_AMBIENT}: the mode {@code EntityRenderer.setupFog} sets for the world pass. */
    public static final int AMBIENT = 4608;
    /** {@code GL_DIFFUSE}. */
    public static final int DIFFUSE = 4609;
    /** {@code GL_SPECULAR}: no term to drive, since the model has no specular. */
    public static final int SPECULAR = 4610;
    /** {@code GL_EMISSION}. */
    public static final int EMISSION = 5632;
    /** {@code GL_AMBIENT_AND_DIFFUSE}: the mode vanilla sets for items and entities. */
    public static final int AMBIENT_AND_DIFFUSE = 5634;

    /** GL's default material ambient, used when colour material is off. */
    private static final float DEFAULT_MATERIAL_AMBIENT = 0.2F;
    /** GL's default material diffuse, used when colour material is off. */
    private static final float DEFAULT_MATERIAL_DIFFUSE = 0.8F;

    /** The state {@code GlStateManager.Profile.DEFAULT} installs, i.e. what a fresh context starts with. */
    public static final LightingModel GL_DEFAULT = new LightingModel(
            new float[] {0.2F, 0.2F, 0.2F},
            ambientOfGlDefaults(), diffuseOfGlDefaults(), positionOfGlDefaults(), enabledOfGlDefaults(),
            false, AMBIENT_AND_DIFFUSE);

    private final float[] modelAmbient;
    /** {@code LIGHTS * 3}: ambient colour of each light, in 0..1. */
    private final float[] lightAmbient;
    /** {@code LIGHTS * 3}: diffuse colour of each light, in 0..1. */
    private final float[] lightDiffuse;
    /** {@code LIGHTS * 3}: unit direction towards each light, in eye space. */
    private final float[] lightDirection;
    private final boolean[] lightEnabled;
    private final boolean colorMaterial;
    private final int colorMaterialMode;

    /**
     * {@code modelAmbient + sum of the enabled lights' ambient}: what the material ambient multiplies.
     *
     * <p>Summed here rather than per vertex because it is constant for the whole draw - the ambient term of GL's
     * lighting equation does not depend on the normal.</p>
     */
    private final float[] ambientSum;
    /** Indices of the lights whose diffuse term can contribute, and how many of them there are. */
    private final int[] diffuseLights;
    private final int diffuseLightCount;

    /**
     * @param modelAmbient      three components of {@code GL_LIGHT_MODEL_AMBIENT}
     * @param lightAmbient      {@code LIGHTS * 3} components, per light
     * @param lightDiffuse      {@code LIGHTS * 3} components, per light
     * @param lightDirection    {@code LIGHTS * 3} unit vectors towards the lights, in eye space
     * @param lightEnabled      which lights are on
     * @param colorMaterial     whether {@code GL_COLOR_MATERIAL} is enabled
     * @param colorMaterialMode the {@code glColorMaterial} mode; every one GL defines is acted on, and
     *                          {@link #SPECULAR} is the one that changes nothing because the model has no specular
     */
    public LightingModel(float[] modelAmbient, float[] lightAmbient, float[] lightDiffuse, float[] lightDirection,
                         boolean[] lightEnabled, boolean colorMaterial, int colorMaterialMode) {
        if (modelAmbient.length < 3 || lightAmbient.length < LIGHTS * 3 || lightDiffuse.length < LIGHTS * 3
                || lightDirection.length < LIGHTS * 3 || lightEnabled.length < LIGHTS) {
            throw new IllegalArgumentException("a lighting model needs 3 model-ambient components, "
                    + LIGHTS + " lights with 3 components each and " + LIGHTS + " enable bits");
        }
        // Copied, not referenced: the frame state keeps mutating its own arrays as the game sets state, and a
        // recorded draw has to keep the model it was issued under.
        this.modelAmbient = java.util.Arrays.copyOf(modelAmbient, 3);
        this.lightAmbient = java.util.Arrays.copyOf(lightAmbient, LIGHTS * 3);
        this.lightDiffuse = java.util.Arrays.copyOf(lightDiffuse, LIGHTS * 3);
        this.lightDirection = java.util.Arrays.copyOf(lightDirection, LIGHTS * 3);
        this.lightEnabled = java.util.Arrays.copyOf(lightEnabled, LIGHTS);
        this.colorMaterial = colorMaterial;
        this.colorMaterialMode = colorMaterialMode;

        this.ambientSum = new float[] {
                this.modelAmbient[0], this.modelAmbient[1], this.modelAmbient[2]};
        int[] contributing = new int[LIGHTS];
        int count = 0;
        for (int light = 0; light < LIGHTS; light++) {
            if (!this.lightEnabled[light]) {
                continue;
            }
            int at = light * 3;
            for (int c = 0; c < 3; c++) {
                this.ambientSum[c] += this.lightAmbient[at + c];
            }
            // A light with no diffuse colour, or no direction, has no diffuse term. Skipping it here keeps the
            // per-vertex loop at the number of lights that actually contribute.
            if (this.lightDiffuse[at] > 0.0F || this.lightDiffuse[at + 1] > 0.0F || this.lightDiffuse[at + 2] > 0.0F) {
                contributing[count++] = light;
            }
        }
        this.diffuseLights = java.util.Arrays.copyOf(contributing, count);
        this.diffuseLightCount = count;
    }

    /**
     * The eye-space unit direction towards one light.
     *
     * <p>Exposed for tests and for anything that has to explain a shaded pixel: the model is otherwise a black box
     * whose only operation is {@link #shadeFactor}.</p>
     */
    public float[] lightDirection(int light) {
        if (light < 0 || light >= LIGHTS) {
            throw new IllegalArgumentException("light index out of range: " + light);
        }
        return new float[] {lightDirection[light * 3], lightDirection[light * 3 + 1], lightDirection[light * 3 + 2]};
    }

    /** Whether the vertex colour stands in for the material, i.e. {@code GL_COLOR_MATERIAL} is on. */
    public boolean usesColorMaterial() {
        return colorMaterial;
    }

    /**
     * The colour-material mode, or {@link #AMBIENT_AND_DIFFUSE} when colour material is off.
     *
     * <p>Only {@code AMBIENT_AND_DIFFUSE} is acted on: the other modes make the vertex colour drive a single
     * component, and nothing in the game sets them. A mode that is not acted on is reported by the caller rather than
     * silently treated as this one.</p>
     */
    public int colorMaterialMode() {
        return colorMaterialMode;
    }

    /** Whether any enabled light has a diffuse colour, i.e. whether the normal can change the result. */
    public boolean hasDiffuseLight() {
        return diffuseLightCount > 0;
    }

    /**
     * The lighting factor for one eye-space normal: what the vertex colour is multiplied by.
     *
     * <p><b>Why a factor and not the final colour.</b> With colour material on, the vertex colour <i>is</i> the
     * material, so it factors straight out of GL's equation and the rest is a function of the normal alone. That
     * keeps the per-vertex work to three multiplies and lets the converter cache the factor for a run of identical
     * normals, which is what a cube face is. With colour material off the material is the GL default and the vertex
     * colour takes no part; the caller then multiplies this factor by white, and gets the material's lit colour.</p>
     *
     * @param nx  eye-space normal x
     * @param ny  eye-space normal y
     * @param nz  eye-space normal z
     * @param out three components, in 0..1
     */
    public void shadeFactor(float nx, float ny, float nz, float[] outVertex, float[] outConstant) {
        // GL's equation, rearranged as  colour = vertex * outVertex + outConstant  so that the vertex colour stays a
        // per-vertex multiply while everything that depends only on the normal is cached for a run of identical ones.
        // Which material components the vertex colour drives is exactly what glColorMaterial's mode selects:
        //
        //   AMBIENT_AND_DIFFUSE  ambient and diffuse both follow it        -> vertex * (ambientSum + diffuse)
        //   AMBIENT              only ambient follows it                   -> vertex * ambientSum + 0.8 * diffuse
        //   DIFFUSE              only diffuse follows it                   -> vertex * diffuse   + 0.2 * ambientSum
        //   EMISSION             it is added, not substituted              -> vertex * 1 + 0.2 * ambient + 0.8 * diffuse
        //   SPECULAR             nothing follows it (no specular term)     -> 0.2 * ambient + 0.8 * diffuse
        //   colour material off  nothing follows it                        -> the same as SPECULAR
        // Start from "the colour drives nothing", which is colour material off: GL's default material.
        float vertexAmbient = 0.0F;
        float vertexDiffuse = 0.0F;
        float constantAmbient = DEFAULT_MATERIAL_AMBIENT;
        float constantDiffuse = DEFAULT_MATERIAL_DIFFUSE;
        float emission = 0.0F;
        if (colorMaterial) {
            // Each mode moves one component from the constant side to the vertex side - not both, which would count
            // it twice and made a diffuse-only model come out at 0.8 + 0.8.
            switch (colorMaterialMode) {
                case AMBIENT_AND_DIFFUSE -> {
                    vertexAmbient = 1.0F;
                    constantAmbient = 0.0F;
                    vertexDiffuse = 1.0F;
                    constantDiffuse = 0.0F;
                }
                case AMBIENT -> {
                    vertexAmbient = 1.0F;
                    constantAmbient = 0.0F;
                }
                case DIFFUSE -> {
                    vertexDiffuse = 1.0F;
                    constantDiffuse = 0.0F;
                }
                case EMISSION -> emission = 1.0F;
                default -> {
                    // SPECULAR and anything else: the vertex colour drives no material component, which is the same
                    // picture as colour material being off.
                }
            }
        }

        float vr = vertexAmbient * ambientSum[0] + emission;
        float vg = vertexAmbient * ambientSum[1] + emission;
        float vb = vertexAmbient * ambientSum[2] + emission;
        float cr = constantAmbient * ambientSum[0];
        float cg = constantAmbient * ambientSum[1];
        float cb = constantAmbient * ambientSum[2];

        for (int i = 0; i < diffuseLightCount; i++) {
            int at = diffuseLights[i] * 3;
            float dot = nx * lightDirection[at] + ny * lightDirection[at + 1] + nz * lightDirection[at + 2];
            if (dot <= 0.0F) {
                continue;
            }
            vr += dot * vertexDiffuse * lightDiffuse[at];
            vg += dot * vertexDiffuse * lightDiffuse[at + 1];
            vb += dot * vertexDiffuse * lightDiffuse[at + 2];
            float k = dot * constantDiffuse;
            cr += k * lightDiffuse[at];
            cg += k * lightDiffuse[at + 1];
            cb += k * lightDiffuse[at + 2];
        }

        // Clamped per term, not per product: an over-lit vertex keeps its texture colour instead of saturating to
        // white. GL clamps the finished colour, so the two differ only where a model is lit past 1 - and this is the
        // behaviour the renderer has always produced, pinned by VertexConverterTest, so changing it here would alter
        // the picture for every tinted model.
        outVertex[0] = Math.min(1.0F, vr);
        outVertex[1] = Math.min(1.0F, vg);
        outVertex[2] = Math.min(1.0F, vb);
        outConstant[0] = Math.min(1.0F, cr);
        outConstant[1] = Math.min(1.0F, cg);
        outConstant[2] = Math.min(1.0F, cb);
    }

    /**
     * The two eye-space directions {@code RenderHelper.enableStandardItemLighting} installs, as GL defaults.
     *
     * <p>Only used to seed {@link #GL_DEFAULT}: {@code Profile.DEFAULT} sets every light to
     * {@code (0, 0, 1, 0)} and disables them, so these are the directions the game overwrites before anything is
     * drawn. Keeping them here means a draw that happens before the game sets a light still has a defined model.</p>
     */
    private static float[] positionOfGlDefaults() {
        float[] positions = new float[LIGHTS * 3];
        for (int light = 0; light < LIGHTS; light++) {
            positions[light * 3 + 2] = 1.0F;
        }
        return positions;
    }

    private static float[] ambientOfGlDefaults() {
        return new float[LIGHTS * 3];
    }

    private static float[] diffuseOfGlDefaults() {
        // Profile.DEFAULT gives light 0 a white diffuse and every other light black, matching GL.
        float[] diffuse = new float[LIGHTS * 3];
        diffuse[0] = 1.0F;
        diffuse[1] = 1.0F;
        diffuse[2] = 1.0F;
        return diffuse;
    }

    private static boolean[] enabledOfGlDefaults() {
        return new boolean[LIGHTS];
    }
}
