package net.minecraftforge.client.render.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkBufferCreateInfo;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_CACHED_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.vkAllocateMemory;
import static org.lwjgl.vulkan.VK10.vkBindBufferMemory;
import static org.lwjgl.vulkan.VK10.vkCreateBuffer;
import static org.lwjgl.vulkan.VK10.vkDestroyBuffer;
import static org.lwjgl.vulkan.VK10.vkFreeMemory;
import static org.lwjgl.vulkan.VK10.vkGetBufferMemoryRequirements;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceMemoryProperties;

/**
 * A vertex/index buffer in memory the host can write.
 *
 * <p><b>One allocation per buffer, host visible.</b> There is no allocator and no staging copy: this exists so the
 * first real draw has geometry, and the geometry is static. The real allocator (VMA) and the upload path for data
 * that changes every frame are separate work; writing a half-allocator here would only have to be replaced.</p>
 *
 * <p><b>System memory, not host-visible VRAM.</b> On a discrete card with Resizable BAR the host-visible
 * device-local type is a window onto VRAM: the GPU reads it at full speed, but the CPU writes it write-combined and
 * uncached. Everything allocated here is written by the CPU - the frame's stream vertices, the staging copy of an
 * upload, a read-back's destination - so that trade runs the wrong way; see {@link #hostWrittenType}.</p>
 */
final class VulkanBuffer implements AutoCloseable {

    private final VkDevice device;
    private final VulkanContext context;
    private final int usage;
    private long buffer;
    private long memory;
    private long size;
    private long allocationSize;
    private long mappedAddress;
    private long deviceAddress;

    private VulkanBuffer(VkDevice device, VulkanContext context, int usage) {
        this.device = device;
        this.context = context;
        this.usage = usage;
    }

    long handle() {
        return buffer;
    }

    long size() {
        return size;
    }

    long mappedAddress() {
        return mappedAddress;
    }

    long deviceAddress() {
        return deviceAddress;
    }

    /**
     * Creates a buffer usable as vertex and index storage and copies {@code data} into it.
     *
     * @param data little-endian bytes, already in the layout the shader expects
     */
    static VulkanBuffer create(VkDevice device, net.minecraftforge.client.render.vulkan.VulkanContext context,
                               byte[] data) {
        return create(device, context, data,
                VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
    }

    /**
     * Creates a dynamically resizable buffer usable as vertex or index storage, persistently mapped.
     */
    static VulkanBuffer createDynamic(VkDevice device, VulkanContext context, int usage, long initialCapacity) {
        VulkanBuffer buf = new VulkanBuffer(device, context, usage);
        buf.allocate(initialCapacity);
        return buf;
    }

    /**
     * Creates host-visible staging memory of {@code size} bytes without pre-allocated data.
     */
    static VulkanBuffer createStaging(VkDevice device, net.minecraftforge.client.render.vulkan.VulkanContext context,
                                      long size) {
        return createDynamic(device, context, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, size);
    }

    /**
     * Creates host-visible memory holding {@code data}, for use as a copy source.
     */
    static VulkanBuffer createStaging(VkDevice device, net.minecraftforge.client.render.vulkan.VulkanContext context,
                                      byte[] data) {
        return create(device, context, data, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
    }

    /**
     * Creates host-visible memory that the GPU can write into, for reading something back.
     */
    static VulkanBuffer createReadback(VkDevice device, net.minecraftforge.client.render.vulkan.VulkanContext context,
                                       int size) {
        return create(device, context, new byte[size], VK_BUFFER_USAGE_TRANSFER_DST_BIT);
    }

    /** Host-visible memory usable as a copy source and destination. */
    static VulkanBuffer createTransfer(VkDevice device, net.minecraftforge.client.render.vulkan.VulkanContext context,
                                       int size) {
        return create(device, context, new byte[size],
                VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
    }

    /** Dynamically resizable indirect command buffer for MDI draws. */
    static VulkanBuffer createIndirect(VkDevice device, VulkanContext context, long initialCapacity) {
        return createDynamic(device, context, org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT, initialCapacity);
    }

    /** Dynamically resizable chunk data storage buffer accessible via BDA (Buffer Device Address). */
    static VulkanBuffer createChunkData(VkDevice device, VulkanContext context, long initialCapacity) {
        return createDynamic(device, context,
                org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                        | org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                initialCapacity);
    }

    /** Ensures the buffer has at least {@code requiredSize} bytes capacity, growing exponentially if needed. */
    void ensureCapacity(long requiredSize) {
        if (requiredSize <= this.size) {
            return;
        }
        long newCapacity = Math.max(requiredSize, Math.max(256L, this.size * 2));
        free();
        allocate(newCapacity);
    }

    /** Reads {@code length} bytes back into a new array. Blocks on the memory being host-visible and coherent. */
    byte[] read(int length) {
        if (length > size) {
            throw new IllegalArgumentException("cannot read " + length + " bytes from a " + size + " byte buffer");
        }
        byte[] out = new byte[length];
        org.lwjgl.system.MemoryUtil.memByteBuffer(mappedAddress, (int) size).position(0);
        org.lwjgl.system.MemoryUtil.memByteBuffer(mappedAddress, (int) size).get(out, 0, length);
        return out;
    }

    static VulkanBuffer create(VkDevice device, net.minecraftforge.client.render.vulkan.VulkanContext context,
                               byte[] data, int usage) {
        VulkanBuffer buf = new VulkanBuffer(device, context, usage);
        buf.allocate(data.length);
        if (data.length > 0) {
            org.lwjgl.system.MemoryUtil.memByteBuffer(buf.mappedAddress, data.length).put(data);
        }
        return buf;
    }

    private void allocate(long capacity) {
        long effectiveSize = Math.max(capacity, 256L);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo createInfo = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(effectiveSize)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer bufferOut = stack.mallocLong(1);
            VkCheck.check(vkCreateBuffer(device, createInfo, null, bufferOut), "vkCreateBuffer");
            this.buffer = bufferOut.get(0);

            long allocatedMem = 0L;
            try {
                VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
                vkGetBufferMemoryRequirements(device, buffer, requirements);
                this.allocationSize = requirements.size();

                VkMemoryAllocateInfo allocateInfo = VkMemoryAllocateInfo.calloc(stack)
                        .sType$Default()
                        .allocationSize(allocationSize)
                        .memoryTypeIndex(memoryType(context, requirements.memoryTypeBits(), stack));
                if ((usage & org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT) != 0) {
                    org.lwjgl.vulkan.VkMemoryAllocateFlagsInfo flagsInfo = org.lwjgl.vulkan.VkMemoryAllocateFlagsInfo.calloc(stack)
                            .sType$Default()
                            .flags(org.lwjgl.vulkan.VK12.VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT);
                    allocateInfo.pNext(flagsInfo.address());
                }
                LongBuffer memoryOut = stack.mallocLong(1);
                VkCheck.check(vkAllocateMemory(device, allocateInfo, null, memoryOut), "vkAllocateMemory");
                allocatedMem = memoryOut.get(0);
                this.memory = allocatedMem;
                VkCheck.check(vkBindBufferMemory(device, buffer, memory, 0L), "vkBindBufferMemory");

                if ((usage & org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT) != 0) {
                    org.lwjgl.vulkan.VkBufferDeviceAddressInfo addressInfo = org.lwjgl.vulkan.VkBufferDeviceAddressInfo.calloc(stack)
                            .sType$Default()
                            .buffer(buffer);
                    this.deviceAddress = org.lwjgl.vulkan.VK12.vkGetBufferDeviceAddress(device, addressInfo);
                }

                org.lwjgl.PointerBuffer mapped = stack.mallocPointer(1);
                VkCheck.check(org.lwjgl.vulkan.VK10.vkMapMemory(device, memory, 0L, allocationSize, 0, mapped),
                        "vkMapMemory");
                this.mappedAddress = mapped.get(0);
                this.size = effectiveSize;
            } catch (RuntimeException e) {
                if (allocatedMem != 0L) {
                    vkFreeMemory(device, allocatedMem, null);
                    this.memory = 0L;
                }
                vkDestroyBuffer(device, buffer, null);
                this.buffer = 0L;
                throw e;
            }
        }
    }

    private void free() {
        if (mappedAddress != 0L) {
            org.lwjgl.vulkan.VK10.vkUnmapMemory(device, memory);
            mappedAddress = 0L;
        }
        if (buffer != 0L) {
            vkDestroyBuffer(device, buffer, null);
            buffer = 0L;
        }
        if (memory != 0L) {
            vkFreeMemory(device, memory, null);
            memory = 0L;
        }
        size = 0L;
        allocationSize = 0L;
        deviceAddress = 0L;
    }

    private static int memoryType(VulkanContext context, int allowedBits, MemoryStack stack) {
        VkPhysicalDeviceMemoryProperties memoryProperties = VkPhysicalDeviceMemoryProperties.malloc(stack);
        vkGetPhysicalDeviceMemoryProperties(context.physicalDevice(), memoryProperties);
        int[] typeFlags = new int[memoryProperties.memoryTypeCount()];
        for (int i = 0; i < typeFlags.length; i++) {
            typeFlags[i] = memoryProperties.memoryTypes(i).propertyFlags();
        }
        int chosen = hostWrittenType(allowedBits, typeFlags);
        if (chosen < 0) {
            throw new VkCheck.VulkanException("no host-visible memory type for a buffer the host writes");
        }
        return chosen;
    }

    /**
     * The memory type a buffer the host writes should live in, given the type bits the driver allows.
     *
     * <p><b>System memory before host-visible VRAM, deliberately.</b> Host-visible <em>device-local</em> memory looks
     * like the obvious choice - it is VRAM, and the GPU reads it at full speed - but on a discrete card with
     * Resizable BAR the CPU only reaches it through the BAR, write-combined and uncached. Every buffer this class
     * allocates is written by the CPU: the frame's stream vertices, the staging copy of a texture upload, the
     * destination of a read-back. Measured on an RX 6650 XT (RADV, ReBAR on): converting one frame's stream vertices
     * into a host-visible device-local buffer cost <b>~400 ns a vertex</b> against <b>3-5 ns</b> into cached system
     * memory - 15 of the frame's 16 ms, with the profile showing all of it in {@code display_update} - and one
     * block-atlas upload of a few megabytes took ~20 ms. The GPU then reads at most a megabyte per frame over PCIe,
     * which is a fraction of a millisecond.</p>
     *
     * <p>{@code HOST_CACHED} first among the system types: it is what makes the CPU's writes fast, and it is also
     * what a read-back needs, since the uncached alternative makes the CPU read of GPU output the slowest case
     * there is.</p>
     *
     * @return the index of the type to use, or -1 when the driver offers no host-visible type for this buffer
     */
    static int hostWrittenType(int allowedBits, int[] typeFlags) {
        int cached = -1;
        int coherent = -1;
        int any = -1;
        int deviceLocalVisible = -1;
        for (int i = 0; i < typeFlags.length; i++) {
            if ((allowedBits & (1 << i)) == 0) {
                continue;
            }
            int flags = typeFlags[i];
            if ((flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) == 0) {
                continue;
            }
            boolean isCoherent = (flags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
            if ((flags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0) {
                // VRAM over the BAR: fast for the GPU, slow for every store the CPU makes.
                if (deviceLocalVisible < 0) {
                    deviceLocalVisible = i;
                }
                continue;
            }
            if (isCoherent && (flags & VK_MEMORY_PROPERTY_HOST_CACHED_BIT) != 0 && cached < 0) {
                cached = i;
            } else if (isCoherent && coherent < 0) {
                coherent = i;
            }
            if (any < 0) {
                any = i;
            }
        }
        if (cached >= 0) {
            return cached;
        }
        if (coherent >= 0) {
            return coherent;
        }
        if (any >= 0) {
            return any;
        }
        // An integrated GPU has one memory: the only host-visible type is the device-local one.
        return deviceLocalVisible;
    }

    @Override
    public void close() {
        free();
    }
}
