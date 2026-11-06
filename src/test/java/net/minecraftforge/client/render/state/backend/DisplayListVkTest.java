package net.minecraftforge.client.render.state.backend;

import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraftforge.client.render.vertex.VertexLayout;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Recording a display list without drawing it.
 *
 * <p>This is the property that lets the sky and cloud lists be built during startup: recording must not need a
 * frame. The replay is the part that does, and it says so.</p>
 */
class DisplayListVkTest {

    private static final int GL_COMPILE = 4864;
    private static final int GL_QUADS = 7;

    private static ByteBuffer vertices(int quadCount) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(quadCount * 4 * DefaultVertexFormats.BLOCK.getSize());
        for (int i = 0; i < buffer.capacity(); i++) {
            buffer.put((byte) i);
        }
        buffer.position(0);
        return buffer;
    }

    @Test
    void recordingKeepsTheDrawsAndDoesNotNeedAFrame() {
        DisplayListVk lists = new DisplayListVk();
        int id = lists.generate(1);

        lists.begin(id, GL_COMPILE);
        assertTrue(lists.capture(VertexLayout.of(DefaultVertexFormats.BLOCK), GL_QUADS, 8, vertices(2)),
                "a draw while recording is consumed");
        lists.end();

        assertEquals(1, lists.drawCount(id));
        assertNull(lists.recording(), "end() closes the recording");
    }

    @Test
    void replaySaysWhatIsMissingInsteadOfDrawingNothing() {
        DisplayListVk lists = new DisplayListVk();
        int id = lists.generate(1);
        lists.begin(id, GL_COMPILE);
        lists.capture(VertexLayout.of(DefaultVertexFormats.BLOCK), GL_QUADS, 4, vertices(1));
        lists.capture(VertexLayout.of(DefaultVertexFormats.BLOCK), GL_QUADS, 4, vertices(1));
        lists.end();

        UnsupportedOperationException thrown =
                assertThrows(UnsupportedOperationException.class, () -> lists.call(id));
        // The message has to say the recording worked, or the next person assumes the list is empty and looks in
        // the wrong place.
        assertTrue(thrown.getMessage().contains("2 draws"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("8 vertices"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("frame"), thrown.getMessage());
    }

    @Test
    void aRangeOfListsGetsConsecutiveNames() {
        DisplayListVk lists = new DisplayListVk();
        int first = lists.generate(3);
        int next = lists.generate(1);

        assertEquals(first + 3, next, "names are handed out without reuse");
        for (int i = 0; i < 3; i++) {
            assertEquals(0, lists.drawCount(first + i));
        }
    }

    @Test
    void nestingAndUnbalancedCallsAreLoud() {
        DisplayListVk lists = new DisplayListVk();
        int id = lists.generate(1);

        assertThrows(IllegalStateException.class, lists::end, "end without begin");
        lists.begin(id, GL_COMPILE);
        assertThrows(IllegalStateException.class, () -> lists.begin(id, GL_COMPILE), "GL forbids nesting");
        lists.end();
        assertThrows(IllegalStateException.class, () -> lists.call(id + 99), "calling a list never generated");
    }

    @Test
    void captureOutsideRecordingIsDeclined() {
        DisplayListVk lists = new DisplayListVk();
        // Returning false rather than throwing is what lets the stream backend fall through to its normal path.
        assertTrue(!lists.capture(VertexLayout.of(DefaultVertexFormats.BLOCK), GL_QUADS, 4, vertices(1)));
    }

    @Test
    void deletingRemovesTheList() {
        DisplayListVk lists = new DisplayListVk();
        int id = lists.generate(2);
        lists.begin(id, GL_COMPILE);
        lists.capture(VertexLayout.of(DefaultVertexFormats.BLOCK), GL_QUADS, 4, vertices(1));
        lists.end();

        lists.delete(id, 2);
        assertEquals(0, lists.drawCount(id));
        assertThrows(IllegalStateException.class, () -> lists.call(id));
    }
}
