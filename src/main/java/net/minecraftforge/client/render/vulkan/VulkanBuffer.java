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
 * <p>Host-visible device-local memory is what makes the staging copy unnecessary, and it is available on anything
 * with ReBAR or unified memory. The fallback to plain host memory is silent on purpose: the geometry still
 * arrives, only slower, and this is not a path where the difference is worth a warning.</p>
 */
final class VulkanBuffer implements AutoCloseable {

    private final VkDevice device;
    private final long buffer;
    private final long memory;
    private final long size;

    private VulkanBuffer(VkDevice device, long buffer, long memory, long size) {
        this.device = device;
        this.buffer = buffer;
        this.memory = memory;
        this.size = size;
    }

    long handle() {
        return buffer;
    }

    long size() {
        return size;
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
     * Creates host-visible memory holding {@code data}, for use as a copy source.
     *
     * <p>The upload path's half of a staged transfer: the bytes go here, and a {@code vkCmdCopyBufferToImage} reads
     * them. Device-local is not requested - this memory is written by the host and read once by the copy engine, so
     * the preference for ReBAR that the vertex buffers use would be wrong here.</p>
     */
    static VulkanBuffer createStaging(VkDevice device, net.minecraftforge.client.render.vulkan.VulkanContext context,
                                      byte[] data) {
        return create(device, context, data, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
    }

    /**
     * Creates host-visible memory that the GPU can write into, for reading something back.
     *
     * <p>Used by the upload verification: a copy of the device image lands here, and the bytes are compared against
     * what was sent. That comparison is the only way to tell "the copy worked" from "the copy was recorded" without
     * a screenshot, and the two look identical from the outside.</p>
     */
    static VulkanBuffer createReadback(VkDevice device, net.minecraftforge.client.render.vulkan.VulkanContext context,
                                       int size) {
        return create(device, context, new byte[size], VK_BUFFER_USAGE_TRANSFER_DST_BIT);
    }

    /** Reads {@code length} bytes back into a new array. Blocks on the memory being host-visible and coherent. */
    byte[] read(int length) {
        if (length > size) {
            throw new IllegalArgumentException("cannot read " + length + " bytes from a " + size + " byte buffer");
        }
        byte[] out = new byte[length];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.PointerBuffer mapped = stack.mallocPointer(1);
            VkCheck.check(org.lwjgl.vulkan.VK10.vkMapMemory(device, memory, 0L, size, 0, mapped), "vkMapMemory");
            try {
                org.lwjgl.system.MemoryUtil.memByteBuffer(mapped.get(0), (int) size).get(out);
            } finally {
                org.lwjgl.vulkan.VK10.vkUnmapMemory(device, memory);
            }
        }
        return out;
    }

    private static VulkanBuffer create(VkDevice device, net.minecraftforge.client.render.vulkan.VulkanContext context,
                                       byte[] data, int usage) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo createInfo = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(data.length)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer bufferOut = stack.mallocLong(1);
            VkCheck.check(vkCreateBuffer(device, createInfo, null, bufferOut), "vkCreateBuffer");
            long buffer = bufferOut.get(0);

            long memory = 0L;
            try {
                VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
                vkGetBufferMemoryRequirements(device, buffer, requirements);

                VkMemoryAllocateInfo allocateInfo = VkMemoryAllocateInfo.calloc(stack)
                        .sType$Default()
                        .allocationSize(requirements.size())
                        .memoryTypeIndex(memoryType(context, requirements.memoryTypeBits(), stack));
                LongBuffer memoryOut = stack.mallocLong(1);
                VkCheck.check(vkAllocateMemory(device, allocateInfo, null, memoryOut), "vkAllocateMemory");
                memory = memoryOut.get(0);
                VkCheck.check(vkBindBufferMemory(device, buffer, memory, 0L), "vkBindBufferMemory");

                write(device, memory, requirements.size(), data);
                return new VulkanBuffer(device, buffer, memory, data.length);
            } catch (RuntimeException e) {
                if (memory != 0L) {
                    vkFreeMemory(device, memory, null);
                }
                vkDestroyBuffer(device, buffer, null);
                throw e;
            }
        }
    }

    private static void write(VkDevice device, long memory, long allocationSize, byte[] data) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.PointerBuffer mapped = stack.mallocPointer(1);
            VkCheck.check(org.lwjgl.vulkan.VK10.vkMapMemory(device, memory, 0L, allocationSize, 0, mapped),
                    "vkMapMemory");
            try {
                org.lwjgl.system.MemoryUtil.memByteBuffer(mapped.get(0), data.length).put(data);
            } finally {
                // COHERENT memory, so unmapping is enough to make the write visible; no explicit flush.
                org.lwjgl.vulkan.VK10.vkUnmapMemory(device, memory);
            }
        }
    }

    private static int memoryType(VulkanContext context, int allowedBits, MemoryStack stack) {
        VkPhysicalDeviceMemoryProperties memoryProperties = VkPhysicalDeviceMemoryProperties.malloc(stack);
        vkGetPhysicalDeviceMemoryProperties(context.physicalDevice(), memoryProperties);

        // Prefer host-visible device-local (ReBAR / unified memory): written once, read by the GPU at full speed.
        int fallback = -1;
        for (int i = 0; i < memoryProperties.memoryTypeCount(); i++) {
            if ((allowedBits & (1 << i)) == 0) {
                continue;
            }
            int flags = memoryProperties.memoryTypes(i).propertyFlags();
            boolean hostVisible = (flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0;
            if (!hostVisible) {
                continue;
            }
            boolean coherent = (flags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
            if ((flags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0 && coherent) {
                return i;
            }
            if (coherent && fallback < 0) {
                fallback = i;
            }
        }
        if (fallback < 0) {
            throw new VkCheck.VulkanException("no host-visible coherent memory type for a vertex buffer");
        }
        return fallback;
    }

    @Override
    public void close() {
        vkDestroyBuffer(device, buffer, null);
        vkFreeMemory(device, memory, null);
    }
}
