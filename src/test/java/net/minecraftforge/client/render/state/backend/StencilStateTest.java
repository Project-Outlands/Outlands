package net.minecraftforge.client.render.state.backend;

import net.minecraftforge.client.render.vertex.DrawState;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stencil state a draw carries: GL's initial values, and what a mod's calls leave behind.
 *
 * <p>The values travel with the draw because the pipeline key is built from them - a frame that records "enable,
 * draw, disable, draw" must not give both draws the same pipeline.</p>
 */
class StencilStateTest {

    @Test
    void theStateStartsAtGlsInitialValues() {
        FrameStateVk state = new FrameStateVk();
        assertEquals(519, state.stencilComparison(), "GL_ALWAYS");
        assertEquals(0, state.stencilReference());
        assertEquals(0xFFFFFFFF, state.stencilCompareMask(), "all bits");
        assertEquals(0xFFFFFFFF, state.stencilWriteMask(), "all bits");
        assertEquals(7680, state.stencilFail(), "GL_KEEP");
        assertEquals(7680, state.stencilDepthFail(), "GL_KEEP");
        assertEquals(7680, state.stencilPass(), "GL_KEEP");
    }

    @Test
    void theBottlingMachinesSequenceTravelsWithTheDraw() {
        FrameStateVk state = new FrameStateVk();
        // What Immersive Engineering does, in order: write 1 everywhere the fluid quad covers, then test against 0
        // and against 1 to draw the two halves of the item.
        state.capability(Capability.STENCIL_TEST, true);
        state.stencilFunc(512, 1, 255);       // GL_ALWAYS
        state.stencilOp(7681, 7680, 7680);    // GL_REPLACE on pass, GL_KEEP otherwise
        state.stencilMask(255);
        state.stencilFunc(514, 0, 255);       // GL_EQUAL 0
        state.stencilMask(0);

        DrawState draw = new DrawState();
        draw.captureFrom(state);
        assertTrue(draw.stencilTest(), "the capability is snapshotted with the draw");
        assertEquals(514, draw.stencilComparison());
        assertEquals(0, draw.stencilReference());
        assertEquals(255, draw.stencilCompareMask());
        assertEquals(0, draw.stencilWriteMask());
        assertEquals(7681, draw.stencilFail());
        assertEquals(7680, draw.stencilPass());

        state.capability(Capability.STENCIL_TEST, false);
        DrawState plain = new DrawState();
        plain.captureFrom(state);
        assertFalse(plain.stencilTest(), "disabled again: the same state, but the draw does not stencil");
    }

    @Test
    void theCapabilityIsTheOneAModUses() {
        assertEquals(Capability.STENCIL_TEST, Capability.tryOf(2960), "GL_STENCIL_TEST");
    }
}
