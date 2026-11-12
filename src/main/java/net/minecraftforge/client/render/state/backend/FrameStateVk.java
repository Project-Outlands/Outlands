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
    private int attributeDepth;

    /** Fog state, at the defaults GL documents: GL_EXP at density 1, no colour, the linear range 0..1. */
    private int fogMode = 2048;
    private final float[] fogColor = {0.0F, 0.0F, 0.0F, 0.0F};
    private float fogDensity = 1.0F;
    private float fogStart = 0.0F;
    private float fogEnd = 1.0F;
    /** The lightmap coordinate a draw gets when its format carries none. Null until the game sets one. */
    private final float[] lightmap = new float[2];
    private boolean hasLightmap;
    /** The texture the game's lightmap lives in, or {@code 0}. */
    private int lightmapTexture;
    private final float[] light0Eye = {0.1617F, 0.8085F, -0.5659F};
    private final float[] light1Eye = {-0.1617F, 0.8085F, 0.5659F};
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
    private static final int GL_TEXTURE_ENV = 0x2200;
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
        if (pname == 4611 && params != null && params.remaining() >= 3) { // GL_POSITION
            int pos = params.position();
            float x = params.get(pos);
            float y = params.get(pos + 1);
            float z = params.get(pos + 2);
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
            if (light == 16384) { // GL_LIGHT0
                light0Eye[0] = ex;
                light0Eye[1] = ey;
                light0Eye[2] = ez;
            } else if (light == 16385) { // GL_LIGHT1
                light1Eye[0] = ex;
                light1Eye[1] = ey;
                light1Eye[2] = ez;
            }
        }
    }

    @Override
    public float[] light0Eye() {
        return light0Eye;
    }

    @Override
    public float[] light1Eye() {
        return light1Eye;
    }

    @Override
    public void fogMode(int glMode) {
        fogMode = switch (glMode) {
            case 2048, 2049, 9729 -> glMode;   // GL_EXP, GL_EXP2, GL_LINEAR
            default -> throw unsupported("fog mode", glMode);
        };
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
    /** 1 when the overlay is outline mode, 0 for the hurt-flash interpolate. */
    private int overlayMode;

    @Override
    public int overlayMode() {
        return overlayMode;
    }

    private void updateOverlayColor() {
        for (int unit = 0; unit < TEXTURE_UNITS; unit++) {
            // Outline mode: GlStateManager.enableOutlineMode sets this unit to GL_REPLACE with the constant colour as
            // source 0, so the light becomes the team colour and the silhouette's alpha comes from the lightmap. That
            // is how a glowing entity gets a shape for entity_sobel to trace - the colour is replaced, nothing is mixed.
            if (texEnvMode[unit] == GL_COMBINE && texCombineRgb[unit] == 7681 /* GL_REPLACE */) {
                System.arraycopy(texEnvColor[unit], 0, overlayColor, 0, 4);
                overlayMode = 1;
                return;
            }
            if (texEnvMode[unit] == GL_COMBINE
                    && texCombineRgb[unit] == GL_INTERPOLATE
                    && texSource0Rgb[unit] == GL_CONSTANT
                    && texSource1Rgb[unit] == GL_PREVIOUS
                    && texSource2Rgb[unit] == GL_CONSTANT
                    && texOperand2Rgb[unit] == GL_SRC_ALPHA) {
                System.arraycopy(texEnvColor[unit], 0, overlayColor, 0, 4);
                overlayMode = 0;
                return;
            }
        }
        overlayMode = 0;
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
    public void clear(int glMask) {
        clearMask = glMask;
        net.minecraftforge.client.render.target.FrameRecording recording =
                net.minecraftforge.client.render.target.FrameRecordings.current();
        if (recording != null) {
            recording.requestClear(glMask);
        }
        if (net.minecraftforge.client.render.vertex.FrameDraws.isCollecting()) {
            net.minecraftforge.client.render.vertex.FrameDraws.recordClear(
                    glMask, clearColor.clone(), clearDepth,
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
        attributeDepth++;
    }

    @Override
    public void popAttributes() {
        if (attributeDepth == 0) {
            throw new IllegalStateException("popAttrib without a matching pushAttrib");
        }
        attributeDepth--;
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
