package net.minecraftforge.client.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recycle pool behind chunk mesh capture.
 *
 * <p>What is being checked is the contract the capture path relies on: an array that comes back is at least as large
 * as asked for (the mesh is written from offset zero and read back by its own computed length), and a released array
 * is handed out again rather than allocated. The second part is the whole point - without it the pool is a cache that
 * never hits, which would look identical from the outside and save nothing.</p>
 */
class ChunkMeshCapturePoolTest {

    @Test
    void releasedArrayIsHandedBackOut() {
        byte[] first = ChunkMeshCapture.acquireBuffer(1000);
        assertEquals(1000, first.length, "a fresh request is exactly the size asked for");

        ChunkMeshCapture.releaseBuffer(first);
        byte[] second = ChunkMeshCapture.acquireBuffer(1000);

        assertSame(first, second, "the same array comes back instead of a new one");
    }

    @Test
    void largerArrayServesASmallerRequest() {
        byte[] large = ChunkMeshCapture.acquireBuffer(4000);
        ChunkMeshCapture.releaseBuffer(large);

        byte[] small = ChunkMeshCapture.acquireBuffer(200);
        assertSame(large, small, "the pool searches upward from the request's bucket");
        assertTrue(small.length >= 200, "the returned array always fits the request");
    }

    @Test
    void arrayIsZeroedOutOfThePoolOnceConsumed() {
        byte[] first = ChunkMeshCapture.acquireBuffer(512);
        ChunkMeshCapture.releaseBuffer(first);
        byte[] second = ChunkMeshCapture.acquireBuffer(512);
        assertSame(first, second);

        // Taken and not released: the next request must not get the same array, or two meshes would share it.
        byte[] third = ChunkMeshCapture.acquireBuffer(512);
        assertNotSame(second, third);
    }

    @Test
    void oversizedRequestStillAllocates() {
        byte[] huge = ChunkMeshCapture.acquireBuffer(64 * 1024 * 1024);
        assertEquals(64 * 1024 * 1024, huge.length, "a request past the buckets is served directly");
        ChunkMeshCapture.releaseBuffer(huge);
    }

    @Test
    void releasingNullIsIgnored() {
        ChunkMeshCapture.releaseBuffer(null);
        assertNotNull(ChunkMeshCapture.acquireBuffer(16), "the pool is unaffected");
    }

    private static void assertEquals(int expected, int actual, String message) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual, message);
    }
}
