package net.minecraftforge.client.render.state.backend;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.nio.FloatBuffer;
import java.util.EnumSet;
import java.util.Set;

/**
 * Frame state on the Vulkan backend: the pipeline key, recorded rather than applied.
 *
 * <p><b>Why recording is the whole job.</b> In GL these calls change driver state that the next draw picks up
 * implicitly. In Vulkan almost all of it is baked into the pipeline object, so the useful form is "what is the
 * state now", read when a draw needs a pipeline. That is what this class keeps. Nothing here touches the device,
 * which is why it works during startup when no frame is being recorded.</p>
 *
 * <p><b>Unknown values are rejected, not stored.</b> A blend factor this class does not recognise would otherwise
 * become a pipeline nobody builds, and the failure would appear as a wrong picture rather than as an error. The
 * message names the value so the next run reports exactly what is missing.</p>
 *
 * <p><b>Matrices.</b> GL has one stack per matrix mode; this keeps the same shape because vanilla pushes and pops
 * with respect to the mode it selected earlier. Values are column-major 4x4, the layout both GL and a push constant
 * use.</p>
 */
final class FrameStateVk implements FrameState {

    static final class MatrixStack {
        private final Matrix4f[] stack;
        private int top;

        MatrixStack(int depth) {
            this.stack = new Matrix4f[depth];
            for (int i = 0; i < depth; i++) {
                this.stack[i] = new Matrix4f();
            }
            this.top = 0;
        }

        Matrix4f current() {
            return stack[top];
        }

        int depth() {
            return top + 1;
        }

        void push() {
            if (top >= stack.length - 1) {
                throw new StackOverflowError("Matrix stack overflow (depth limit " + stack.length + ")");
            }
            stack[top + 1].set(stack[top]);
            top++;
        }

        void pop() {
            if (top <= 0) {
                throw new IllegalStateException("popMatrix without a matching pushMatrix");
            }
            top--;
        }

        void loadIdentity() {
            stack[top].identity();
        }

        int top() {
            return top;
        }

        /** Puts the stack back to a saved index, discarding anything pushed above it. */
        void restoreTop(int index) {
            top = index;
        }

        /** Back to one identity matrix, as a fresh context would be. */
        void reset() {
            top = 0;
            for (Matrix4f matrix : stack) {
                matrix.identity();
            }
        }
    }

    private final Set<Capability> enabled = EnumSet.noneOf(Capability.class);

    private int depthComparison = 513;      // GL_LESS, the GL default
    private int alphaComparison = 519;      // GL_ALWAYS, the GL default
    private float alphaReference = 0.0F;
    private int shadeModel = 7425;          // GL_SMOOTH
    private int cullFace = 1029;            // GL_BACK
    private final boolean[] colorMask = {true, true, true, true};
    private boolean depthMask = true;
    private final float[] vertexColor = {1.0F, 1.0F, 1.0F, 1.0F};
    private int blendSource = 1;            // GL_ONE
    private int blendDestination = 0;       // GL_ZERO
    private final int[] blendSeparate = new int[4];
    private boolean hasBlendSeparate;
    private int blendEquation = 32774;      // GL_FUNC_ADD
    private int logicOp = 5379;             // GL_COPY
    private float lineWidth = 1.0F;
    private int polygonModeFace = 1032;     // GL_FRONT_AND_BACK
    private int polygonModeMode = 6914;     // GL_FILL
    private float polygonOffsetFactor;
    private float polygonOffsetUnits;
    private final int[] viewport = {0, 0, 0, 0};
    private final int[] scissor = {0, 0, 0, 0};
    private final float[] clearColor = {0.0F, 0.0F, 0.0F, 0.0F};
    private double clearDepth = 1.0;
    /** {@code glClearStencil}: the value the next stencil clear writes, 0 at GL's default. */
    private int clearStencil;
    private int clearMask;

    private int matrixMode = 5888;          // GL_MODELVIEW
    private static final int MODELVIEW_MATRIX = 2982;      // GL_MODELVIEW_MATRIX, a readback target
    private static final int PROJECTION_MATRIX = 2983;     // GL_PROJECTION_MATRIX, a readback target
    private static final int TEXTURE_MATRIX = 2984;        // GL_TEXTURE_MATRIX, a readback target
    private static final int MODELVIEW = 5888;             // the matrix mode each target reads
    private static final int PROJECTION = 5889;
    private static final int TEXTURE = 5890;

    private final MatrixStack modelViewStack = new MatrixStack(64);
    private final MatrixStack projectionStack = new MatrixStack(16);
    private final MatrixStack[] textureStacks = new MatrixStack[8];

    private final float[] multiplyScratchValues = new float[16];
    private final Matrix4f multiplyScratchMatrix = new Matrix4f();
    private final float[] readMatrixScratch = new float[16];
    private final Matrix4f mvpScratch = new Matrix4f();
    private final Matrix3f normalScratch = new Matrix3f();
    private final float[] normalMatrixValues = new float[9];
    private boolean normalMatrixDirty = true;
    private final float[] textureMatrixScratch = new float[16];
    /** Saved attribute sets, innermost first. */
    private final java.util.ArrayDeque<SavedState> savedAttributes = new java.util.ArrayDeque<>();

    /**
     * One saved GL attribute set.
     *
     * <p><b>Why a field-by-field copy and not a mask walk.</b> {@code glPushAttrib} takes a mask of state groups, and
     * the mods that use it - Astral Sorcery 66 times, Immersive Intelligence twice, TC6 twice - pass
     * {@code GL_ALL_ATTRIB_BITS} every time. So the whole set is saved and the whole set restored, and a narrower
     * mask is reported once instead of being silently widened. Walking GL's groups would be a table of forty
     * fields-to-bits that nothing in the pack exercises, and a wrong bit in it would restore the wrong half.</p>
     *
     * <p><b>What has to be here.</b> Everything mutable in this class that a draw reads. Leaving one out does not
     * fail loudly: the mod's state leaks past its {@code glPopAttrib} and the *next* thing drawn is wrong, which
     * looks like a bug in whatever drew next. {@code AttributeStackTest} mutates every group and checks each one
     * comes back.</p>
     */
    private static final class SavedState {
        private int modelViewTop;
        private int projectionTop;
        private final int[] textureTops = new int[8];
        private final Set<Capability> enabled = EnumSet.noneOf(Capability.class);
        private int depthComparison;
        private int alphaComparison;
        private float alphaReference;
        private int shadeModel;
        private int cullFace;
        private final boolean[] colorMask = new boolean[4];
        private boolean depthMask;
        private final float[] vertexColor = new float[4];
        private int blendSource;
        private int blendDestination;
        private final int[] blendSeparate = new int[4];
        private boolean hasBlendSeparate;
        private int blendEquation;
        private int logicOp;
        private float lineWidth;
        private int polygonModeFace;
        private int polygonModeMode;
        private float polygonOffsetFactor;
        private float polygonOffsetUnits;
        private final int[] viewport = new int[4];
        private final int[] scissor = new int[4];
        private final float[] clearColor = new float[4];
        private double clearDepth;
        private int clearStencil;
        private int clearMask;
        private int matrixMode;
        private int activeTextureUnit;
        private int fogMode;
        private boolean radialFog;
        private float pointSize;
        private int stencilComparison;
        private int stencilReference;
        private int stencilCompareMask;
        private int stencilWriteMask;
        private int stencilFail;
        private int stencilDepthFail;
        private int stencilPass;
        private final float[] fogColor = new float[4];
        private float fogDensity;
        private float fogStart;
        private float fogEnd;
        private final float[] lightmap = new float[2];
        private boolean hasLightmap;
        private int lightmapTexture;
        private final float[] lightModelAmbient = new float[3];
        private final float[] lightAmbient;
        private final float[] lightDiffuse;
        private final float[] lightDirection;
        private int colorMaterialFace;
        private int colorMaterialMode;
        private final float[] overlayColor = new float[4];
        private final float[][] texEnvColor;
        private final int[] texEnvMode;
        private final int[] texCombineRgb;
        private final int[] texSource0Rgb;
        private final int[] texSource1Rgb;
        private final int[] texSource2Rgb;
        private final int[] texOperand2Rgb;

        SavedState() {
            lightAmbient = new float[net.minecraftforge.client.render.vertex.LightingModel.LIGHTS * 3];
            lightDiffuse = new float[net.minecraftforge.client.render.vertex.LightingModel.LIGHTS * 3];
            lightDirection = new float[net.minecraftforge.client.render.vertex.LightingModel.LIGHTS * 3];
            texEnvColor = new float[FrameStateVk.TEXTURE_UNITS][4];
            texEnvMode = new int[FrameStateVk.TEXTURE_UNITS];
            texCombineRgb = new int[FrameStateVk.TEXTURE_UNITS];
            texSource0Rgb = new int[FrameStateVk.TEXTURE_UNITS];
            texSource1Rgb = new int[FrameStateVk.TEXTURE_UNITS];
            texSource2Rgb = new int[FrameStateVk.TEXTURE_UNITS];
            texOperand2Rgb = new int[FrameStateVk.TEXTURE_UNITS];
        }

        void save(FrameStateVk state) {
            modelViewTop = state.modelViewStack.top();
            projectionTop = state.projectionStack.top();
            for (int unit = 0; unit < textureTops.length; unit++) {
                textureTops[unit] = state.textureStacks[unit].top();
            }
            enabled.clear();
            enabled.addAll(state.enabled);
            depthComparison = state.depthComparison;
            alphaComparison = state.alphaComparison;
            alphaReference = state.alphaReference;
            shadeModel = state.shadeModel;
            cullFace = state.cullFace;
            System.arraycopy(state.colorMask, 0, colorMask, 0, 4);
            depthMask = state.depthMask;
            System.arraycopy(state.vertexColor, 0, vertexColor, 0, 4);
            blendSource = state.blendSource;
            blendDestination = state.blendDestination;
            System.arraycopy(state.blendSeparate, 0, blendSeparate, 0, 4);
            hasBlendSeparate = state.hasBlendSeparate;
            blendEquation = state.blendEquation;
            logicOp = state.logicOp;
            lineWidth = state.lineWidth;
            polygonModeFace = state.polygonModeFace;
            polygonModeMode = state.polygonModeMode;
            polygonOffsetFactor = state.polygonOffsetFactor;
            polygonOffsetUnits = state.polygonOffsetUnits;
            System.arraycopy(state.viewport, 0, viewport, 0, 4);
            System.arraycopy(state.scissor, 0, scissor, 0, 4);
            System.arraycopy(state.clearColor, 0, clearColor, 0, 4);
            clearDepth = state.clearDepth;
            clearStencil = state.clearStencil;
            clearMask = state.clearMask;
            matrixMode = state.matrixMode;
            activeTextureUnit = state.activeTextureUnit;
            fogMode = state.fogMode;
            radialFog = state.radialFog;
            pointSize = state.pointSize;
            stencilComparison = state.stencilComparison;
            stencilReference = state.stencilReference;
            stencilCompareMask = state.stencilCompareMask;
            stencilWriteMask = state.stencilWriteMask;
            stencilFail = state.stencilFail;
            stencilDepthFail = state.stencilDepthFail;
            stencilPass = state.stencilPass;
            System.arraycopy(state.fogColor, 0, fogColor, 0, 4);
            fogDensity = state.fogDensity;
            fogStart = state.fogStart;
            fogEnd = state.fogEnd;
            System.arraycopy(state.lightmap, 0, lightmap, 0, 2);
            hasLightmap = state.hasLightmap;
            lightmapTexture = state.lightmapTexture;
            System.arraycopy(state.lightModelAmbient, 0, lightModelAmbient, 0, 3);
            System.arraycopy(state.lightAmbient, 0, lightAmbient, 0, lightAmbient.length);
            System.arraycopy(state.lightDiffuse, 0, lightDiffuse, 0, lightDiffuse.length);
            System.arraycopy(state.lightDirection, 0, lightDirection, 0, lightDirection.length);
            colorMaterialFace = state.colorMaterialFace;
            colorMaterialMode = state.colorMaterialMode;
            System.arraycopy(state.overlayColor, 0, overlayColor, 0, 4);
            for (int unit = 0; unit < FrameStateVk.TEXTURE_UNITS; unit++) {
                System.arraycopy(state.texEnvColor[unit], 0, texEnvColor[unit], 0, 4);
            }
            System.arraycopy(state.texEnvMode, 0, texEnvMode, 0, texEnvMode.length);
            System.arraycopy(state.texCombineRgb, 0, texCombineRgb, 0, texCombineRgb.length);
            System.arraycopy(state.texSource0Rgb, 0, texSource0Rgb, 0, texSource0Rgb.length);
            System.arraycopy(state.texSource1Rgb, 0, texSource1Rgb, 0, texSource1Rgb.length);
            System.arraycopy(state.texSource2Rgb, 0, texSource2Rgb, 0, texSource2Rgb.length);
            System.arraycopy(state.texOperand2Rgb, 0, texOperand2Rgb, 0, texOperand2Rgb.length);
        }

        void restore(FrameStateVk state) {
            state.enabled.clear();
            state.enabled.addAll(enabled);
            state.depthComparison = depthComparison;
            state.alphaComparison = alphaComparison;
            state.alphaReference = alphaReference;
            state.shadeModel = shadeModel;
            state.cullFace = cullFace;
            System.arraycopy(colorMask, 0, state.colorMask, 0, 4);
            state.depthMask = depthMask;
            System.arraycopy(vertexColor, 0, state.vertexColor, 0, 4);
            state.blendSource = blendSource;
            state.blendDestination = blendDestination;
            System.arraycopy(blendSeparate, 0, state.blendSeparate, 0, 4);
            state.hasBlendSeparate = hasBlendSeparate;
            state.blendEquation = blendEquation;
            state.logicOp = logicOp;
            state.lineWidth = lineWidth;
            state.polygonModeFace = polygonModeFace;
            state.polygonModeMode = polygonModeMode;
            state.polygonOffsetFactor = polygonOffsetFactor;
            state.polygonOffsetUnits = polygonOffsetUnits;
            System.arraycopy(viewport, 0, state.viewport, 0, 4);
            System.arraycopy(scissor, 0, state.scissor, 0, 4);
            System.arraycopy(clearColor, 0, state.clearColor, 0, 4);
            state.clearDepth = clearDepth;
            state.clearStencil = clearStencil;
            state.clearMask = clearMask;
            state.matrixMode = matrixMode;
            state.activeTextureUnit = activeTextureUnit;
            state.fogMode = fogMode;
            state.radialFog = radialFog;
            state.pointSize = pointSize;
            state.stencilComparison = stencilComparison;
            state.stencilReference = stencilReference;
            state.stencilCompareMask = stencilCompareMask;
            state.stencilWriteMask = stencilWriteMask;
            state.stencilFail = stencilFail;
            state.stencilDepthFail = stencilDepthFail;
            state.stencilPass = stencilPass;
            System.arraycopy(fogColor, 0, state.fogColor, 0, 4);
            state.fogDensity = fogDensity;
            state.fogStart = fogStart;
            state.fogEnd = fogEnd;
            System.arraycopy(lightmap, 0, state.lightmap, 0, 2);
            state.hasLightmap = hasLightmap;
            state.lightmapTexture = lightmapTexture;
            System.arraycopy(lightModelAmbient, 0, state.lightModelAmbient, 0, 3);
            System.arraycopy(lightAmbient, 0, state.lightAmbient, 0, lightAmbient.length);
            System.arraycopy(lightDiffuse, 0, state.lightDiffuse, 0, lightDiffuse.length);
            System.arraycopy(lightDirection, 0, state.lightDirection, 0, lightDirection.length);
            state.colorMaterialFace = colorMaterialFace;
            state.colorMaterialMode = colorMaterialMode;
            System.arraycopy(overlayColor, 0, state.overlayColor, 0, 4);
            for (int unit = 0; unit < FrameStateVk.TEXTURE_UNITS; unit++) {
                System.arraycopy(texEnvColor[unit], 0, state.texEnvColor[unit], 0, 4);
            }
            System.arraycopy(texEnvMode, 0, state.texEnvMode, 0, texEnvMode.length);
            System.arraycopy(texCombineRgb, 0, state.texCombineRgb, 0, texCombineRgb.length);
            System.arraycopy(texSource0Rgb, 0, state.texSource0Rgb, 0, texSource0Rgb.length);
            System.arraycopy(texSource1Rgb, 0, state.texSource1Rgb, 0, texSource1Rgb.length);
            System.arraycopy(texSource2Rgb, 0, state.texSource2Rgb, 0, texSource2Rgb.length);
            System.arraycopy(texOperand2Rgb, 0, state.texOperand2Rgb, 0, texOperand2Rgb.length);
        }
    }

    /** Fog state, at the defaults GL documents: GL_EXP at density 1, no colour, the linear range 0..1. */
    private int fogMode = 2048;
    /** GL_FOG_DISTANCE_MODE_NV: false is GL's default planar distance, true is GL_EYE_RADIAL_NV. */
    private boolean radialFog;
    /** glPointSize: 1.0 until something asks for another size, which is GL's initial value. */
    private float pointSize = 1.0F;
    /** Stencil state, at GL's initial values: ALWAYS, reference 0, all bits, KEEP/KEEP/KEEP. */
    private int stencilComparison = 519;
    private int stencilReference;
    private int stencilCompareMask = 0xFFFFFFFF;
    private int stencilWriteMask = 0xFFFFFFFF;
    private int stencilFail = 7680;
    private int stencilDepthFail = 7680;
    private int stencilPass = 7680;
    private final float[] fogColor = {0.0F, 0.0F, 0.0F, 0.0F};
    private float fogDensity = 1.0F;
    private float fogStart = 0.0F;
    private float fogEnd = 1.0F;
    /** The lightmap coordinate a draw gets when its format carries none. Null until the game sets one. */
    private final float[] lightmap = new float[2];
    private boolean hasLightmap;
    /** The texture the game's lightmap lives in, or {@code 0}. */
    private int lightmapTexture;
    /**
     * The fixed-function lighting model, mutable while the game sets state.
     *
     * <p>Colours and directions of all eight lights, plus the model ambient - the pieces {@code glLight},
     * {@code glLightModel} and {@code glColorMaterial} write. Kept here rather than in the draw because it changes a
     * handful of times per frame while draws are issued by the thousand: {@link #lighting()} turns it into an
     * immutable snapshot, and a draw holds that instead of a copy.</p>
     */
    private final float[] lightModelAmbient = {0.2F, 0.2F, 0.2F};
    private final float[] lightAmbient = new float[net.minecraftforge.client.render.vertex.LightingModel.LIGHTS * 3];
    private final float[] lightDiffuse = new float[net.minecraftforge.client.render.vertex.LightingModel.LIGHTS * 3];
    private final float[] lightDirection = new float[net.minecraftforge.client.render.vertex.LightingModel.LIGHTS * 3];
    private int colorMaterialFace = 1032;    // GL_FRONT_AND_BACK, the default
    private int colorMaterialMode = net.minecraftforge.client.render.vertex.LightingModel.AMBIENT_AND_DIFFUSE;
    /** The snapshot a draw takes, rebuilt on the first {@link #lighting()} after a change. */
    private net.minecraftforge.client.render.vertex.LightingModel lightingSnapshot;
    private boolean lightingDirty = true;
    /** The light capabilities in {@code GL_LIGHT0..7} order, so the snapshot does not index the enum by ordinal. */
    private static final Capability[] LIGHT_CAPABILITIES = {
            Capability.LIGHT_0, Capability.LIGHT_1, Capability.LIGHT_2, Capability.LIGHT_3,
            Capability.LIGHT_4, Capability.LIGHT_5, Capability.LIGHT_6, Capability.LIGHT_7};
    private final float[] overlayColor = new float[4];
    /** GL_TEXTURE_ENV state, per texture unit: the combine stack is per unit, not global. */
    private static final int TEXTURE_UNITS = 4;
    private final float[][] texEnvColor = new float[TEXTURE_UNITS][4];
    private final int[] texEnvMode = new int[TEXTURE_UNITS];
    private final int[] texCombineRgb = new int[TEXTURE_UNITS];
    private final int[] texSource0Rgb = new int[TEXTURE_UNITS];
    private final int[] texSource1Rgb = new int[TEXTURE_UNITS];
    private final int[] texSource2Rgb = new int[TEXTURE_UNITS];
    private final int[] texOperand2Rgb = new int[TEXTURE_UNITS];

    // GL enumerants the combine stack is expressed in. Spelled out because the bug this replaced was a wrong
    // constant: 34164 is GL_CONSTANT, and GL_INTERPOLATE is 34165.
    private static final int GL_TEXTURE_ENV = 0x2300;
    private static final int GL_TEXTURE_ENV_MODE = 0x2200;
    private static final int GL_TEXTURE_ENV_COLOR = 0x2201;
    private static final int GL_COMBINE = 0x8570;
    private static final int GL_COMBINE_RGB = 0x8571;
    private static final int GL_INTERPOLATE = 0x8575;
    private static final int GL_CONSTANT = 0x8576;
    private static final int GL_PREVIOUS = 0x8578;
    private static final int GL_SOURCE0_RGB = 0x8580;
    private static final int GL_SOURCE1_RGB = 0x8581;
    private static final int GL_SOURCE2_RGB = 0x8582;
    private static final int GL_OPERAND2_RGB = 0x8592;
    private static final int GL_SRC_ALPHA = 0x0302;
    private static final int FOG_DENSITY = 2914;   // GL_FOG_DENSITY
    private static final int FOG_START = 2915;     // GL_FOG_START
    private static final int FOG_END = 2916;       // GL_FOG_END
    private static final int FOG_COLOR = 2918;     // GL_FOG_COLOR

    FrameStateVk() {
        for (int i = 0; i < textureStacks.length; i++) {
            textureStacks[i] = new MatrixStack(16);
        }
        // GL's defaults, which GlStateManager.Profile.DEFAULT also installs: light 0 is white and every light points
        // down the eye's -Z (the game overwrites both before it draws anything that is lit).
        lightDiffuse[0] = 1.0F;
        lightDiffuse[1] = 1.0F;
        lightDiffuse[2] = 1.0F;
        for (int light = 0; light < net.minecraftforge.client.render.vertex.LightingModel.LIGHTS; light++) {
            lightDirection[light * 3 + 2] = 1.0F;
        }
    }

    @Override
    public void capability(Capability capability, boolean enable) {
        if (capability == Capability.TEXTURE_2D) {
            net.minecraftforge.client.render.texture.TextureBackend tb =
                    net.minecraftforge.client.render.texture.TextureBackends.forNonGl();
            if (tb != null) {
                tb.setTexture2DEnabled(enable);
            }
        }
        if (enable) {
            enabled.add(capability);
        } else {
            enabled.remove(capability);
        }
        if (isLightingCapability(capability)) {
            lightingDirty = true;
        }
    }

    /**
     * Whether a capability is part of the lighting model.
     *
     * <p>The eight lights and colour material are: which lights are on decides which terms of GL's equation apply,
     * and {@code GL_COLOR_MATERIAL} decides whether the vertex colour is the material. {@code LIGHTING} itself is
     * not, because it is carried on the draw instead - a draw issued with lighting off never reads the model.</p>
     */
    private static boolean isLightingCapability(Capability capability) {
        return switch (capability) {
            case LIGHT_0, LIGHT_1, LIGHT_2, LIGHT_3, LIGHT_4, LIGHT_5, LIGHT_6, LIGHT_7, COLOR_MATERIAL -> true;
            default -> false;
        };
    }

    @Override
    public void depthFunc(int glComparison) {
        depthComparison = comparison(glComparison);
    }

    @Override
    public int depthFunc() {
        return depthComparison;
    }

    private static boolean isIdentity(Matrix4f m) {
        if ((m.properties() & org.joml.Matrix4fc.PROPERTY_IDENTITY) != 0) {
            return true;
        }
        return m.m00() == 1.0F && m.m01() == 0.0F && m.m02() == 0.0F && m.m03() == 0.0F
                && m.m10() == 0.0F && m.m11() == 1.0F && m.m12() == 0.0F && m.m13() == 0.0F
                && m.m20() == 0.0F && m.m21() == 0.0F && m.m22() == 1.0F && m.m23() == 0.0F
                && m.m30() == 0.0F && m.m31() == 0.0F && m.m32() == 0.0F && m.m33() == 1.0F;
    }

    @Override
    public float[] textureMatrix() {
        Matrix4f m = textureStacks[activeTextureUnit & 7].current();
        if (isIdentity(m)) {
            return null;
        }
        m.get(textureMatrixScratch);
        return textureMatrixScratch;
    }

    private int activeTextureUnit;

    @Override
    public void activeTextureUnit(int unit) {
        this.activeTextureUnit = unit;
    }

    @Override
    public int activeTextureUnit() {
        return activeTextureUnit;
    }

    @Override
    public float[] normalMatrix() {
        if (normalMatrixDirty) {
            modelViewStack.current().normal(normalScratch);
            normalScratch.get(normalMatrixValues);
            normalMatrixDirty = false;
        }
        return normalMatrixValues;
    }

    @Override
    public void computeMvp(float[] target) {
        mvpScratch.set(projectionStack.current()).mul(modelViewStack.current()).get(target);
    }

    @Override
    public void alphaFunc(int glComparison, float reference) {
        alphaComparison = comparison(glComparison);
        alphaReference = reference;
    }

    @Override
    public boolean alphaTestEnabled() {
        return enabled.contains(Capability.ALPHA_TEST);
    }

    @Override
    public float alphaReference() {
        return alphaReference;
    }

    @Override
    public int alphaComparison() {
        return alphaComparison;
    }

    @Override
    public void lightmap(float u, float v) {
        lightmap[0] = u;
        lightmap[1] = v;
        hasLightmap = true;
    }

    @Override
    public float[] lightmap() {
        return hasLightmap ? lightmap : null;
    }

    @Override
    public void lightmapTexture(int texture) {
        lightmapTexture = texture;
    }

    @Override
    public int lightmapTexture() {
        return lightmapTexture;
    }

    @Override
    public void glLight(int light, int pname, FloatBuffer params) {
        if (params == null) {
            return;
        }
        // GL_LIGHT0 is 0x4000, not 0: the index is what the arrays are laid out by.
        int index = light - 16384;
        if (index < 0 || index >= net.minecraftforge.client.render.vertex.LightingModel.LIGHTS) {
            // glLight on GL_LIGHT0..7 only. Anything else is a caller error GL would reject with GL_INVALID_ENUM;
            // ignoring it keeps a mod's mistake from taking the frame down.
            return;
        }
        int at = index * 3;
        int pos = params.position();
        int remaining = params.remaining();
        switch (pname) {
            case 4608 -> { // GL_AMBIENT
                if (remaining < 3) {
                    return;
                }
                lightAmbient[at] = params.get(pos);
                lightAmbient[at + 1] = params.get(pos + 1);
                lightAmbient[at + 2] = params.get(pos + 2);
                lightingDirty = true;
            }
            case 4609 -> { // GL_DIFFUSE
                if (remaining < 3) {
                    return;
                }
                lightDiffuse[at] = params.get(pos);
                lightDiffuse[at + 1] = params.get(pos + 1);
                lightDiffuse[at + 2] = params.get(pos + 2);
                lightingDirty = true;
            }
            case 4611 -> { // GL_POSITION
                if (remaining < 3) {
                    return;
                }
                float x = params.get(pos);
                float y = params.get(pos + 1);
                float z = params.get(pos + 2);
                // GL keeps the light's position in eye space: the modelview that is current when glLight is called
                // is applied once, here, and not again at draw time. The fourth component decides between a
                // positional and a directional light; both end up as a direction towards the light, which is what
                // the diffuse term needs.
                Matrix4fc mv = modelView();
                float ex = mv.m00() * x + mv.m10() * y + mv.m20() * z;
                float ey = mv.m01() * x + mv.m11() * y + mv.m21() * z;
                float ez = mv.m02() * x + mv.m12() * y + mv.m22() * z;
                float len = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
                if (len > 1e-4F) {
                    float inv = 1.0F / len;
                    ex *= inv;
                    ey *= inv;
                    ez *= inv;
                }
                lightDirection[at] = ex;
                lightDirection[at + 1] = ey;
                lightDirection[at + 2] = ez;
                lightingDirty = true;
            }
            default -> {
                // GL_SPECULAR and the spot/attenuation parameters: nothing sets them (GL's default material
                // specular is black, so the specular term is zero) and a spot light would need a cone test the
                // shader does not have. Recorded as "not acted on" by FrameStates, which is where the one-time
                // report lives.
            }
        }
    }

    @Override
    public void lightModelAmbient(FloatBuffer params) {
        if (params == null || params.remaining() < 3) {
            return;
        }
        int pos = params.position();
        lightModelAmbient[0] = params.get(pos);
        lightModelAmbient[1] = params.get(pos + 1);
        lightModelAmbient[2] = params.get(pos + 2);
        lightingDirty = true;
    }

    @Override
    public void colorMaterial(int face, int mode) {
        colorMaterialFace = face;
        colorMaterialMode = mode;
        lightingDirty = true;
    }

    @Override
    public net.minecraftforge.client.render.vertex.LightingModel lighting() {
        if (lightingSnapshot == null || lightingDirty) {
            boolean[] enabledLights = new boolean[net.minecraftforge.client.render.vertex.LightingModel.LIGHTS];
            for (int light = 0; light < enabledLights.length; light++) {
                enabledLights[light] = isEnabled(LIGHT_CAPABILITIES[light]);
            }
            // GL_COLOR_MATERIAL on its own does not make the front faces use the vertex colour: the face given to
            // glColorMaterial decides which side does. The shader has one material, so it is the front one that is
            // modelled - GL_BACK would leave front faces with the default material, and culling means the back
            // faces of a lit model are the ones nobody sees.
            boolean frontUsesColor = colorMaterialFace != 1030; // 1030 is GL_BACK
            lightingSnapshot = new net.minecraftforge.client.render.vertex.LightingModel(
                    lightModelAmbient, lightAmbient, lightDiffuse, lightDirection, enabledLights,
                    isEnabled(Capability.COLOR_MATERIAL) && frontUsesColor, colorMaterialMode);
            lightingDirty = false;
        }
        return lightingSnapshot;
    }

    @Override
    public void fogMode(int glMode) {
        fogMode = switch (glMode) {
            case 2048, 2049, 9729 -> glMode;   // GL_EXP, GL_EXP2, GL_LINEAR
            default -> throw unsupported("fog mode", glMode);
        };
    }

    @Override
    public void fogDistanceMode(int glMode) {
        // 34139 GL_EYE_RADIAL_NV, 34140 GL_EYE_PLANE_ABSOLUTE_NV, 9472 GL_EYE_PLANE.
        switch (glMode) {
            case 34139 -> radialFog = true;
            case 34140, 9472 -> radialFog = false;
            default -> throw FrameStates.unimplemented("fog (glFogi 34138 -> " + glMode + ")",
                    "GL_EYE_RADIAL_NV, GL_EYE_PLANE_ABSOLUTE_NV or GL_EYE_PLANE");
        }
    }

    @Override
    public boolean radialFog() {
        return radialFog;
    }

    @Override
    public void pointSize(float size) {
        pointSize = size;
    }

    @Override
    public float pointSize() {
        return pointSize;
    }

    @Override
    public void stencilFunc(int comparison, int reference, int mask) {
        // Recorded as the GL enums the caller passed; VulkanPipeline maps them and throws for one it does not know,
        // which is the right place - a mask that silently compares the wrong way is worse than a rejected pipeline.
        stencilComparison = comparison;
        stencilReference = reference;
        stencilCompareMask = mask;
    }

    @Override
    public void stencilMask(int mask) {
        stencilWriteMask = mask;
    }

    @Override
    public void stencilOp(int fail, int depthFail, int depthPass) {
        stencilFail = fail;
        stencilDepthFail = depthFail;
        stencilPass = depthPass;
    }

    @Override
    public int stencilComparison() {
        return stencilComparison;
    }

    @Override
    public int stencilReference() {
        return stencilReference;
    }

    @Override
    public int stencilCompareMask() {
        return stencilCompareMask;
    }

    @Override
    public int stencilWriteMask() {
        return stencilWriteMask;
    }

    @Override
    public int stencilFail() {
        return stencilFail;
    }

    @Override
    public int stencilDepthFail() {
        return stencilDepthFail;
    }

    @Override
    public int stencilPass() {
        return stencilPass;
    }

    @Override
    public void fog(int glPname, float value) {
        switch (glPname) {
            case FOG_DENSITY -> fogDensity = value;
            case FOG_START -> fogStart = value;
            case FOG_END -> fogEnd = value;
            default -> throw FrameStates.unimplemented("fog (glFogf " + glPname + ")",
                    "GL_FOG_DENSITY, GL_FOG_START or GL_FOG_END, the scalar fog values vanilla sets");
        }
    }

    @Override
    public void fog(int glPname, FloatBuffer param) {
        if (glPname != FOG_COLOR) {
            throw FrameStates.unimplemented("fog (glFog " + glPname + ")",
                    "GL_FOG_COLOR, the only buffer-valued fog setting vanilla uses");
        }
        FloatBuffer view = param.duplicate();
        view.rewind();
        if (view.remaining() < 4) {
            throw new IllegalArgumentException("a fog colour needs 4 floats, the buffer has " + view.remaining());
        }
        fogColor[0] = view.get();
        fogColor[1] = view.get();
        fogColor[2] = view.get();
        fogColor[3] = view.get();
    }

    @Override
    public int fogMode() {
        return fogMode;
    }

    @Override
    public float[] fogColor() {
        return fogColor;
    }

    @Override
    public float fogDensity() {
        return fogDensity;
    }

    @Override
    public float fogStart() {
        return fogStart;
    }

    @Override
    public float fogEnd() {
        return fogEnd;
    }

    @Override
    public void shadeModel(int glModel) {
        shadeModel = switch (glModel) {
            case 7424, 7425 -> glModel;   // GL_FLAT, GL_SMOOTH
            default -> throw unsupported("shade model", glModel);
        };
    }

    @Override
    public void cullFace(int glFace) {
        cullFace = switch (glFace) {
            case 1028, 1029, 1032 -> glFace;   // GL_FRONT, GL_BACK, GL_FRONT_AND_BACK
            default -> throw unsupported("cull face", glFace);
        };
    }

    @Override
    public int cullFace() {
        return cullFace;
    }

    @Override
    public void colorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        colorMask[0] = red;
        colorMask[1] = green;
        colorMask[2] = blue;
        colorMask[3] = alpha;
    }

    @Override
    public void texEnv(int target, int parameterName, java.nio.FloatBuffer parameters) {
        if (target != GL_TEXTURE_ENV || parameterName != GL_TEXTURE_ENV_COLOR || parameters.remaining() < 4) {
            return;
        }
        int unit = textureUnit();
        int pos = parameters.position();
        for (int i = 0; i < 4; i++) {
            texEnvColor[unit][i] = parameters.get(pos + i);
        }
        updateOverlayColor();
    }

    @Override
    public void texEnvi(int target, int parameterName, int parameter) {
        if (target != GL_TEXTURE_ENV) {
            return;
        }
        int unit = textureUnit();
        switch (parameterName) {
            case GL_TEXTURE_ENV_MODE -> texEnvMode[unit] = parameter;
            case GL_COMBINE_RGB -> texCombineRgb[unit] = parameter;
            case GL_SOURCE0_RGB -> texSource0Rgb[unit] = parameter;
            case GL_SOURCE1_RGB -> texSource1Rgb[unit] = parameter;
            case GL_SOURCE2_RGB -> texSource2Rgb[unit] = parameter;
            case GL_OPERAND2_RGB -> texOperand2Rgb[unit] = parameter;
            default -> { return; }
        }
        updateOverlayColor();
    }

    private int textureUnit() {
        int unit = activeTextureUnit();
        return unit >= 0 && unit < TEXTURE_UNITS ? unit : 0;
    }

    /**
     * Derives the light-colour overlay from the combine stack.
     *
     * <p>Vanilla does not tint a hurt mob's colour: it sets the <i>lightmap</i> unit to
     * {@code GL_INTERPOLATE(constant, previous, constant)} with a red constant at alpha 0.3, so GL computes
     * {@code mix(previousLight, red, 0.3)} and every fragment drawn afterwards has a red-shifted light. That is why
     * this is the overlay of the light term and not of the final colour - applying it to the colour looks like a flat
     * red wash instead of a mob lit red.</p>
     *
     * <p>Only the interpolate form is recognised. {@code GlStateManager.enableOutlineMode} configures the same unit
     * with {@code GL_REPLACE} and the primary colour, which is the team-colour outline drawn by a handful of
     * renderers; that is a different combine function and it is not implemented here, so it stays un-applied rather
     * than being approximated into something that looks wrong.</p>
     */
    /** The hurt-flash interpolate: the light is mixed towards the constant colour. */
    private static final int OVERLAY_LIGHT_MIXED = 0;
    /** Outline mode over a light unit: the light becomes the constant colour, the texture still multiplies. */
    private static final int OVERLAY_LIGHT_REPLACED = 1;
    /** Outline mode over unit 0: the texture is replaced by the constant colour, so the shape is one flat colour. */
    private static final int OVERLAY_TEXTURE_REPLACED = 2;

    /** One of the {@code OVERLAY_*} values: what the texture environment's combine stack is doing. */
    private int overlayMode;

    @Override
    public int overlayMode() {
        return overlayMode;
    }

    private void updateOverlayColor() {
        for (int unit = 0; unit < TEXTURE_UNITS; unit++) {
            // Outline mode: GlStateManager.enableOutlineMode sets the *active* unit to GL_REPLACE with the constant
            // colour as source 0. Which unit that is changes what the replacement replaces, and the two cases are not
            // the same picture:
            //
            // - unit 0 is the entity's own texture, so replacing it makes the silhouette one flat colour. That is the
            //   glowing-entity outline vanilla produces, and it is the case that actually happens: RenderHelper's
            //   enableStandardItemLighting ends by making unit 0 active, and enableOutlineMode is called right after.
            // - any other unit is a light, so replacing it tints the silhouette instead of flattening it.
            //
            // Measured, treating both as "the light was replaced": the outline appeared in the entity's texture colour
            // - a dark ring for a dark-textured entity - where the GL client shows a flat team-coloured one.
            if (texEnvMode[unit] == GL_COMBINE && texCombineRgb[unit] == 7681 /* GL_REPLACE */) {
                System.arraycopy(texEnvColor[unit], 0, overlayColor, 0, 4);
                overlayMode = unit == 0 ? OVERLAY_TEXTURE_REPLACED : OVERLAY_LIGHT_REPLACED;
                return;
            }
            if (texEnvMode[unit] == GL_COMBINE
                    && texCombineRgb[unit] == GL_INTERPOLATE
                    && texSource0Rgb[unit] == GL_CONSTANT
                    && texSource1Rgb[unit] == GL_PREVIOUS
                    && texSource2Rgb[unit] == GL_CONSTANT
                    && texOperand2Rgb[unit] == GL_SRC_ALPHA) {
                System.arraycopy(texEnvColor[unit], 0, overlayColor, 0, 4);
                overlayMode = OVERLAY_LIGHT_MIXED;
                return;
            }
        }
        overlayMode = OVERLAY_LIGHT_MIXED;
        overlayColor[0] = 0.0F;
        overlayColor[1] = 0.0F;
        overlayColor[2] = 0.0F;
        overlayColor[3] = 0.0F;
    }

    @Override
    public float[] overlayColor() {
        return overlayColor;
    }

    @Override
    public boolean[] colorMask() {
        return colorMask;
    }

    @Override
    public boolean isEnabled(Capability capability) {
        if (capability == Capability.TEXTURE_2D) {
            net.minecraftforge.client.render.texture.TextureBackend tb =
                    net.minecraftforge.client.render.texture.TextureBackends.forNonGl();
            if (tb != null) {
                return tb.isTexture2DEnabled();
            }
        }
        return enabled.contains(capability);
    }

    @Override
    public float[] currentColor() {
        return vertexColor;
    }

    @Override
    public int[] viewport() {
        return viewport;
    }

    @Override
    public boolean depthWriteEnabled() {
        return depthMask;
    }

    @Override
    public org.joml.Matrix4fc projection() {
        return matrix(5889);
    }

    @Override
    public org.joml.Matrix4fc modelView() {
        return matrix(5888);
    }

    @Override
    public void depthMask(boolean enabled) {
        depthMask = enabled;
    }

    @Override
    public void color(float red, float green, float blue, float alpha) {
        vertexColor[0] = red;
        vertexColor[1] = green;
        vertexColor[2] = blue;
        vertexColor[3] = alpha;
    }

    @Override
    public void blendFunc(int glSourceFactor, int glDestinationFactor) {
        blendSource = blendFactor(glSourceFactor);
        blendDestination = blendFactor(glDestinationFactor);
        hasBlendSeparate = false;
    }

    @Override
    public void blendFuncSeparate(int glSourceRgb, int glDestinationRgb, int glSourceAlpha, int glDestinationAlpha) {
        blendSeparate[0] = blendFactor(glSourceRgb);
        blendSeparate[1] = blendFactor(glDestinationRgb);
        blendSeparate[2] = blendFactor(glSourceAlpha);
        blendSeparate[3] = blendFactor(glDestinationAlpha);
        hasBlendSeparate = true;
    }

    @Override
    public int blendSource() {
        return blendSource;
    }

    @Override
    public int blendDestination() {
        return blendDestination;
    }

    @Override
    public int[] blendFactors() {
        return hasBlendSeparate ? blendSeparate : null;
    }

    @Override
    public void blendEquation(int glMode) {
        blendEquation = switch (glMode) {
            case 32774, 32775, 32776, 32777, 32778, 32779 -> glMode;   // ADD, MIN, MAX, SUBTRACT, REVERSE_SUBTRACT
            default -> throw unsupported("blend equation", glMode);
        };
    }

    @Override
    public void logicOp(int glOpcode) {
        logicOp = switch (glOpcode) {
            case 5376, 5377, 5378, 5379, 5380, 5381, 5382, 5383,
                 5384, 5385, 5386, 5387, 5388, 5389, 5390, 5391 -> glOpcode;
            default -> throw unsupported("logic op", glOpcode);
        };
    }

    @Override
    public void lineWidth(float width) {
        lineWidth = width;
    }

    @Override
    public float lineWidth() {
        return lineWidth;
    }

    /**
     * Puts the state back to GL's defaults, as a context loss would.
     *
     * <p>Test support: the instance is a singleton, so a test that changes the matrix or leaves an attribute block
     * open hands that state to the next test in the same JVM - and the failure appears there, not here.</p>
     */
    void resetForTests() {
        savedAttributes.clear();
        modelViewStack.reset();
        projectionStack.reset();
        for (MatrixStack stack : textureStacks) {
            stack.reset();
        }
        // The scalar state comes from a fresh instance rather than a hand-written list of defaults: a list would be
        // one more place to forget a field, and the constructor is already the definition of GL's initial values.
        SavedState defaults = new SavedState();
        defaults.save(new FrameStateVk());
        defaults.restore(this);
        normalMatrixDirty = true;
        lightingDirty = true;
    }

    @Override
    public int matrixStackDepth() {
        // GL counts the matrix in effect as one, so an untouched stack is depth 1 and `top` is the index of it.
        return currentStack().depth();
    }

    @Override
    public void polygonMode(int glFace, int glMode) {
        polygonModeFace = glFace;
        polygonModeMode = switch (glMode) {
            case 6912, 6913, 6914 -> glMode;   // GL_POINT, GL_LINE, GL_FILL
            default -> throw unsupported("polygon mode", glMode);
        };
    }

    @Override
    public void polygonOffset(float factor, float units) {
        polygonOffsetFactor = factor;
        polygonOffsetUnits = units;
    }

    @Override
    public void viewport(int x, int y, int width, int height) {
        viewport[0] = x;
        viewport[1] = y;
        viewport[2] = width;
        viewport[3] = height;
    }

    @Override
    public void scissor(int x, int y, int width, int height) {
        scissor[0] = x;
        scissor[1] = y;
        scissor[2] = width;
        scissor[3] = height;
    }

    @Override
    public int[] scissorBox() {
        return scissor;
    }

    @Override
    public void clearColor(float red, float green, float blue, float alpha) {
        clearColor[0] = red;
        clearColor[1] = green;
        clearColor[2] = blue;
        clearColor[3] = alpha;
        // The frame asks for one clear colour, so the last value set here is the one that reaches it.
        net.minecraftforge.client.render.target.FrameRecording recording =
                net.minecraftforge.client.render.target.FrameRecordings.current();
        if (recording != null) {
            recording.setClearColor(red, green, blue, alpha);
        }
    }

    @Override
    public void clearDepth(double depth) {
        clearDepth = depth;
        net.minecraftforge.client.render.target.FrameRecording recording =
                net.minecraftforge.client.render.target.FrameRecordings.current();
        if (recording != null) {
            recording.setClearDepth(depth);
        }
    }

    @Override
    public void clearStencil(int value) {
        clearStencil = value;
    }

    @Override
    public void clear(int glMask) {
        clearMask = glMask;
        net.minecraftforge.client.render.target.FrameRecording recording =
                net.minecraftforge.client.render.target.FrameRecordings.current();
        if (recording != null) {
            recording.requestClear(glMask);
        }
        if (net.minecraftforge.client.render.vertex.FrameDraws.isCollecting()) {
            net.minecraftforge.client.render.vertex.FrameDraws.recordClear(
                    glMask, clearColor.clone(), clearDepth, clearStencil,
                    isEnabled(Capability.SCISSOR_TEST), scissor.clone());
        }
    }

    private MatrixStack currentStack() {
        if (matrixMode == MODELVIEW) {
            return modelViewStack;
        } else if (matrixMode == PROJECTION) {
            return projectionStack;
        } else if (matrixMode == TEXTURE) {
            return textureStacks[activeTextureUnit & 7];
        }
        return modelViewStack;
    }

    private Matrix4f current() {
        normalMatrixDirty = true;
        return currentStack().current();
    }

    @Override
    public void matrixMode(int glMode) {
        matrixMode = switch (glMode) {
            case 5888, 5889, 5890 -> glMode;   // GL_MODELVIEW, GL_PROJECTION, GL_TEXTURE
            default -> throw unsupported("matrix mode", glMode);
        };
    }

    @Override
    public void loadIdentity() {
        normalMatrixDirty = true;
        currentStack().loadIdentity();
    }

    @Override
    public void pushMatrix() {
        normalMatrixDirty = true;
        currentStack().push();
    }

    @Override
    public void popMatrix() {
        normalMatrixDirty = true;
        currentStack().pop();
    }

    @Override
    public void multiply(FloatBuffer columnMajor16) {
        // Copied through a float[] rather than handed to Matrix4f.set(FloatBuffer).
        //
        // Reason: that method's Unsafe implementation takes the buffer's native address field and dereferences it
        // whenever the buffer's order is native - it only asserts isDirect() when joml.debug is enabled, which it is
        // not by default. On a heap FloatBuffer that field is 0, so the read happens at address 0 and the process
        // dies (measured: MemUtilInternalUnsafe.get(Matrix4f, int, FloatBuffer) -> Unsafe.getLongUnaligned). This is
        // not a JDK-version effect and not something a JOML upgrade is expected to fix; heap buffers reach here
        // because FloatBuffer.wrap is what vanilla and mods use.
        //
        // The copy also leaves the caller's buffer position alone, which matters because vanilla reuses these
        // buffers across draws.
        FloatBuffer view = columnMajor16.duplicate();
        view.rewind();
        if (view.remaining() < 16) {
            throw new IllegalArgumentException("a 4x4 matrix needs 16 floats, the buffer has " + view.remaining());
        }
        view.get(multiplyScratchValues);
        // JOML's storage is column-major, the same layout glMultMatrixf reads, so no transpose happens here.
        current().mul(multiplyScratchMatrix.set(multiplyScratchValues));
    }

    @Override
    public void loadMatrix(FloatBuffer columnMajor16) {
        // Same copy-through-a-float[] reason as multiply(): Matrix4f.set(FloatBuffer) dereferences the buffer's
        // native address field on a heap buffer and dies at address 0. The position is left alone for the same
        // reason too - mods reuse these buffers.
        FloatBuffer view = columnMajor16.duplicate();
        view.rewind();
        if (view.remaining() < 16) {
            throw new IllegalArgumentException("a 4x4 matrix needs 16 floats, the buffer has " + view.remaining());
        }
        view.get(multiplyScratchValues);
        // Column-major, the layout glLoadMatrixf reads, so no transpose happens here.
        current().set(multiplyScratchValues);
    }

    @Override
    public void ortho(double left, double right, double bottom, double top, double zNear, double zFar) {
        current().ortho((float) left, (float) right, (float) bottom, (float) top, (float) zNear, (float) zFar);
    }

    @Override
    public void translate(double x, double y, double z) {
        current().translate((float) x, (float) y, (float) z);
    }

    @Override
    public void rotate(float angleDegrees, float x, float y, float z) {
        // glRotatef takes degrees; JOML takes radians. This is the one conversion on the matrix path.
        current().rotate((float) Math.toRadians(angleDegrees), x, y, z);
    }

    @Override
    public void scale(float x, float y, float z) {
        current().scale(x, y, z);
    }

    @Override
    public void scale(double x, double y, double z) {
        // Narrowed at entry, not lost: GL's own matrix stack is float32 (GLfloat matrices), so glScaled accumulates
        // into a float matrix too. Matching that here keeps the result identical to the GL path rather than
        // accidentally more precise.
        current().scale((float) x, (float) y, (float) z);
    }

    @Override
    public void readMatrix(int glMatrixTarget, FloatBuffer out) {
        Matrix4fc source = switch (glMatrixTarget) {
            case MODELVIEW_MATRIX -> matrix(MODELVIEW);
            case PROJECTION_MATRIX -> matrix(PROJECTION);
            case TEXTURE_MATRIX -> matrix(TEXTURE);
            default -> throw FrameStates.unimplemented("state readback (getFloat " + glMatrixTarget + ")",
                    "a matrix target: GL_MODELVIEW_MATRIX, GL_PROJECTION_MATRIX or GL_TEXTURE_MATRIX, which is"
                            + " everything vanilla reads back");
        };
        // Read through get(column, row) rather than Matrix4fc.get(FloatBuffer) for the reason spelled out on
        // multiply(): that overload's Unsafe path trusts the buffer's native address field and dereferences it even
        // for a heap buffer, so it would kill the process on exactly the buffers vanilla and mods build with wrap().
        for (int i = 0; i < 16; i++) {
            readMatrixScratch[i] = source.get(i >> 2, i & 3);   // column-major, the layout glGetFloatv writes
        }
        FloatBuffer view = out.duplicate();
        int at = view.position();
        if (view.limit() - at < 16) {
            throw new IllegalArgumentException("a 4x4 matrix needs 16 floats from position " + at
                    + ", the buffer has " + (view.limit() - at));
        }
        // Written at the caller's position without moving it, which is glGetFloatv's contract: it fills the storage
        // the caller points at. Rewinding instead would write over data a caller deliberately offset past, and
        // advancing would consume a buffer vanilla reuses every frame.
        for (int i = 0; i < 16; i++) {
            view.put(at + i, readMatrixScratch[i]);
        }
    }

    @Override
    public void pushAttributes() {
        // The state is recorded first, because it holds the stack depths to restore to - recording after the push
        // would save the post-push depth and the pop would keep whatever the block pushed.
        SavedState saved = new SavedState();
        saved.save(this);
        // The matrices go on their own stacks rather than into the snapshot: a MatrixStack already knows how to save
        // itself, and copying three stacks of 64 matrices per pushAttrib would allocate on a path a mod calls per
        // object it renders.
        modelViewStack.push();
        projectionStack.push();
        for (MatrixStack stack : textureStacks) {
            stack.push();
        }
        savedAttributes.push(saved);
    }

    @Override
    public void popAttributes() {
        SavedState saved = savedAttributes.poll();
        if (saved == null) {
            throw new IllegalStateException("popAttrib without a matching pushAttrib");
        }
        // The saved index, not "one pop": a matrix pushed inside the block is part of what glPopAttrib undoes, so the
        // stack goes back to the depth it had when the block opened. Popping once would leave the inner matrix in
        // place - measured, before this was written that way.
        modelViewStack.restoreTop(saved.modelViewTop);
        projectionStack.restoreTop(saved.projectionTop);
        for (int unit = 0; unit < textureStacks.length; unit++) {
            textureStacks[unit].restoreTop(saved.textureTops[unit]);
        }
        saved.restore(this);
        normalMatrixDirty = true;
        lightingDirty = true;
    }

    /** The matrix of one mode, for a draw that needs to hand it to a push constant. */
    public Matrix4fc matrix(int glMode) {
        if (glMode == MODELVIEW) {
            return modelViewStack.current();
        } else if (glMode == PROJECTION) {
            return projectionStack.current();
        } else if (glMode == TEXTURE) {
            return textureStacks[activeTextureUnit & 7].current();
        }
        return modelViewStack.current();
    }

    private static int comparison(int glComparison) {
        return switch (glComparison) {
            case 512, 513, 514, 515, 516, 517, 518, 519 -> glComparison;
            default -> throw new IllegalArgumentException("not a GL comparison function: " + glComparison);
        };
    }

    private static int blendFactor(int glFactor) {
        return switch (glFactor) {
            case 0, 1, 768, 769, 770, 771, 772, 773, 774, 775, 776, 777 -> glFactor;
            default -> throw new IllegalArgumentException("not a GL blend factor: " + glFactor);
        };
    }

    private static IllegalArgumentException unsupported(String what, int value) {
        return new IllegalArgumentException("unsupported " + what + ": " + value);
    }
}
