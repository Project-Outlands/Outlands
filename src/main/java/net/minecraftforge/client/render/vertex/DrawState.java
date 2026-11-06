package net.minecraftforge.client.render.vertex;

/**
 * The state one draw was issued with.
 *
 * <p><b>Why this is captured rather than read at replay time.</b> GL reads its matrix stack and its enable bits at
 * the moment the draw call happens. A frame that is recorded and replayed later has to carry those values with each
 * draw, or every draw ends up using whatever the state happened to be when the replay ran - which draws the whole
 * frame with the last matrix that was set, and looks like geometry in the wrong place rather than like a missing
 * value.</p>
 *
 * @param mvp        projection times modelview, column-major, 16 values
 * @param depthTest  whether depth testing was enabled
 * @param depthWrite whether depth writes were enabled
 * @param blend      whether blending was enabled
 * @param cull       whether face culling was enabled
 */
public record DrawState(float[] mvp, boolean depthTest, boolean depthWrite, boolean blend, boolean cull) {

    public DrawState {
        if (mvp.length != 16) {
            throw new IllegalArgumentException("an mvp matrix has 16 values, got " + mvp.length);
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
        return new DrawState(values,
                state.isEnabled(net.minecraftforge.client.render.state.backend.Capability.DEPTH_TEST),
                state.depthWriteEnabled(),
                state.isEnabled(net.minecraftforge.client.render.state.backend.Capability.BLEND),
                state.isEnabled(net.minecraftforge.client.render.state.backend.Capability.CULL_FACE));
    }
}
