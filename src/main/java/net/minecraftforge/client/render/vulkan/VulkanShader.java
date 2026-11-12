package net.minecraftforge.client.render.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.vkCreateShaderModule;
import static org.lwjgl.vulkan.VK10.vkDestroyShaderModule;

/**
 * A SPIR-V module, loaded from the classpath.
 *
 * <p>This is the only way a shader enters the process: the loader's own shaders are compiled ahead of time and
 * shipped as {@code .spv}, and a shader pack will arrive the same way. There is deliberately no path that takes
 * GLSL at run time - one shader path means one set of failure modes, and it is the same one pack authors will
 * hit.</p>
 *
 * <p>SPIR-V is a sequence of 32-bit words, so the byte length has to be a multiple of four and the buffer is read
 * as ints. A truncated or corrupted file is rejected here with the size in the message, rather than handed to the
 * driver to reject with a less useful one.</p>
 */
final class VulkanShader implements AutoCloseable {

    private final VkDevice device;
    private final long module;

    private VulkanShader(VkDevice device, long module) {
        this.device = device;
        this.module = module;
    }

    long handle() {
        return module;
    }

    /**
     * Loads {@code resource} (an absolute classpath path) and creates a shader module from it.
     *
     * @throws IllegalStateException if the resource is missing, empty, or not a multiple of four bytes
     */
    static VulkanShader load(VkDevice device, String resource) {
        return create(device, read(resource), resource);
    }

    /**
     * Creates a module from SPIR-V that arrived some other way than a resource.
     *
     * <p>The game's own shaders reach the backend as GLSL strings, compiled in process by the GL facade; that is what
     * needs this entry point. The bytes still have to be a whole number of words, so the check is the same one a
     * resource gets rather than a weaker one for a path that "was already checked".</p>
     */
    static VulkanShader create(VkDevice device, byte[] bytes, String name) {
        if (bytes.length == 0) {
            throw new IllegalStateException(name + " is empty");
        }
        if (bytes.length % 4 != 0) {
            throw new IllegalStateException(name + " is " + bytes.length
                    + " bytes, which is not a whole number of 32-bit SPIR-V words");
        }

        ByteBuffer spirv = MemoryUtil.memAlloc(bytes.length);
        try {
            spirv.put(bytes).flip();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                        .sType$Default()
                        .pCode(spirv);
                LongBuffer moduleOut = stack.mallocLong(1);
                VkCheck.check(vkCreateShaderModule(device, createInfo, null, moduleOut),
                        "vkCreateShaderModule(" + name + ")");
                return new VulkanShader(device, moduleOut.get(0));
            }
        } finally {
            MemoryUtil.memFree(spirv);
        }
    }

    private static byte[] read(String resource) {
        try (InputStream stream = VulkanShader.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing shader resource " + resource);
            }
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + resource, e);
        }
    }

    @Override
    public void close() {
        vkDestroyShaderModule(device, module, null);
    }
}
