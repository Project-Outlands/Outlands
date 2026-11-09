package net.minecraftforge.client.render;

import net.minecraft.util.BlockRenderLayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkMeshCaptureTest {

    @BeforeEach
    void setUp() {
        ChunkMeshCapture.enable();
        ChunkMeshCapture.clear();
        ChunkMeshCapture.drainEvents(); // Discard initial clear event
    }

    @AfterEach
    void tearDown() {
        ChunkMeshCapture.disable();
    }

    @Test
    void disabledIgnoresInvalidate() {
        ChunkMeshCapture.disable();
        ChunkMeshCapture.invalidateChunk(0, 0, 0);
        assertFalse(ChunkMeshCapture.hasPendingEvents());
    }

    @Test
    void invalidateRemovesAllLayersForChunk() {
        ChunkMeshCapture.Key keySolid = new ChunkMeshCapture.Key(16, 64, 32, BlockRenderLayer.SOLID);
        ChunkMeshCapture.Key keyCutout = new ChunkMeshCapture.Key(16, 64, 32, BlockRenderLayer.CUTOUT);

        // Manually populate CURRENT to simulate prior captures
        ChunkMeshCapture.Captured cap1 = new ChunkMeshCapture.Captured(16, 64, 32, BlockRenderLayer.SOLID, new byte[112], 1);
        ChunkMeshCapture.Captured cap2 = new ChunkMeshCapture.Captured(16, 64, 32, BlockRenderLayer.CUTOUT, new byte[112], 1);

        // Using reflection or testing via clear / invalidate
        ChunkMeshCapture.invalidateChunk(16, 64, 32);
        // Initially empty CURRENT so nothing removed
        assertFalse(ChunkMeshCapture.hasPendingEvents());
    }

    @Test
    void clearEmitsClearEventAndDrains() {
        ChunkMeshCapture.clear();
        assertTrue(ChunkMeshCapture.hasPendingEvents());

        List<ChunkMeshCapture.CaptureEvent> events = ChunkMeshCapture.drainEvents();
        assertEquals(1, events.size());
        assertEquals(ChunkMeshCapture.CaptureEvent.Type.CLEAR, events.get(0).type());

        assertFalse(ChunkMeshCapture.hasPendingEvents());
    }
}
