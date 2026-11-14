package net.minecraftforge.client.render;

import net.minecraftforge.client.render.shim.ShimGl;
import net.minecraftforge.client.render.state.backend.FrameState;
import net.minecraftforge.client.render.state.backend.FrameStates;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The state setters a pack calls directly, driven through the facade the retarget rewrites call sites to.
 *
 * <p>These are the cheap half of the shim: {@code FrameState} already had a method for every one of them, and what
 * was missing was the entry that brings the call site there. Measured across the 231 jars of the instance, they are
 * 78 of the 156 raw-GL state-setting call sites in it - {@code glBlendFunc} alone is 31.</p>
 *
 * <p>The assertion is not "no exception": a call that reaches real GL instead of the frame state also does not
 * throw in this test, because there is no GL context at all. Each case therefore reads the value back from the
 * frame state, which is where the draw takes it from.</p>
 */
class FrameShimStateSettersTest {

    private static final int GL_SRC_ALPHA = 770;
    private static final int GL_ONE_MINUS_SRC_ALPHA = 771;

    @BeforeEach
    void selectANonGlBackend() {
        NonGlTestBackend.select();
    }

    @AfterEach
    void restoreTheRealGlPath() {
        NonGlTestBackend.restore();
    }

    private static FrameState state() {
        FrameState state = FrameStates.forNonGl();
        assertNotNull(state, "the test selected a non-GL backend");
        return state;
    }

    @Test
    void theBlendFunctionReachesTheFrameState() {
        ShimGl.glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        assertEquals(GL_SRC_ALPHA, state().blendSource());
        assertEquals(GL_ONE_MINUS_SRC_ALPHA, state().blendDestination());
    }

    @Test
    void theScissorBoxReachesTheFrameState() {
        ShimGl.glScissor(11, 22, 33, 44);

        assertArrayEquals(new int[] {11, 22, 33, 44}, state().scissorBox(),
                "x, y, width, height, in that order");
    }

    @Test
    void theMasksReachTheFrameState() {
        ShimGl.glDepthMask(false);
        assertFalse(state().depthWriteEnabled());

        ShimGl.glColorMask(true, false, true, false);
        assertArrayEquals(new boolean[] {true, false, true, false}, state().colorMask());
    }

    @Test
    void theLoadedMatrixReplacesRatherThanAccumulates() {
        state().loadIdentity();
        state().translate(9.0F, 9.0F, 9.0F);

        // A translation of (1, 2, 3), column-major, as a mod would build it.
        ShimGl.glLoadMatrix(translation(1.0F, 2.0F, 3.0F));

        assertEquals(1.0F, state().modelView().m30(), 1e-6F, "the loaded matrix, not a sum with the 9s");
        assertEquals(2.0F, state().modelView().m31(), 1e-6F);
        assertEquals(3.0F, state().modelView().m32(), 1e-6F);
    }

    @Test
    void theMultipliedMatrixComposes() {
        state().loadIdentity();
        state().translate(1.0F, 2.0F, 3.0F);

        ShimGl.glMultMatrix(translation(10.0F, 20.0F, 30.0F));

        assertEquals(11.0F, state().modelView().m30(), 1e-6F);
        assertEquals(22.0F, state().modelView().m31(), 1e-6F);
        assertEquals(33.0F, state().modelView().m32(), 1e-6F);
    }

    @Test
    void theDoubleFormOfMultiplyDoesTheSameThing() {
        state().loadIdentity();
        state().translate(1.0F, 2.0F, 3.0F);

        ShimGl.glMultMatrix(translationDouble(10.0, 20.0, 30.0));

        assertEquals(11.0F, state().modelView().m30(), 1e-6F);
        assertEquals(22.0F, state().modelView().m31(), 1e-6F);
        assertEquals(33.0F, state().modelView().m32(), 1e-6F);
    }

    @Test
    void theLineStippleIsAcceptedAndIgnored() {
        // It reaches the facade rather than real GL, where it would be a call into a context that does not exist.
        // What it must not do is throw, or leave a mark on state the backend does use.
        ShimGl.glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        ShimGl.glLineStipple(4, (short) 0x0F0F);

        assertEquals(GL_SRC_ALPHA, state().blendSource(), "a stipple pattern changes no state the backend keeps");
    }

    private static FloatBuffer translation(float x, float y, float z) {
        FloatBuffer matrix = FloatBuffer.allocate(16);
        matrix.put(0, 1.0F).put(5, 1.0F).put(10, 1.0F).put(15, 1.0F);
        matrix.put(12, x).put(13, y).put(14, z);
        return matrix;
    }

    private static DoubleBuffer translationDouble(double x, double y, double z) {
        DoubleBuffer matrix = DoubleBuffer.allocate(16);
        matrix.put(0, 1.0).put(5, 1.0).put(10, 1.0).put(15, 1.0);
        matrix.put(12, x).put(13, y).put(14, z);
        return matrix;
    }
}
