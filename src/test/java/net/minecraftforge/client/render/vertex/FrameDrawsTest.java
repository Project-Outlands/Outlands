package net.minecraftforge.client.render.vertex;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameDrawsTest {

    @BeforeEach
    @AfterEach
    void reset() {
        FrameDraws.drain();
    }

    @Test
    void recordClearOnlyCollectsDuringFrame() {
        float[] color = {0.1F, 0.2F, 0.3F, 1.0F};
        int[] scissor = {0, 0, 100, 100};

        assertFalse(FrameDraws.isCollecting());
        assertFalse(FrameDraws.recordClear(0x100, color, 1.0, false, scissor),
                "recordClear returns false when not collecting");

        FrameDraws.beginCollecting();
        assertTrue(FrameDraws.isCollecting());
        assertTrue(FrameDraws.recordClear(0x100, color, 1.0, true, scissor));

        List<FrameDraws.Operation> ops = FrameDraws.drain();
        assertEquals(1, ops.size());
        assertInstanceOf(FrameDraws.Clear.class, ops.get(0));

        FrameDraws.Clear clear = (FrameDraws.Clear) ops.get(0);
        assertEquals(0x100, clear.mask());
        assertEquals(0.1F, clear.color()[0], 0.001F);
        assertEquals(1.0, clear.depth(), 0.001);
        assertTrue(clear.scissorEnabled());
        assertEquals(100, clear.scissorBox()[2]);

        // After drain, pending operations list is empty
        assertEquals(0, FrameDraws.pending());
    }

    @Test
    void clearRecordValidation() {
        float[] badColor = {1.0F, 1.0F};
        int[] scissor = {0, 0, 10, 10};
        assertThrows(IllegalArgumentException.class,
                () -> new FrameDraws.Clear(0x100, badColor, 1.0, false, scissor));

        float[] goodColor = {1.0F, 1.0F, 1.0F, 1.0F};
        int[] badScissor = {0, 0};
        assertThrows(IllegalArgumentException.class,
                () -> new FrameDraws.Clear(0x100, goodColor, 1.0, false, badScissor));
    }

    @Test
    void pooledDrawInstancesAreReusedAcrossFrames() {
        VertexLayout layout = VertexLayout.of(net.minecraft.client.renderer.vertex.DefaultVertexFormats.POSITION_TEX_COLOR);
        byte[] dummyData = new byte[layout.stride() * 4];
        DrawState state = new DrawState();

        // Frame 1
        FrameDraws.beginCollecting();
        FrameDraws.record(layout, 7, 4, dummyData, state);
        List<FrameDraws.Operation> ops1 = FrameDraws.drain();
        assertEquals(1, ops1.size());
        FrameDraws.Draw draw1 = (FrameDraws.Draw) ops1.get(0);

        // Frame 2
        FrameDraws.beginCollecting();
        FrameDraws.record(layout, 7, 4, dummyData, state);
        List<FrameDraws.Operation> ops2 = FrameDraws.drain();
        assertEquals(1, ops2.size());
        FrameDraws.Draw draw2 = (FrameDraws.Draw) ops2.get(0);

        org.junit.jupiter.api.Assertions.assertSame(draw1, draw2, "Draw instance should be reused from pool across frames");
    }
}
