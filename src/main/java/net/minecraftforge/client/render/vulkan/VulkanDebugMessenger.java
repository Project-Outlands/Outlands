package net.minecraftforge.client.render.vulkan;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;
import org.lwjgl.vulkan.VkInstance;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.vkCreateDebugUtilsMessengerEXT;
import static org.lwjgl.vulkan.EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;

/**
 * Routes validation layer messages into the game log.
 *
 * <p>Without this the validation layer writes to stderr, which a launched game does not show. With it, every
 * specification violation arrives in the same log as everything else, at a severity that matches: errors as
 * errors, warnings as warnings, the rest at debug.</p>
 *
 * <p>The messenger is created with the instance and destroyed before it, because a message can be emitted while
 * the instance is being destroyed.</p>
 */
final class VulkanDebugMessenger {

    private static final Logger LOGGER = LogManager.getLogger("Outlands|Vulkan");

    private VulkanDebugMessenger() {}

    /**
     * Installs the messenger.
     *
     * @return the messenger handle, or {@code 0} when creation failed (validation then runs without a sink)
     */
    static long install(VkInstance instance, MemoryStack stack) {
        VkDebugUtilsMessengerCreateInfoEXT createInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
                .sType$Default()
                .messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT
                        | VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT
                        | VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT)
                .messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT
                        | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                        | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                .pfnUserCallback(VulkanDebugMessenger::callback);

        LongBuffer messengerOut = stack.mallocLong(1);
        int result = vkCreateDebugUtilsMessengerEXT(instance, createInfo, null, messengerOut);
        if (result != VK_SUCCESS) {
            LOGGER.warn("vkCreateDebugUtilsMessengerEXT returned {}; validation messages will go to stderr", result);
            return 0L;
        }
        LOGGER.info("Vulkan validation layers are active");
        return messengerOut.get(0);
    }

    static void remove(VkInstance instance, long messenger) {
        vkDestroyDebugUtilsMessengerEXT(instance, messenger, null);
    }

    private static int callback(int severity, int types, long callbackData, long userData) {
        try {
            VkDebugUtilsMessengerCallbackDataEXT data =
                    VkDebugUtilsMessengerCallbackDataEXT.create(callbackData);
            String message = data.pMessageString();
            if ((severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) {
                LOGGER.error("[vulkan] {}", message);
            } else if ((severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) != 0) {
                LOGGER.warn("[vulkan] {}", message);
            } else {
                LOGGER.debug("[vulkan] {}", message);
            }
        } catch (Throwable t) {
            // A callback that throws across the native boundary takes the process down. Never let it.
            LOGGER.debug("Failed to format a Vulkan validation message", t);
        }
        // VK_FALSE: the message was handled, do not abort the call that produced it.
        return 0;
    }
}
