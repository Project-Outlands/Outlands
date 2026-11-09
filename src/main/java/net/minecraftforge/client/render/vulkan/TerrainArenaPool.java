package net.minecraftforge.client.render.vulkan;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDevice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;

/**
 * Manages GPU memory for chunk meshes via arena slabs and sub-allocation.
 *
 * <p><b>Static Shared Index Buffer:</b> Minecraft terrain is composed entirely of quads. Every quad has identical
 * index topology relative to its base vertex: (0, 1, 2, 0, 2, 3) + 4*k. A single pre-baked static index buffer
 * is allocated once and shared across all chunks and all frames.</p>
 *
 * <p><b>Multi-Slab Arena:</b> Vertices are sub-allocated from 128MB slabs. When a slab is full, a new slab is
 * allocated without copying or moving existing chunks.</p>
 *
 * <p><b>Deferred Retirement:</b> When chunk meshes are updated or removed, their blocks are retired to the current
 * frame slot and only freed back to the sub-allocator after that slot's fence has signaled on the GPU.</p>
 */
public final class TerrainArenaPool implements AutoCloseable {

    public static final int DEFAULT_SLAB_CAPACITY = 128 * 1024 * 1024; // 128 MB per slab
    public static final int QUAD_ALIGNMENT = TerrainVertexFormat.VERTICES_PER_QUAD * TerrainVertexFormat.STRIDE; // 112 bytes
    public static final int MAX_STATIC_QUADS = 131_072; // 131,072 quads (524,288 vertices), supports any chunk layer

    public record Allocation(int slabIndex, BlockAllocator.Block block) {
        public int offset() {
            return block.offset();
        }

        public int size() {
            return block.size();
        }
    }

    public static final class Slab implements AutoCloseable {
        private final int index;
        private final VulkanBuffer buffer;
        private final BlockAllocator allocator;

        Slab(int index, VulkanBuffer buffer, BlockAllocator allocator) {
            this.index = index;
            this.buffer = buffer;
            this.allocator = allocator;
        }

        public int index() {
            return index;
        }

        public VulkanBuffer buffer() {
            return buffer;
        }

        public BlockAllocator allocator() {
            return allocator;
        }

        @Override
        public void close() {
            buffer.close();
        }
    }

    private final VkDevice device;
    private final VulkanContext context;
    private final int slabCapacity;
    private final VulkanBuffer indexBuffer;
    private final List<Slab> slabs = new ArrayList<>();
    private final List<Allocation>[] retiringBlocks;

    public TerrainArenaPool(VkDevice device, VulkanContext context, int framesInFlight) {
        this(device, context, framesInFlight, DEFAULT_SLAB_CAPACITY);
    }

    @SuppressWarnings("unchecked")
    public TerrainArenaPool(VkDevice device, VulkanContext context, int framesInFlight, int slabCapacity) {
        this.device = device;
        this.context = context;
        this.slabCapacity = slabCapacity;

        // 1. Create static shared quad index buffer
        this.indexBuffer = createStaticQuadIndexBuffer(device, context, MAX_STATIC_QUADS);

        // 2. Pre-allocate initial vertex slab 0
        this.slabs.add(createSlab(0, slabCapacity));

        // 3. Retirement tracking per frame slot
        this.retiringBlocks = new List[framesInFlight];
        for (int i = 0; i < framesInFlight; i++) {
            this.retiringBlocks[i] = new ArrayList<>();
        }
    }

    private static VulkanBuffer createStaticQuadIndexBuffer(VkDevice device, VulkanContext context, int quadCount) {
        int indexCount = quadCount * 6;
        ByteBuffer buffer = ByteBuffer.allocateDirect(indexCount * Integer.BYTES)
                .order(ByteOrder.nativeOrder());
        for (int q = 0; q < quadCount; q++) {
            int base = q * TerrainVertexFormat.VERTICES_PER_QUAD;
            buffer.putInt(base);
            buffer.putInt(base + 1);
            buffer.putInt(base + 2);
            buffer.putInt(base);
            buffer.putInt(base + 2);
            buffer.putInt(base + 3);
        }
        buffer.flip();
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        return VulkanBuffer.create(device, context, data, VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
    }

    private Slab createSlab(int index, int capacity) {
        VulkanBuffer buffer = VulkanBuffer.createDynamic(device, context, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, capacity);
        BlockAllocator allocator = new BlockAllocator(capacity, QUAD_ALIGNMENT);
        return new Slab(index, buffer, allocator);
    }

    public VulkanBuffer indexBuffer() {
        return indexBuffer;
    }

    public Slab slab(int index) {
        return slabs.get(index);
    }

    public int slabCount() {
        return slabs.size();
    }

    /**
     * Sub-allocates {@code sizeBytes} from one of the slabs, allocating a new slab if all existing ones are full.
     */
    public Allocation allocate(int sizeBytes) {
        for (Slab slab : slabs) {
            BlockAllocator.Block block = slab.allocator().allocate(sizeBytes);
            if (block != null) {
                return new Allocation(slab.index(), block);
            }
        }
        // All slabs full; allocate new slab
        Slab newSlab = createSlab(slabs.size(), Math.max(slabCapacity, sizeBytes));
        slabs.add(newSlab);
        BlockAllocator.Block block = newSlab.allocator().allocate(sizeBytes);
        return new Allocation(newSlab.index(), block);
    }

    /**
     * Copies vertex bytes into the allocated slab slot.
     */
    public void upload(Allocation alloc, byte[] vertices, int length) {
        Slab slab = slabs.get(alloc.slabIndex());
        ByteBuffer dest = MemoryUtil.memByteBuffer(slab.buffer().mappedAddress() + alloc.offset(), length);
        dest.put(vertices, 0, length);
    }

    /**
     * Retires an allocation so it can be safely freed after the current slot completes its GPU execution.
     */
    public void retire(Allocation alloc, int currentSlot) {
        if (alloc != null && alloc.block() != null) {
            retiringBlocks[currentSlot].add(alloc);
        }
    }

    /**
     * Frees all allocations that were retired for this slot back into their slab's sub-allocator.
     * Called after the fence for this slot has been waited on.
     */
    public void releaseSlot(int slot) {
        List<Allocation> list = retiringBlocks[slot];
        if (!list.isEmpty()) {
            for (Allocation alloc : list) {
                Slab slab = slabs.get(alloc.slabIndex());
                slab.allocator().free(alloc.block());
            }
            list.clear();
        }
    }

    @Override
    public void close() {
        for (List<Allocation> list : retiringBlocks) {
            list.clear();
        }
        for (Slab slab : slabs) {
            slab.close();
        }
        slabs.clear();
        indexBuffer.close();
    }
}
