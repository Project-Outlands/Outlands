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
    private float[] light0;
    private float[] light1;
    private final float[] textureMatrix = new float[16];
    private boolean hasTextureMatrix;

    public DrawState() {}

    public DrawState(float[] mvp, boolean depthTest, boolean depthWrite, int depthFunc, boolean blend, boolean cull,
                     int blendSourceRgb, int blendDestinationRgb, int blendSourceAlpha, int blendDestinationAlpha,
                     int texture, boolean scissorEnabled, int[] scissor, float[] color, boolean texture2d,
                     boolean alphaTest, float alphaReference, int alphaComparison, int[] viewport,
                     boolean[] colorMask, float[] lightmap,
                     boolean lightingEnabled, float[] normalMatrix, float[] light0, float[] light1,
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
        this.light0 = light0;
        this.light1 = light1;
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
        this.light0 = other.light0;
        this.light1 = other.light1;
        this.hasTextureMatrix = other.hasTextureMatrix;
        if (this.hasTextureMatrix) {
            System.arraycopy(other.textureMatrix, 0, this.textureMatrix, 0, 16);
        }
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
            this.light0 = state.light0Eye();
            this.light1 = state.light1Eye();
        } else {
            this.hasNormalMatrix = false;
            this.light0 = null;
            this.light1 = null;
        }

        float[] tm = state.textureMatrix();
        if (tm != null) {
            this.hasTextureMatrix = true;
            System.arraycopy(tm, 0, this.textureMatrix, 0, 16);
        } else {
            this.hasTextureMatrix = false;
        }
    }

    public static DrawState current() {
        var state = net.minecraftforge.client.render.state.backend.FrameStates.instance();
        if (state == null) {
            throw new IllegalStateException("no frame state installed: draws are being captured on the GL path");
        }
        DrawState ds = new DrawState();
        ds.captureFrom(state);
        return ds;
    }

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
    public float[] normalMatrix() { return hasNormalMatrix ? normalMatrix : null; }
    public float[] light0() { return light0; }
    public float[] light1() { return light1; }
    public float[] textureMatrix() { return hasTextureMatrix ? textureMatrix : null; }

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
