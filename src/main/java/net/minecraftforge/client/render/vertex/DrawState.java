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
 */
public record DrawState(float[] mvp, boolean depthTest, boolean depthWrite, boolean blend, boolean cull,
                        int blendSourceRgb, int blendDestinationRgb, int blendSourceAlpha, int blendDestinationAlpha,
                        int texture, boolean scissorEnabled, int[] scissor, float[] color) {

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

        return new DrawState(values,
                state.isEnabled(net.minecraftforge.client.render.state.backend.Capability.DEPTH_TEST),
                state.depthWriteEnabled(),
                state.isEnabled(net.minecraftforge.client.render.state.backend.Capability.BLEND),
                state.isEnabled(net.minecraftforge.client.render.state.backend.Capability.CULL_FACE),
                sourceRgb, destinationRgb, sourceAlpha, destinationAlpha,
                boundTexture(),
                state.isEnabled(net.minecraftforge.client.render.state.backend.Capability.SCISSOR_TEST),
                state.scissorBox(),
                state.currentColor());
    }

    /**
     * The colour as an {@code 0xAARRGGBB} int, for a draw whose vertex format carries no colour of its own.
     *
     * <p>Vanilla sets text and tint colours with {@code GlStateManager.color(...)} and then draws vertices that have
     * no colour attribute at all - {@code FontRenderer} is the obvious one. In GL the current colour applies per
     * vertex; a pipeline needs it in the vertex data, so this is the value that fills the gap.</p>
     */
    public int colorArgb() {
        return (Math.round(color[3] * 255.0F) << 24)
                | (Math.round(color[0] * 255.0F) << 16)
                | (Math.round(color[1] * 255.0F) << 8)
                | Math.round(color[2] * 255.0F);
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
}
