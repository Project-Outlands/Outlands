package net.minecraftforge.client.render;

import net.minecraftforge.client.render.shim.ShimGl;
import net.minecraftforge.client.render.state.backend.Capability;
import net.minecraftforge.client.render.state.backend.FrameState;
import net.minecraftforge.client.render.state.backend.FrameStates;
import net.minecraftforge.client.render.vertex.DrawState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code glPushAttrib}/{@code glPopAttrib}: everything the block changed has to come back.
 *
 * <p>The state this guards was a depth counter. A mod that wrapped a block in push/pop got no restore at all, and
 * its blend, depth mask and matrix leaked into whatever drew next - which looks like a bug in that next thing. Every
 * group is mutated inside the block and checked afterwards, because the failure mode of a forgotten field is silence:
 * the field keeps the mod's value and nothing reports it.</p>
 */
class AttributeStackTest {

    private static final int GL_ALL_ATTRIB_BITS = 0xFFFFF;
    private static final int GL_BLEND = 3042;
    private static final int GL_SRC_ALPHA = 770;
    private static final int GL_ONE_MINUS_SRC_ALPHA = 771;
    private static final int GL_LEQUAL = 515;
    private static final int GL_FRONT = 1028;

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
    void everyGroupIsRestored() {
        FrameState state = state();
        // A known starting point, then a draw captured from it: that snapshot is what has to come back.
        state.capability(Capability.BLEND, false);
        state.capability(Capability.DEPTH_TEST, true);
        state.blendFunc(1, 0);
        state.depthFunc(GL_LEQUAL);
        state.depthMask(true);
        state.colorMask(true, true, true, true);
        state.cullFace(GL_FRONT);
        state.lineWidth(1.0F);
        state.pointSize(1.0F);
        state.color(1.0F, 1.0F, 1.0F, 1.0F);
        state.viewport(0, 0, 100, 100);
        state.scissor(0, 0, 100, 100);
        state.alphaFunc(519, 0.0F);
        state.clearColor(0.0F, 0.0F, 0.0F, 0.0F);
        state.clearDepth(1.0);
        state.clearStencil(0);
        state.loadIdentity();
        state.translate(1.0F, 2.0F, 3.0F);
        DrawState before = new DrawState();
        before.captureFrom(state);

        ShimGl.glPushAttrib(GL_ALL_ATTRIB_BITS);

        // Everything the block is allowed to change.
        state.capability(Capability.BLEND, true);
        state.capability(Capability.DEPTH_TEST, false);
        state.blendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        state.depthFunc(513);
        state.depthMask(false);
        state.colorMask(false, false, false, false);
        state.cullFace(1029);
        state.lineWidth(4.0F);
        state.pointSize(8.0F);
        state.color(0.0F, 0.0F, 0.0F, 1.0F);
        state.viewport(5, 6, 7, 8);
        state.scissor(9, 10, 11, 12);
        state.alphaFunc(516, 0.5F);
        state.clearColor(1.0F, 1.0F, 1.0F, 1.0F);
        state.clearDepth(0.5);
        state.clearStencil(9);
        state.loadIdentity();
        state.translate(9.0F, 9.0F, 9.0F);

        ShimGl.glPopAttrib();

        DrawState after = new DrawState();
        after.captureFrom(state);

        assertFalse(state.isEnabled(Capability.BLEND), "an enable bit comes back");
        assertTrue(state.isEnabled(Capability.DEPTH_TEST));
        assertEquals(1, state.blendSource(), "the blend function comes back");
        assertEquals(0, state.blendDestination());
        assertEquals(GL_LEQUAL, state.depthFunc());
        assertTrue(state.depthWriteEnabled(), "the depth mask comes back");
        assertArrayEquals(new boolean[] {true, true, true, true}, state.colorMask());
        assertEquals(GL_FRONT, state.cullFace());
        assertEquals(1.0F, state.lineWidth());
        assertEquals(1.0F, state.pointSize());
        assertArrayEquals(new float[] {1.0F, 1.0F, 1.0F, 1.0F}, state.currentColor());
        assertArrayEquals(new int[] {0, 0, 100, 100}, state.viewport());
        assertArrayEquals(new int[] {0, 0, 100, 100}, state.scissorBox());
        assertEquals(1.0F, state.modelView().m30(), 1e-6F, "the matrix stack is restored with the rest");
        assertEquals(2.0F, state.modelView().m31(), 1e-6F);

        // And the same through the object a draw actually carries, which is the thing that would have been wrong.
        assertEquals(before.blendSourceRgb(), after.blendSourceRgb());
        assertEquals(before.blendSourceAlpha(), after.blendSourceAlpha());
        assertEquals(before.lineWidth(), after.lineWidth());
        assertEquals(before.pointSize(), after.pointSize());
        assertEquals(before.depthFunc(), after.depthFunc());
        assertArrayEquals(before.color(), after.color());
    }

    @Test
    void theMatrixStackDepthComesBackToo() {
        FrameState state = state();
        state.loadIdentity();
        int outside = state.matrixStackDepth();

        ShimGl.glPushAttrib(GL_ALL_ATTRIB_BITS);
        // The block saves the matrix stack by pushing it, which is one deeper on its own.
        int inside = state.matrixStackDepth();
        assertEquals(outside + 1, inside);

        state.pushMatrix();
        state.translate(5.0F, 5.0F, 5.0F);
        assertEquals(inside + 1, state.matrixStackDepth());

        ShimGl.glPopAttrib();
        assertEquals(outside, state.matrixStackDepth(), "a matrix pushed inside the block is gone afterwards");
    }

    @Test
    void nestedBlocksUnwindInOrder() {
        FrameState state = state();
        state.lineWidth(1.0F);

        ShimGl.glPushAttrib(GL_ALL_ATTRIB_BITS);
        state.lineWidth(2.0F);
        ShimGl.glPushAttrib(GL_ALL_ATTRIB_BITS);
        state.lineWidth(3.0F);

        ShimGl.glPopAttrib();
        assertEquals(2.0F, state.lineWidth(), "the inner pop restores the outer block's value");
        ShimGl.glPopAttrib();
        assertEquals(1.0F, state.lineWidth(), "and the outer pop the state before both");
    }

    @Test
    void aPopWithoutAPushIsLoud() {
        assertThrows(IllegalStateException.class, ShimGl::glPopAttrib);
    }

    @Test
    void aNarrowMaskIsReportedButStillRestores() {
        FrameState state = state();
        state.lineWidth(1.0F);

        ShimGl.glPushAttrib(0x4);   // GL_LINE_BIT only
        state.lineWidth(9.0F);
        ShimGl.glPopAttrib();

        assertEquals(1.0F, state.lineWidth(), "the line width comes back either way");
    }

    @Test
    void theStencilClearValueTravelsWithTheClear() {
        FrameState state = state();
        ShimGl.glClearStencil(9);
        state.clearStencil(0);
        ShimGl.glClearStencil(9);

        net.minecraftforge.client.render.vertex.FrameDraws.beginCollecting();
        state.clear(0x400);
        var ops = net.minecraftforge.client.render.vertex.FrameDraws.drain();

        assertEquals(1, ops.size());
        var clear = (net.minecraftforge.client.render.vertex.FrameDraws.Clear) ops.get(0);
        assertEquals(9, clear.stencil(), "glClearStencil's value reaches the clear record");
    }

    @Test
    void aReadbackIsAcceptedAndLeavesTheBufferAlone() {
        // It cannot be answered yet, but it must not reach real GL: a Vulkan window has no GL context, and the call
        // would fail inside the driver rather than in a log line naming what is missing.
        FloatBuffer pixels = FloatBuffer.allocate(16);
        ShimGl.glReadPixels(0, 0, 2, 2, 6408, 5126, pixels);

        assertEquals(0.0F, pixels.get(0), "the destination is left as it was");
    }
}
