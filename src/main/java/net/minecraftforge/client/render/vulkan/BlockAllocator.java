package net.minecraftforge.client.render.vulkan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Sub-allocates blocks of memory within a contiguous range {@code [0, capacity]}.
 *
 * <p>Uses a Best-Fit free-list allocator with O(1) coalescing of adjacent free blocks via an
 * address-ordered doubly linked list, and O(log K) Best-Fit lookup via a size-indexed TreeMap.</p>
 *
 * <p>Allocations are aligned to multiples of {@code alignment} bytes (default: 112 bytes, matching
 * one terrain quad of 4 vertices x 28 bytes).</p>
 */
public final class BlockAllocator {

    public static final class Block {
        final int offset;
        int size;
        boolean free;
        Block prev;
        Block next;

        Block(int offset, int size, boolean free) {
            this.offset = offset;
            this.size = size;
            this.free = free;
        }

        public int offset() {
            return offset;
        }

        public int size() {
            return size;
        }

        public boolean isFree() {
            return free;
        }

        @Override
        public String toString() {
            return "Block[offset=" + offset + ", size=" + size + ", free=" + free + "]";
        }
    }

    private final int capacity;
    private final int alignment;
    private int usedBytes;

    private final TreeMap<Integer, List<Block>> freeBlocksBySize = new TreeMap<>();
    private final Block head; // First block in address order

    public BlockAllocator(int capacity, int alignment) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        if (alignment <= 0) {
            throw new IllegalArgumentException("alignment must be positive: " + alignment);
        }
        this.capacity = capacity;
        this.alignment = alignment;
        this.usedBytes = 0;

        this.head = new Block(0, capacity, true);
        addFreeBlock(head);
    }

    public int capacity() {
        return capacity;
    }

    public int usedBytes() {
        return usedBytes;
    }

    public int freeBytes() {
        return capacity - usedBytes;
    }

    public int alignment() {
        return alignment;
    }

    /**
     * Allocates a contiguous block of at least {@code size} bytes.
     *
     * @param size size in bytes
     * @return the allocated block, or {@code null} if no free block is large enough
     */
    public Block allocate(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("allocation size must be positive: " + size);
        }
        int alignedSize = (size + alignment - 1) / alignment * alignment;
        Map.Entry<Integer, List<Block>> entry = freeBlocksBySize.ceilingEntry(alignedSize);
        if (entry == null) {
            return null;
        }

        List<Block> list = entry.getValue();
        Block block = list.remove(list.size() - 1);
        if (list.isEmpty()) {
            freeBlocksBySize.remove(entry.getKey());
        }

        if (block.size > alignedSize) {
            // Split block into used prefix and free remainder
            int remainderSize = block.size - alignedSize;
            Block remainder = new Block(block.offset + alignedSize, remainderSize, true);
            remainder.prev = block;
            remainder.next = block.next;
            if (block.next != null) {
                block.next.prev = remainder;
            }
            block.next = remainder;
            block.size = alignedSize;
            addFreeBlock(remainder);
        }

        block.free = false;
        usedBytes += block.size;
        return block;
    }

    /**
     * Frees a previously allocated block and coalesces it with neighbouring free blocks.
     */
    public void free(Block block) {
        if (block == null) {
            throw new IllegalArgumentException("cannot free null block");
        }
        if (block.free) {
            throw new IllegalStateException("block at offset " + block.offset + " is already free");
        }

        block.free = true;
        usedBytes -= block.size;

        // Coalesce with previous block if free
        if (block.prev != null && block.prev.free) {
            removeFreeBlock(block.prev);
            block.prev.size += block.size;
            block.prev.next = block.next;
            if (block.next != null) {
                block.next.prev = block.prev;
            }
            block = block.prev;
        }

        // Coalesce with next block if free
        if (block.next != null && block.next.free) {
            removeFreeBlock(block.next);
            block.size += block.next.size;
            block.next = block.next.next;
            if (block.next != null) {
                block.next.prev = block;
            }
        }

        addFreeBlock(block);
    }

    private void addFreeBlock(Block block) {
        freeBlocksBySize.computeIfAbsent(block.size, k -> new ArrayList<>(1)).add(block);
    }

    private void removeFreeBlock(Block block) {
        List<Block> list = freeBlocksBySize.get(block.size);
        if (list != null) {
            list.remove(block);
            if (list.isEmpty()) {
                freeBlocksBySize.remove(block.size);
            }
        }
    }
}
