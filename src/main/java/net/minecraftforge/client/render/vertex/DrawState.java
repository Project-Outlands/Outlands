package net.minecraftforge.client.render.vertex;

/**
 * The state one draw was issued with.
 *
 * <p><b>Why this is captured rather than read at replay time.</b> GL reads its matrix stack, its enable bits and its
 * texture binding at the moment the draw call happens. A frame that is recorded and replayed later has to carry those
 * values with each draw, or every draw ends up using whatever the state happened to be when the replay ran.</p>
 */
public final class DrawState {

    private final float[] mvp = new float[16];
    private boolean depthTest;
    private boolean depthWrite;
    private int depthFunc;
    private boolean blend;
    private boolean cull;
    private int blendSourceRgb;
    private int blendDestinationRgb;
    private int blendSourceAlpha;
    private int blendDestinationAlpha;
    private int texture;
    private boolean scissorEnabled;
    private final int[] scissor = new int[4];
    private final float[] color = new float[4];
    private boolean texture2d;
    private boolean alphaTest;
    private float alphaReference;
    private int alphaComparison;
    private final int[] viewport = new int[4];
    private final boolean[] colorMask = new boolean[4];
    private final float[] lightmap = new float[2];
    private boolean hasLightmap;
    private boolean lightingEnabled;
    private final float[] normalMatrix = new float[9];
    private boolean hasNormalMatrix;
    /**
     * The lighting model the draw was issued under.
     *
     * <p><b>Referenced, not copied, and that is safe because the model is immutable.</b> The alternative - copying
     * eight lights' colours and directions into every draw - is what the two light vectors did before, and it was
     * there for a real reason: {@code glLight} rewrote the state's vectors in place, so a replay-time read gave item
     * draws the world's light direction. A snapshot that is replaced rather than mutated has the same guarantee
     * without the copy.</p>
     */
    private LightingModel lighting = LightingModel.GL_DEFAULT;
    private final float[] textureMatrix = new float[16];
    private boolean hasTextureMatrix;
    private boolean fogEnabled;
    private int fogMode;
    private float fogStart;
    private float fogEnd;
    private float fogDensity;
    private final float[] fogColor = new float[4];
    private final float[] overlayColor = new float[4];
    /** 1 when {@link #overlayColor} is outline mode (replace the light, alpha from the lightmap), 0 for a mix. */
    private int overlayMode;
    /**
     * The shader program the draw was issued under, or null for the fixed-function path.
     *
     * <p><b>Snapshotted like every other piece of state, and for the same reason.</b> {@code glUseProgram} is global
     * state: a frame that records "draw, then switch program, then draw" would apply the second program to both draws
     * if this were read at replay time. It is not part of {@code glPushAttrib} in GL either, which is why it is
     * captured here rather than tracked with the enable bits.</p>
     */
    private net.minecraftforge.client.render.shader.GlShaders.Program program;
    /**
     * The program's uniform block as it stood at the draw, and the textures its samplers read.
     *
     * <p>Both are copied rather than referenced. The block is one array per program that every later
     * {@code glUniform*} write lands in - the outline chain sets {@code BlurDir} between two passes - so holding the
     * array would give every draw in the frame the last values written. The sampler textures are resolved from the
     * texture unit each sampler was pointed at, for the same reason: the unit binding is global state too.</p>
     */
    private byte[] programBlock = new byte[0];
    private int[] programSamplers = new int[0];

    public DrawState() {}

    public DrawState(float[] mvp, boolean depthTest, boolean depthWrite, int depthFunc, boolean blend, boolean cull,
                     int blendSourceRgb, int blendDestinationRgb, int blendSourceAlpha, int blendDestinationAlpha,
                     int texture, boolean scissorEnabled, int[] scissor, float[] color, boolean texture2d,
                     boolean alphaTest, float alphaReference, int alphaComparison, int[] viewport,
                     boolean[] colorMask, float[] lightmap,
                     boolean lightingEnabled, float[] normalMatrix, LightingModel lighting,
                     float[] textureMatrix) {
        if (mvp.length != 16) {
            throw new IllegalArgumentException("an mvp matrix has 16 values, got " + mvp.length);
        }
        if (scissor.length != 4) {
            throw new IllegalArgumentException("a scissor box has 4 values, got " + scissor.length);
        }
        if (color.length != 4) {
            throw new IllegalArgumentException("a colour has 4 components, got " + color.length);
        }
        if (viewport.length != 4) {
            throw new IllegalArgumentException("a viewport has 4 values, got " + viewport.length);
        }
        if (colorMask.length != 4) {
            throw new IllegalArgumentException("a colour mask has 4 channels, got " + colorMask.length);
        }

        System.arraycopy(mvp, 0, this.mvp, 0, 16);
        this.depthTest = depthTest;
        this.depthWrite = depthWrite;
        this.depthFunc = depthFunc;
        this.blend = blend;
        this.cull = cull;
        this.blendSourceRgb = blendSourceRgb;
        this.blendDestinationRgb = blendDestinationRgb;
        this.blendSourceAlpha = blendSourceAlpha;
        this.blendDestinationAlpha = blendDestinationAlpha;
        this.texture = texture;
        this.scissorEnabled = scissorEnabled;
        System.arraycopy(scissor, 0, this.scissor, 0, 4);
        System.arraycopy(color, 0, this.color, 0, 4);
        this.texture2d = texture2d;
        this.alphaTest = alphaTest;
        this.alphaReference = alphaReference;
        this.alphaComparison = alphaComparison;
        System.arraycopy(viewport, 0, this.viewport, 0, 4);
        System.arraycopy(colorMask, 0, this.colorMask, 0, 4);
        if (lightmap != null) {
            this.hasLightmap = true;
            this.lightmap[0] = lightmap[0];
            this.lightmap[1] = lightmap[1];
        } else {
            this.hasLightmap = false;
        }
        this.lightingEnabled = lightingEnabled;
        if (normalMatrix != null) {
            this.hasNormalMatrix = true;
            System.arraycopy(normalMatrix, 0, this.normalMatrix, 0, 9);
        } else {
            this.hasNormalMatrix = false;
        }
        this.lighting = lighting != null ? lighting : LightingModel.GL_DEFAULT;
        if (textureMatrix != null) {
            this.hasTextureMatrix = true;
            System.arraycopy(textureMatrix, 0, this.textureMatrix, 0, 16);
        } else {
            this.hasTextureMatrix = false;
        }
    }

    public void copyFrom(DrawState other) {
        System.arraycopy(other.mvp, 0, this.mvp, 0, 16);
        this.depthTest = other.depthTest;
        this.depthWrite = other.depthWrite;
        this.depthFunc = other.depthFunc;
        this.blend = other.blend;
        this.cull = other.cull;
        this.blendSourceRgb = other.blendSourceRgb;
        this.blendDestinationRgb = other.blendDestinationRgb;
        this.blendSourceAlpha = other.blendSourceAlpha;
        this.blendDestinationAlpha = other.blendDestinationAlpha;
        this.texture = other.texture;
        this.scissorEnabled = other.scissorEnabled;
        System.arraycopy(other.scissor, 0, this.scissor, 0, 4);
        System.arraycopy(other.color, 0, this.color, 0, 4);
        this.texture2d = other.texture2d;
        this.alphaTest = other.alphaTest;
        this.alphaReference = other.alphaReference;
        this.alphaComparison = other.alphaComparison;
        System.arraycopy(other.viewport, 0, this.viewport, 0, 4);
        System.arraycopy(other.colorMask, 0, this.colorMask, 0, 4);
        this.hasLightmap = other.hasLightmap;
        if (this.hasLightmap) {
            this.lightmap[0] = other.lightmap[0];
            this.lightmap[1] = other.lightmap[1];
        }
        this.lightingEnabled = other.lightingEnabled;
        this.hasNormalMatrix = other.hasNormalMatrix;
        if (this.hasNormalMatrix) {
            System.arraycopy(other.normalMatrix, 0, this.normalMatrix, 0, 9);
        }
        this.lighting = other.lighting;
        this.hasTextureMatrix = other.hasTextureMatrix;
        if (this.hasTextureMatrix) {
            System.arraycopy(other.textureMatrix, 0, this.textureMatrix, 0, 16);
        }
        this.fogEnabled = other.fogEnabled;
        this.fogMode = other.fogMode;
        this.fogStart = other.fogStart;
        this.fogEnd = other.fogEnd;
        this.fogDensity = other.fogDensity;
        System.arraycopy(other.fogColor, 0, this.fogColor, 0, 4);
        System.arraycopy(other.overlayColor, 0, this.overlayColor, 0, 4);
        this.overlayMode = other.overlayMode;
        this.program = other.program;
        if (other.programBlock.length != this.programBlock.length) {
            this.programBlock = new byte[other.programBlock.length];
        }
        System.arraycopy(other.programBlock, 0, this.programBlock, 0, other.programBlock.length);
        if (other.programSamplers.length != this.programSamplers.length) {
            this.programSamplers = new int[other.programSamplers.length];
        }
        System.arraycopy(other.programSamplers, 0, this.programSamplers, 0, other.programSamplers.length);
    }

    public void captureFrom(net.minecraftforge.client.render.state.backend.FrameState state) {
        state.computeMvp(this.mvp);
        this.depthTest = state.isEnabled(net.minecraftforge.client.render.state.backend.Capability.DEPTH_TEST);
        this.depthWrite = state.depthWriteEnabled();
        this.depthFunc = state.depthFunc();
        this.blend = state.isEnabled(net.minecraftforge.client.render.state.backend.Capability.BLEND);
        this.cull = state.isEnabled(net.minecraftforge.client.render.state.backend.Capability.CULL_FACE);

        int[] separate = state.blendFactors();
        if (separate != null) {
            this.blendSourceRgb = separate[0];
            this.blendDestinationRgb = separate[1];
            this.blendSourceAlpha = separate[2];
            this.blendDestinationAlpha = separate[3];
        } else {
            this.blendSourceRgb = state.blendSource();
            this.blendDestinationRgb = state.blendDestination();
            this.blendSourceAlpha = this.blendSourceRgb;
            this.blendDestinationAlpha = this.blendDestinationRgb;
        }

        this.texture = boundTexture();
        this.scissorEnabled = state.isEnabled(net.minecraftforge.client.render.state.backend.Capability.SCISSOR_TEST);
        System.arraycopy(state.scissorBox(), 0, this.scissor, 0, 4);
        System.arraycopy(state.currentColor(), 0, this.color, 0, 4);
        this.texture2d = texture2dEnabled(state);
        this.alphaTest = state.alphaTestEnabled();
        this.alphaReference = state.alphaReference();
        this.alphaComparison = state.alphaComparison();
        System.arraycopy(state.viewport(), 0, this.viewport, 0, 4);
        System.arraycopy(state.colorMask(), 0, this.colorMask, 0, 4);

        float[] lm = lightmapCoord(state);
        if (lm != null) {
            this.hasLightmap = true;
            this.lightmap[0] = lm[0];
            this.lightmap[1] = lm[1];
        } else {
            this.hasLightmap = false;
        }

        this.lightingEnabled = state.isEnabled(net.minecraftforge.client.render.state.backend.Capability.LIGHTING);
        if (this.lightingEnabled) {
            float[] nm = state.normalMatrix();
            if (nm != null) {
                this.hasNormalMatrix = true;
                System.arraycopy(nm, 0, this.normalMatrix, 0, 9);
            } else {
                this.hasNormalMatrix = false;
            }
            LightingModel model = state.lighting();
            this.lighting = model != null ? model : LightingModel.GL_DEFAULT;
        } else {
            this.hasNormalMatrix = false;
        }

        float[] tm = state.textureMatrix();
        if (tm != null) {
            this.hasTextureMatrix = true;
            System.arraycopy(tm, 0, this.textureMatrix, 0, 16);
        } else {
            this.hasTextureMatrix = false;
        }

        this.fogEnabled = state.isEnabled(net.minecraftforge.client.render.state.backend.Capability.FOG);
        if (this.fogEnabled) {
            this.fogMode = state.fogMode();
            this.fogStart = state.fogStart();
            this.fogEnd = state.fogEnd();
            this.fogDensity = state.fogDensity();
            float[] fc = state.fogColor();
            if (fc != null && fc.length >= 4) {
                System.arraycopy(fc, 0, this.fogColor, 0, 4);
            }
        } else {
            this.fogMode = 0;
            this.fogStart = 0.0f;
            this.fogEnd = 1.0f;
            this.fogDensity = 1.0f;
        }

        captureProgram();

        this.overlayMode = state.overlayMode();
        float[] oc = state.overlayColor();
        if (oc != null && oc.length >= 4) {
            System.arraycopy(oc, 0, this.overlayColor, 0, 4);
        } else {
            this.overlayColor[3] = 0.0f;
        }
    }

    private static final DrawState SCRATCH_CURRENT = new DrawState();

    public static DrawState current() {
        var state = net.minecraftforge.client.render.state.backend.FrameStates.instance();
        if (state == null) {
            throw new IllegalStateException("no frame state installed: draws are being captured on the GL path");
        }
        SCRATCH_CURRENT.captureFrom(state);
        return SCRATCH_CURRENT;
    }

    /**
     * Snapshots the active program, its uniform block and the textures its samplers read.
     *
     * <p>A sampler that was never pointed at a unit reads unit 0, which is what GL does: {@code glUniform1i} on a
     * sampler is the only way to move it, and the default value of an unset sampler uniform is 0.</p>
     */
    private void captureProgram() {
        net.minecraftforge.client.render.shader.GlShaders.Program active =
                net.minecraftforge.client.render.shader.GlShaders.program(
                        net.minecraftforge.client.render.shader.GlShaders.currentProgram());
        this.program = active;
        if (active == null) {
            this.programBlock = new byte[0];
            this.programSamplers = new int[0];
            return;
        }

        byte[] block = active.blockBytes();
        if (block.length != this.programBlock.length) {
            this.programBlock = new byte[block.length];
        }
        System.arraycopy(block, 0, this.programBlock, 0, block.length);

        java.util.List<net.minecraftforge.client.render.shader.GlShaders.Uniform> samplers =
                active.samplerUniforms();
        if (samplers.size() != this.programSamplers.length) {
            this.programSamplers = new int[samplers.size()];
        }
        net.minecraftforge.client.render.texture.TextureBackend textures =
                net.minecraftforge.client.render.texture.TextureBackends.forNonGl();
        for (int i = 0; i < samplers.size(); i++) {
            int unit = active.samplerUnit(samplers.get(i).name());
            this.programSamplers[i] = textures == null ? 0 : textures.boundTexture(unit < 0 ? 0 : unit);
        }
    }

    /** The program this draw was issued under, or null for the fixed-function one. */
    public net.minecraftforge.client.render.shader.GlShaders.Program program() { return program; }

    /** The program's uniform block at the time of the draw. Empty when there is no program. */
    public byte[] programBlock() { return programBlock; }

    /** The texture handle each of the program's samplers read, in {@code samplerUniforms()} order. */
    public int[] programSamplers() { return programSamplers; }

    public float[] overlayColor() { return overlayColor; }

    /** 1 when the overlay replaces the light (outline mode), 0 when it mixes into it (hurt flash). */
    public int overlayMode() { return overlayMode; }

    public float[] mvp() { return mvp; }
    public boolean depthTest() { return depthTest; }
    public boolean depthWrite() { return depthWrite; }
    public int depthFunc() { return depthFunc; }
    public boolean blend() { return blend; }
    public boolean cull() { return cull; }
    public int blendSourceRgb() { return blendSourceRgb; }
    public int blendDestinationRgb() { return blendDestinationRgb; }
    public int blendSourceAlpha() { return blendSourceAlpha; }
    public int blendDestinationAlpha() { return blendDestinationAlpha; }
    public int texture() { return texture; }
    public boolean scissorEnabled() { return scissorEnabled; }
    public int[] scissor() { return scissor; }
    public float[] color() { return color; }
    public boolean texture2d() { return texture2d; }
    public boolean alphaTest() { return alphaTest; }
    public float alphaReference() { return alphaReference; }
    public int alphaComparison() { return alphaComparison; }
    public int[] viewport() { return viewport; }
    public boolean[] colorMask() { return colorMask; }
    public float[] lightmap() { return hasLightmap ? lightmap : null; }
    public boolean lightingEnabled() { return lightingEnabled; }

    /** The lighting model this draw was issued under; never {@code null}. */
    public LightingModel lighting() { return lighting; }
    public float[] normalMatrix() { return hasNormalMatrix ? normalMatrix : null; }
    public float[] textureMatrix() { return hasTextureMatrix ? textureMatrix : null; }
    public boolean fogEnabled() { return fogEnabled; }
    public int fogMode() { return fogMode; }
    public float fogStart() { return fogStart; }
    public float fogEnd() { return fogEnd; }
    public float fogDensity() { return fogDensity; }
    public float[] fogColor() { return fogColor; }

    public boolean hasViewport() {
        return viewport[2] > 0 && viewport[3] > 0;
    }

    public boolean samplesTexture() {
        return texture2d;
    }

    public int colorArgb() {
        int r = Math.round(Math.max(0.0F, Math.min(1.0F, color[0])) * 255.0F);
        int g = Math.round(Math.max(0.0F, Math.min(1.0F, color[1])) * 255.0F);
        int b = Math.round(Math.max(0.0F, Math.min(1.0F, color[2])) * 255.0F);
        int a = Math.round(Math.max(0.0F, Math.min(1.0F, color[3])) * 255.0F);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static float[] lightmapCoord(net.minecraftforge.client.render.state.backend.FrameState state) {
        net.minecraftforge.client.render.texture.TextureBackend tb =
                net.minecraftforge.client.render.texture.TextureBackends.forNonGl();
        if (tb != null && !tb.isTexture2DEnabled(1)) {
            return null;
        }
        return state.lightmap();
    }

    private static int boundTexture() {
        net.minecraftforge.client.render.texture.TextureBackend textures =
                net.minecraftforge.client.render.texture.TextureBackends.forNonGl();
        return textures == null ? 0 : textures.boundTexture();
    }

    private static boolean texture2dEnabled(net.minecraftforge.client.render.state.backend.FrameState state) {
        net.minecraftforge.client.render.texture.TextureBackend textures =
                net.minecraftforge.client.render.texture.TextureBackends.forNonGl();
        return textures != null ? textures.isTexture2DEnabled()
                : state.isEnabled(net.minecraftforge.client.render.state.backend.Capability.TEXTURE_2D);
    }
}
