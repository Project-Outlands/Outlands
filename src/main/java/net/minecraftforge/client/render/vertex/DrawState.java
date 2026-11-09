package net.minecraftforge.client.render.vertex;

/**
 * The state one draw was issued with.
 *
 * <p><b>Why this is captured rather than read at replay time.</b> GL reads its matrix stack, its enable bits and its
 * texture binding at the moment the draw call happens. A frame that is recorded and replayed later has to carry those
 * values with each draw, or every draw ends up using whatever the state happened to be when the replay ran - which
 * draws the whole frame with the last matrix that was set, and looks like geometry in the wrong place rather than
 * like a missing value. The texture binding has exactly the same failure mode, and it is the one that made the menu
 * come out blank: the last texture bound in a frame is almost never the one the first draw wanted.</p>
 *
 * <p><b>Blend factors are part of the draw, not of the pipeline.</b> Vulkan bakes blend state into the pipeline, so
 * they are part of the key, but GL let the game change them between draws inside one frame. Carrying them here is
 * what lets a single stream serve a frame that switches from vanilla's {@code SRC_ALPHA, ONE_MINUS_SRC_ALPHA} to
 * additive blending and back.</p>
 *
 * @param mvp                  projection times modelview, column-major, 16 values
 * @param depthTest            whether depth testing was enabled
 * @param depthWrite           whether depth writes were enabled
 * @param blend                whether blending was enabled
 * @param cull                 whether face culling was enabled
 * @param blendSourceRgb       the GL source factor for RGB, meaningless when {@code blend} is false
 * @param blendDestinationRgb  the GL destination factor for RGB
 * @param blendSourceAlpha     the GL source factor for alpha
 * @param blendDestinationAlpha the GL destination factor for alpha
 * @param texture              the texture bound on unit 0, or {@code 0} when nothing was bound
 * @param scissorEnabled       whether the scissor test was on
 * @param scissor              the scissor rectangle as {@code x, y, width, height} in GL's bottom-up pixels
 * @param color                the current vertex colour, four components in 0..1
 * @param texture2d            whether texturing was enabled, i.e. whether this draw samples the bound texture at all
 * @param alphaTest            whether the alpha test was enabled, which decides whether a fragment exists at all
 * @param alphaReference       the alpha test's reference value
 * @param alphaComparison      the alpha test's GL comparison
 * @param viewport             the viewport as {@code x, y, width, height} in GL's bottom-up pixels
 * @param colorMask            which of red, green, blue, alpha the draw may write
 * @param lightmap             the constant lightmap coordinate, {@code u, v} in 0..1, or {@code null} when the game
 *                             set none; used for geometry whose format carries no lightmap attribute
 * @param depthFunc            the depth comparison function (e.g. GL_LEQUAL 515, GL_EQUAL 514)
 * @param textureMatrix        the 4x4 texture matrix for mode 5890, or {@code null} if identity
 */
public record DrawState(float[] mvp, boolean depthTest, boolean depthWrite, int depthFunc, boolean blend, boolean cull,
                        int blendSourceRgb, int blendDestinationRgb, int blendSourceAlpha, int blendDestinationAlpha,
                        int texture, boolean scissorEnabled, int[] scissor, float[] color, boolean texture2d,
                        boolean alphaTest, float alphaReference, int alphaComparison, int[] viewport,
                        boolean[] colorMask, float[] lightmap,
                        boolean lightingEnabled, float[] normalMatrix, float[] light0, float[] light1,
                        float[] textureMatrix) {

    public DrawState {
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
    }

    /**
     * Reads the state the game has set through {@code GlStateManager}.
     *
     * @throws IllegalStateException when no frame state is installed, which means the GL path should be running
     */
    public static DrawState current() {
        var state = net.minecraftforge.client.render.state.backend.FrameStates.instance();
        if (state == null) {
            throw new IllegalStateException("no frame state installed: draws are being captured on the GL path");
        }
        org.joml.Matrix4f mvp = new org.joml.Matrix4f();
        mvp.set(state.projection()).mul(state.modelView());

        float[] values = new float[16];
        mvp.get(values);

        // A backend that records blend factors separately has four; GL's plain blendFunc sets two that apply to
        // both channels, so the separate form is the one to read when it exists.
        int[] separate = state.blendFactors();
        int sourceRgb;
        int destinationRgb;
        int sourceAlpha;
        int destinationAlpha;
        if (separate != null) {
            sourceRgb = separate[0];
            destinationRgb = separate[1];
            sourceAlpha = separate[2];
            destinationAlpha = separate[3];
        } else {
            sourceRgb = state.blendSource();
            destinationRgb = state.blendDestination();
            sourceAlpha = sourceRgb;
            destinationAlpha = destinationRgb;
        }

        boolean lighting = state.isEnabled(net.minecraftforge.client.render.state.backend.Capability.LIGHTING);
        float[] normalMatrix = null;
        float[] l0 = null;
        float[] l1 = null;
        if (lighting) {
            normalMatrix = state.normalMatrix();
            l0 = state.light0Eye();
            l1 = state.light1Eye();
        }

        return new DrawState(values,
                state.isEnabled(net.minecraftforge.client.render.state.backend.Capability.DEPTH_TEST),
                state.depthWriteEnabled(),
                state.depthFunc(),
                state.isEnabled(net.minecraftforge.client.render.state.backend.Capability.BLEND),
                state.isEnabled(net.minecraftforge.client.render.state.backend.Capability.CULL_FACE),
                sourceRgb, destinationRgb, sourceAlpha, destinationAlpha,
                boundTexture(),
                state.isEnabled(net.minecraftforge.client.render.state.backend.Capability.SCISSOR_TEST),
                state.scissorBox(),
                state.currentColor(),
                texture2dEnabled(state),
                state.alphaTestEnabled(), state.alphaReference(), state.alphaComparison(),
                state.viewport(),
                state.colorMask(),
                lightmapCoord(state),
                lighting, normalMatrix, l0, l1,
                state.textureMatrix());
    }

    private static float[] lightmapCoord(net.minecraftforge.client.render.state.backend.FrameState state) {
        net.minecraftforge.client.render.texture.TextureBackend tb =
                net.minecraftforge.client.render.texture.TextureBackends.forNonGl();
        // If texture unit 1 (lightmap unit) is disabled, the lightmap is not in effect (e.g. GUI rendering).
        // Returning null ensures unlit geometry gets full brightness rather than stale world lighting.
        if (tb != null && !tb.isTexture2DEnabled(1)) {
            return null;
        }
        return state.lightmap();
    }

    /**
     * The viewport this draw was issued with.
     *
     * <p><b>Not a detail.</b> The main menu renders its panorama into a 256x256 viewport at the bottom-left corner of
     * the framebuffer, blurs that corner in place, and then draws the result across the whole screen from a centred
     * crop. A renderer that always uses the full window instead draws the panorama over the entire frame, so the
     * 256x256 the blur copies is a corner of that, and the centred crop at the end samples a small piece of it: the
     * background comes out at an angle vanilla never shows and the blur has nothing coherent to accumulate. Measured,
     * not assumed.</p>
     */
    public boolean hasViewport() {
        return viewport[2] > 0 && viewport[3] > 0;
    }

    /**
     * Whether this draw samples a texture at all.
     *
     * <p><b>Not the same thing as having one bound.</b> {@code GlStateManager.disableTexture2D()} leaves the binding
     * untouched and makes the fragment stage ignore it - the texture environment's output becomes plain white, and
     * the colour comes entirely from the vertices. Vanilla relies on that for every solid overlay: the menu's
     * darkening gradients, tooltip backgrounds, the {@code drawGradientRect} panels. A backend that samples anyway
     * reads whatever texel lands at the (absent, so zero) texture coordinate of the previously bound image, which
     * tints those overlays with a colour and an alpha nobody asked for. Measured as the main menu never getting
     * darker than a flat pale grey no matter what its gradient said.</p>
     */
    public boolean samplesTexture() {
        return texture2d;
    }

    /**
     * The colour packed as 0xAARRGGBB for conversion into vertex attributes.
     */
    public int colorArgb() {
        int r = Math.round(Math.max(0.0F, Math.min(1.0F, color[0])) * 255.0F);
        int g = Math.round(Math.max(0.0F, Math.min(1.0F, color[1])) * 255.0F);
        int b = Math.round(Math.max(0.0F, Math.min(1.0F, color[2])) * 255.0F);
        int a = Math.round(Math.max(0.0F, Math.min(1.0F, color[3])) * 255.0F);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * The texture the draw will actually sample: the one on the active unit.
     *
     * <p>Not hardcoded to unit 0. The world leaves the lightmap bound on unit 1 and the active unit pointed there,
     * and a GUI drawn afterwards samples whatever the active unit holds unless the game rebinds - which is exactly
     * what GL did, and what a backend has to reproduce to draw the same picture. Reading unit 0 here made the mod
     * list's scroll bar sample the option background, which is how this was found.</p>
     */
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
