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
        ChunkMeshCapture.markLayerActiveForTest(16, 64, 32, BlockRenderLayer.SOLID);
        ChunkMeshCapture.markLayerActiveForTest(16, 64, 32, BlockRenderLayer.CUTOUT);

        assertTrue(ChunkMeshCapture.isLayerActive(16, 64, 32, BlockRenderLayer.SOLID));
        assertTrue(ChunkMeshCapture.isLayerActive(16, 64, 32, BlockRenderLayer.CUTOUT));

        ChunkMeshCapture.invalidateChunk(16, 64, 32);

        assertFalse(ChunkMeshCapture.isLayerActive(16, 64, 32, BlockRenderLayer.SOLID));
        assertFalse(ChunkMeshCapture.isLayerActive(16, 64, 32, BlockRenderLayer.CUTOUT));
        assertTrue(ChunkMeshCapture.hasPendingEvents());

        List<ChunkMeshCapture.CaptureEvent> events = ChunkMeshCapture.drainEvents();
        assertEquals(2, events.size());
        assertEquals(ChunkMeshCapture.CaptureEvent.Type.REMOVE, events.get(0).type());
        assertEquals(ChunkMeshCapture.CaptureEvent.Type.REMOVE, events.get(1).type());
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
