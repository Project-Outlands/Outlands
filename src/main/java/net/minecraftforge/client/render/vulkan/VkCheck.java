package net.minecraftforge.client.render.vulkan;

import static org.lwjgl.vulkan.VK10.VK_INCOMPLETE;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;

/**
 * Turns a Vulkan return code into an exception, the way {@code Sys.checkSdl} does for SDL.
 *
 * <p>Vulkan reports failure through return codes rather than exceptions, and almost every call can fail. A helper
 * that names the call is the difference between "something went wrong" and "vkCreateSwapchainKHR returned
 * VK_ERROR_NATIVE_WINDOW_IN_USE_KHR".</p>
 *
 * <p>LWJGL maps {@code VkResult} to {@code int}, so this takes and compares ints rather than an enum.</p>
 */
final class VkCheck {

    private VkCheck() {}

    /** @throws VulkanException if {@code result} is anything but {@link org.lwjgl.vulkan.VK10#VK_SUCCESS} */
    static void check(int result, String call) {
        if (result != VK_SUCCESS) {
            throw new VulkanException(call + " failed with " + result);
        }
    }

    /** As {@link #check}, but also accepts {@code VK_INCOMPLETE}, which some enumerations return. */
    static void checkAllowingIncomplete(int result, String call) {
        if (result != VK_SUCCESS && result != VK_INCOMPLETE) {
            throw new VulkanException(call + " failed with " + result);
        }
    }

    /** A Vulkan call failed in a way the backend cannot continue from. */
    static final class VulkanException extends RuntimeException {
        VulkanException(String message) {
            super(message);
        }
    }
}
