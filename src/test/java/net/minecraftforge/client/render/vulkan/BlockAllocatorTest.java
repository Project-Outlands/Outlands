package net.minecraftforge.client.render.vulkan;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockAllocatorTest {

    private static final int ALIGNMENT = 112; // 1 quad = 4 vertices * 28 bytes

    @Test
    void initialCapacityIsAllFree() {
        BlockAllocator allocator = new BlockAllocator(1024, ALIGNMENT);
        assertEquals(1024, allocator.capacity());
        assertEquals(0, allocator.usedBytes());
        assertEquals(1024, allocator.freeBytes());
    }

    @Test
    void exactAllocationUsesEntireCapacity() {
        BlockAllocator allocator = new BlockAllocator(ALIGNMENT * 4, ALIGNMENT);
        BlockAllocator.Block block = allocator.allocate(ALIGNMENT * 4);
        assertNotNull(block);
        assertEquals(0, block.offset());
        assertEquals(ALIGNMENT * 4, block.size());
        assertFalse(block.isFree());
        assertEquals(ALIGNMENT * 4, allocator.usedBytes());
        assertEquals(0, allocator.freeBytes());

        // Further allocation must fail
        assertNull(allocator.allocate(ALIGNMENT));

        // Freeing restores full capacity
        allocator.free(block);
        assertEquals(0, allocator.usedBytes());
        assertEquals(ALIGNMENT * 4, allocator.freeBytes());
    }

    @Test
    void allocationAlignsUpwards() {
        BlockAllocator allocator = new BlockAllocator(1024, ALIGNMENT);
        BlockAllocator.Block block1 = allocator.allocate(1); // 1 byte -> aligned to 112
        assertNotNull(block1);
        assertEquals(0, block1.offset());
        assertEquals(ALIGNMENT, block1.size());

        BlockAllocator.Block block2 = allocator.allocate(ALIGNMENT + 5); // 117 bytes -> aligned to 224
        assertNotNull(block2);
        assertEquals(ALIGNMENT, block2.offset());
        assertEquals(ALIGNMENT * 2, block2.size());

        assertEquals(ALIGNMENT * 3, allocator.usedBytes());
    }

    @Test
    void outOfMemoryReturnsNull() {
        BlockAllocator allocator = new BlockAllocator(224, ALIGNMENT);
        assertNull(allocator.allocate(225));
        assertNotNull(allocator.allocate(224));
        assertNull(allocator.allocate(1));
    }

    @Test
    void doubleFreeThrowsException() {
        BlockAllocator allocator = new BlockAllocator(1024, ALIGNMENT);
        BlockAllocator.Block block = allocator.allocate(112);
        allocator.free(block);
        assertThrows(IllegalStateException.class, () -> allocator.free(block));
    }

    @Test
    void bidirectionalCoalescing() {
        BlockAllocator allocator = new BlockAllocator(ALIGNMENT * 10, ALIGNMENT);
        BlockAllocator.Block b0 = allocator.allocate(ALIGNMENT);
        BlockAllocator.Block b1 = allocator.allocate(ALIGNMENT);
        BlockAllocator.Block b2 = allocator.allocate(ALIGNMENT);
        BlockAllocator.Block b3 = allocator.allocate(ALIGNMENT);

        assertEquals(ALIGNMENT * 4, allocator.usedBytes());

        // Free b1 -> leaves a free hole at ALIGNMENT
        allocator.free(b1);
        assertEquals(ALIGNMENT * 3, allocator.usedBytes());

        // Free b2 -> should coalesce with b1 to form a free hole of size 2*ALIGNMENT
        allocator.free(b2);
        assertEquals(ALIGNMENT * 2, allocator.usedBytes());

        // Free b0 -> should coalesce with b1+b2 to form a free hole of size 3*ALIGNMENT at 0
        allocator.free(b0);
        assertEquals(ALIGNMENT, allocator.usedBytes());

        // Free b3 -> should coalesce with b0+b1+b2 and the remaining tail!
        allocator.free(b3);
        assertEquals(0, allocator.usedBytes());
        assertEquals(ALIGNMENT * 10, allocator.freeBytes());

        // Should be able to allocate full capacity in one block again
        BlockAllocator.Block big = allocator.allocate(ALIGNMENT * 10);
        assertNotNull(big);
        assertEquals(0, big.offset());
        assertEquals(ALIGNMENT * 10, big.size());
    }

    @Test
    void stressRandomAllocationAndFree() {
        int capacity = ALIGNMENT * 1000;
        BlockAllocator allocator = new BlockAllocator(capacity, ALIGNMENT);
        Random rng = new Random(42);

        List<BlockAllocator.Block> active = new ArrayList<>();
        for (int step = 0; step < 5000; step++) {
            if (active.isEmpty() || (active.size() < 100 && rng.nextBoolean())) {
                int quads = rng.nextInt(1, 20);
                BlockAllocator.Block block = allocator.allocate(quads * ALIGNMENT);
                if (block != null) {
                    active.add(block);
                }
            } else {
                int index = rng.nextInt(active.size());
                BlockAllocator.Block block = active.remove(index);
                allocator.free(block);
            }
        }

        // Free all remaining active blocks
        for (BlockAllocator.Block block : active) {
            allocator.free(block);
        }

        assertEquals(0, allocator.usedBytes());
        assertEquals(capacity, allocator.freeBytes());

        // Allocating full capacity must succeed with 0 fragmentation!
        BlockAllocator.Block full = allocator.allocate(capacity);
        assertNotNull(full);
        assertEquals(0, full.offset());
        assertEquals(capacity, full.size());
    }
}
