package net.minecraftforge.client.render.state.backend;

import net.minecraftforge.client.render.vertex.DrawState;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The line width travels with the draw, like every other piece of state the pipeline key is built from.
 *
 * <p>A frame that records "thick line, draw, thin line, draw" would give both draws the second width if this were
 * read at replay time, and the thick line would silently become one pixel.</p>
 */
class LineWidthStateTest {

    @Test
    void theWidthTravelsWithTheDraw() {
        FrameStateVk state = new FrameStateVk();
        assertEquals(1.0F, state.lineWidth(), "GL's initial value");

        // What Immersive Engineering's Tesla coil does around its arcs.
        state.lineWidth(4.0F);
        DrawState thick = new DrawState();
        thick.captureFrom(state);
        assertEquals(4.0F, thick.lineWidth());

        state.lineWidth(1.0F);
        DrawState thin = new DrawState();
        thin.captureFrom(state);
        assertEquals(1.0F, thin.lineWidth(), "a snapshot, not a reference: the next draw is thin again");
        assertEquals(4.0F, thick.lineWidth(), "and the earlier draw keeps what it was issued with");
    }
}
