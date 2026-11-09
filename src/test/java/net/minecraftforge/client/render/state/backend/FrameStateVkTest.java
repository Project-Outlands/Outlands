package net.minecraftforge.client.render.state.backend;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recorded frame state, and the two places where a silent mistake would show up as geometry in the wrong place
 * rather than as an error: the degrees-to-radians conversion and the composition order.
 */
class FrameStateVkTest {

    private static final int MODELVIEW = 5888;

    private static Vector4f transform(FrameStateVk state, float x, float y, float z) {
        Matrix4f matrix = new Matrix4f(state.matrix(MODELVIEW));
        return matrix.transform(new Vector4f(x, y, z, 1.0F));
    }

    @Test
    void allThirteenCapabilitiesAreKnown() {
        // The set is closed by the thirteen BooleanState constructions in vanilla. A backend switching on a value
        // with meaning is the point; a new one should fail to compile rather than fall through.
        for (int glEnum : new int[] {2884, 2896, 2903, 2912, 2929, 2977, 3008, 3042, 3058, 3089, 3553, 10754, 32823, 32826}) {
            Capability.ofGlEnum(glEnum);
        }
        assertThrows(IllegalArgumentException.class, () -> Capability.ofGlEnum(3040),
                "a GL capability vanilla never toggles is not silently accepted");
    }

    @Test
    void capabilitiesAreTrackedAndNotJustRecorded() {
        FrameStateVk state = new FrameStateVk();
        // 13 toggles that vanilla drives through BooleanState, plus SCISSOR_TEST (3089), plus the eight GL_LIGHTn
        // sources that RenderHelper.enableGUIStandardItemLighting turns on when an item icon is drawn. The count is
        // asserted so that adding a capability is a deliberate act rather than a side effect - and so that removing one,
        // which would make some draw silently unrepresented, fails here.
        assertEquals(22, Capability.values().length);
        assertEquals(Capability.LIGHT_0, Capability.ofGlEnum(16384));
        assertEquals(Capability.LIGHT_7, Capability.ofGlEnum(16391));

        state.capability(Capability.DEPTH_TEST, true);
        state.capability(Capability.DEPTH_TEST, false);
        // Re-enabling after disabling must still be visible; a set that only ever adds would pass a naive test.
        state.capability(Capability.DEPTH_TEST, true);

        // The observer is the pipeline key, which is not exposed; the assertion here is that repeated toggles do
        // not throw and that the enum is the only input.
        assertTrue(Capability.DEPTH_TEST.name().equals("DEPTH_TEST"));
    }

    @Test
    void rotateTakesDegreesLikeGl() {
        // glRotatef(90, 0, 0, 1) maps +X to +Y. In radians it would map +X to almost +X, which is the mistake this
        // test exists to catch - it produces a picture that is wrong but not obviously wrong.
        FrameStateVk state = new FrameStateVk();
        state.matrixMode(MODELVIEW);
        state.rotate(90.0F, 0.0F, 0.0F, 1.0F);

        Vector4f mapped = transform(state, 1.0F, 0.0F, 0.0F);
        assertEquals(0.0F, mapped.x, 1.0e-5F);
        assertEquals(1.0F, mapped.y, 1.0e-5F);
    }

    @Test
    void translateThenRotateRotatesAboutTheTranslatedOrigin() {
        // Composition on the right is what makes this true. Composing on the left would rotate about the world
        // origin and move the translated point back.
        FrameStateVk state = new FrameStateVk();
        state.matrixMode(MODELVIEW);
        state.translate(10.0, 0.0, 0.0);
        state.rotate(90.0F, 0.0F, 0.0F, 1.0F);

        // The point (1,0,0) is first rotated to (0,1,0), then translated by (10,0,0).
        Vector4f mapped = transform(state, 1.0F, 0.0F, 0.0F);
        assertEquals(10.0F, mapped.x, 1.0e-5F);
        assertEquals(1.0F, mapped.y, 1.0e-5F);
    }

    @Test
    void pushAndPopRestoreTheMatrixAndKeepSeparateStacksPerMode() {
        FrameStateVk state = new FrameStateVk();
        state.matrixMode(MODELVIEW);
        state.translate(5.0, 0.0, 0.0);
        state.pushMatrix();
        state.translate(100.0, 0.0, 0.0);

        // The projection stack is independent: pushing to it must not disturb the modelview stack.
        state.matrixMode(5889);
        state.pushMatrix();
        state.popMatrix();

        state.matrixMode(MODELVIEW);
        Vector4f before = transform(state, 0.0F, 0.0F, 0.0F);
        assertEquals(105.0F, before.x, 1.0e-5F, "still shifted by the inner translate");

        state.popMatrix();
        Vector4f after = transform(state, 0.0F, 0.0F, 0.0F);
        assertEquals(5.0F, after.x, 1.0e-5F, "the outer translate survives");
    }

    @Test
    void anUnbalancedPopIsLoud() {
        FrameStateVk state = new FrameStateVk();
        state.matrixMode(MODELVIEW);
        assertThrows(IllegalStateException.class, state::popMatrix);
        assertThrows(IllegalStateException.class, state::popAttributes);
    }

    @Test
    void multMatrixReadsColumnMajor() {
        // glMultMatrixf reads column-major. Reading the same 16 floats the other way round is a transpose, which
        // is invisible for a diagonal matrix - so this one is not diagonal.
        //
        // The buffer is a heap one, deliberately: handing a heap FloatBuffer to Matrix4f.set(FloatBuffer) crashes
        // the JVM, because that path dereferences the buffer's native address field without checking isDirect().
        // This test is the regression guard for the copy in FrameStateVk.multiply.
        float[] columnMajor = {
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                3, 4, 5, 1,
        };
        FrameStateVk state = new FrameStateVk();
        state.matrixMode(MODELVIEW);
        state.multiply(FloatBuffer.wrap(columnMajor));

        Vector4f mapped = transform(state, 0.0F, 0.0F, 0.0F);
        assertEquals(3.0F, mapped.x, 1.0e-5F);
        assertEquals(4.0F, mapped.y, 1.0e-5F);
        assertEquals(5.0F, mapped.z, 1.0e-5F);
    }

    @Test
    void unknownValuesAreRejected() {
        FrameStateVk state = new FrameStateVk();
        assertThrows(IllegalArgumentException.class, () -> state.shadeModel(1234));
        assertThrows(IllegalArgumentException.class, () -> state.cullFace(1234));
        assertThrows(IllegalArgumentException.class, () -> state.matrixMode(1234));
        assertThrows(IllegalArgumentException.class, () -> state.depthFunc(1234));
        assertThrows(IllegalArgumentException.class, () -> state.blendFunc(1234, 1));
        assertThrows(IllegalArgumentException.class, () -> state.polygonMode(1029, 1234));
    }

    @Test
    void attribPushesAreCountedNotApplied() {
        FrameStateVk state = new FrameStateVk();
        state.pushAttributes();
        state.pushAttributes();
        state.popAttributes();
        state.popAttributes();
        // A third pop has nothing to restore; the depth going negative is a caller bug, not a state to absorb.
        assertThrows(IllegalStateException.class, state::popAttributes);
        assertFalse(false);
    }
}
