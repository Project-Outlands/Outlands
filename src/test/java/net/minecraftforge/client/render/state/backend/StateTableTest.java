package net.minecraftforge.client.render.state.backend;

import net.minecraftforge.client.render.vertex.DrawState;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The enumerant tables at the state end, and the three pieces of state the pipeline was dropping.
 *
 * <p><b>Why these are guards and not trivia.</b> Two crashes came out of the same mistake in one evening: a table
 * listing the values that had been measured and rejecting the rest. GL's enumerant sets are fixed by the spec, so a
 * mod using a value outside a hand-written list is normal, and throwing from inside a render loop takes the process
 * down. The tables are now the spec sets, and the refusal happens where the value is set rather than from inside the
 * pipeline cache.</p>
 */
class StateTableTest {

    private static final int GL_FUNC_SUBTRACT = 32778;
    private static final int GL_LINE = 6913;

    @Test
    void everySpecStencilOperationIsAccepted() {
        FrameStateVk state = new FrameStateVk();
        // GL_KEEP, GL_ZERO, GL_REPLACE, GL_INCR, GL_DECR, GL_INVERT, GL_INCR_WRAP, GL_DECR_WRAP
        int[] all = {7680, 0, 7681, 7682, 7683, 5386, 34055, 34056};
        for (int op : all) {
            state.stencilOp(op, op, op);
            assertEquals(op, state.stencilFail(), "accepted: " + op);
        }
        assertThrows(IllegalArgumentException.class, () -> state.stencilOp(1234, 7680, 7680),
                "an unknown operation is refused where it is set");
    }

    @Test
    void everySpecBlendFactorIsAccepted() {
        FrameStateVk state = new FrameStateVk();
        // ZERO, ONE, the eight source/destination factors, SRC_ALPHA_SATURATE, the four constants, the four SRC1s.
        // 777 is not one of them: the list used to run 768..777 as if the eight source/destination factors plus
        // SRC_ALPHA_SATURATE filled that range, but the range is 768..776 and 777 is nothing at all.
        int[] all = {0, 1, 768, 769, 770, 771, 772, 773, 774, 775, 776,
                32769, 32770, 32771, 32772, 34185, 35065, 35066, 35067};
        for (int factor : all) {
            state.blendFunc(factor, factor);
            assertEquals(factor, state.blendSource(), "accepted: " + factor);
        }
        assertThrows(IllegalArgumentException.class, () -> state.blendFunc(1234, 1));
        assertThrows(IllegalArgumentException.class, () -> state.blendFunc(777, 1));
    }

    @Test
    void everySpecComparisonIsAccepted() {
        FrameStateVk state = new FrameStateVk();
        for (int func = 512; func <= 519; func++) {
            state.depthFunc(func);
            assertEquals(func, state.depthFunc());
            state.alphaFunc(func, 0.5F);
            assertEquals(func, state.alphaComparison());
        }
        assertThrows(IllegalArgumentException.class, () -> state.depthFunc(511));
    }

    @Test
    void theThreeDroppedPiecesOfStateReachTheDraw() {
        FrameStateVk state = new FrameStateVk();
        state.blendEquation(GL_FUNC_SUBTRACT);
        state.logicOp(5382);                 // GL_XOR
        state.polygonMode(1032, GL_LINE);    // GL_FRONT_AND_BACK

        DrawState draw = new DrawState();
        draw.captureFrom(state);

        assertEquals(GL_FUNC_SUBTRACT, draw.blendEquation(),
                "the pipeline used to hard-code ADD, so a subtractive blend was silently additive");
        assertEquals(5382, draw.logicOp());
        assertEquals(GL_LINE, draw.polygonModeMode(),
                "glPolygonMode was recorded and then dropped; the pipeline always said FILL");
    }
}
