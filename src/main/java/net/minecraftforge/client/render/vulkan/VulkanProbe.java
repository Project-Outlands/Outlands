package net.minecraftforge.client.render.vulkan;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.nio.IntBuffer;

import static org.lwjgl.vulkan.KHRSurface.VK_KHR_SURFACE_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_GRAPHICS_BIT;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkCreateInstance;
import static org.lwjgl.vulkan.VK10.vkDestroyInstance;
import static org.lwjgl.vulkan.VK10.vkEnumerateInstanceExtensionProperties;
import static org.lwjgl.vulkan.VK10.vkEnumeratePhysicalDevices;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceQueueFamilyProperties;
import static org.lwjgl.vulkan.VK11.vkEnumerateInstanceVersion;
import static org.lwjgl.vulkan.VK14.VK_API_VERSION_1_4;

/**
 * Whether this machine can run the Vulkan backend, answered before the window exists.
 *
 * <p><b>What it asks.</b> Three things, in increasing cost: the loader reports Vulkan 1.4, {@code VK_KHR_surface}
 * is an instance extension, and there is a physical device with a graphics queue that reports 1.4. Those are the
 * requirements that cannot be worked around later; anything platform-specific (which surface extension, which
 * present mode) is answered once the window exists.</p>
 *
 * <p><b>It creates and destroys a throwaway instance.</b> {@code BackendProbe} asks implementations not to create
 * driver objects, and this honours the intent rather than the letter: the instance is created only because
 * enumerating devices needs one, it is destroyed before the method returns, and nothing about the answer depends
 * on state it leaves behind. There is no way to enumerate devices without an instance.</p>
 *
 * <p><b>The answer is cached</b>, because the probe is consulted at startup and again by the selection path. A
 * cached "no" is also what keeps a machine without Vulkan from paying the probe cost twice.</p>
 */
final class VulkanProbe {

    private static final Logger LOGGER = LogManager.getLogger("Outlands|render");

    private static Boolean cached;
    private static Boolean forcedForTests;

    private VulkanProbe() {}

    /** @return whether the Vulkan backend can own the frame here */
    static synchronized boolean isAvailable() {
        if (forcedForTests != null) {
            return forcedForTests;
        }
        if (cached == null) {
            cached = probe();
        }
        return cached;
    }

    /** Test hook: pins the answer so tests do not depend on the machine's driver. {@code null} restores probing. */
    static synchronized void forceForTests(Boolean value) {
        forcedForTests = value;
        cached = null;
    }

    private static boolean probe() {
        try {
            return doProbe();
        } catch (Throwable t) {
            // A missing loader surfaces as UnsatisfiedLinkError, not as a VkResult: the library is loaded lazily
            // by LWJGL on first use. A probe that throws is a probe that says no.
            LOGGER.info("Vulkan is unavailable on this machine: {}", t.toString());
            return false;
        }
    }

    private static boolean doProbe() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer versionOut = stack.mallocInt(1);
            int result = vkEnumerateInstanceVersion(versionOut);
            if (result != VK_SUCCESS) {
                LOGGER.info("Vulkan is unavailable: vkEnumerateInstanceVersion returned {}", result);
                return false;
            }
            final int instanceVersion = versionOut.get(0);
            if (instanceVersion < VK_API_VERSION_1_4) {
                LOGGER.info("Vulkan is unavailable: the loader reports {}, the backend requires 1.4",
                        version(instanceVersion));
                return false;
            }
            if (!hasSurfaceExtension(stack)) {
                LOGGER.info("Vulkan is unavailable: {} is missing, so there is nothing to present to",
                        VK_KHR_SURFACE_EXTENSION_NAME);
                return false;
            }
            return hasSuitableDevice(stack);
        }
    }

    private static boolean hasSurfaceExtension(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        vkEnumerateInstanceExtensionProperties((String) null, count, null);
        VkExtensionProperties.Buffer available = VkExtensionProperties.malloc(count.get(0), stack);
        vkEnumerateInstanceExtensionProperties((String) null, count, available);
        for (int i = 0; i < available.capacity(); i++) {
            if (VK_KHR_SURFACE_EXTENSION_NAME.equals(available.get(i).extensionNameString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSuitableDevice(MemoryStack stack) {
        VkApplicationInfo applicationInfo = VkApplicationInfo.calloc(stack)
                .sType$Default()
                .pApplicationName(stack.UTF8("Outlands"))
                .pEngineName(stack.UTF8("Outlands"))
                .apiVersion(VK_API_VERSION_1_4);
        VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                .sType$Default()
                .pApplicationInfo(applicationInfo);

        PointerBuffer instanceOut = stack.mallocPointer(1);
        int result = vkCreateInstance(createInfo, null, instanceOut);
        if (result != VK_SUCCESS) {
            LOGGER.info("Vulkan is unavailable: vkCreateInstance returned {}", result);
            return false;
        }
        VkInstance instance = new VkInstance(instanceOut.get(0), createInfo);
        try {
            return hasSuitableDevice(instance, stack);
        } finally {
            vkDestroyInstance(instance, null);
        }
    }

    private static boolean hasSuitableDevice(VkInstance instance, MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        int result = vkEnumeratePhysicalDevices(instance, count, null);
        if (result != VK_SUCCESS || count.get(0) == 0) {
            LOGGER.info("Vulkan is unavailable: no physical devices (result {})", result);
            return false;
        }
        PointerBuffer devices = stack.mallocPointer(count.get(0));
        result = vkEnumeratePhysicalDevices(instance, count, devices);
        if (result != VK_SUCCESS) {
            LOGGER.info("Vulkan is unavailable: vkEnumeratePhysicalDevices returned {}", result);
            return false;
        }

        String rejection = null;
        for (int i = 0; i < devices.capacity(); i++) {
            VkPhysicalDevice device = new VkPhysicalDevice(devices.get(i), instance);
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(device, properties);
            String name = properties.deviceNameString();

            if (properties.apiVersion() < VK_API_VERSION_1_4) {
                rejection = name + " reports " + version(properties.apiVersion());
                continue;
            }
            if (!hasGraphicsQueue(device, stack)) {
                rejection = name + " has no graphics queue family";
                continue;
            }
            LOGGER.info("Vulkan is available: {} ({})", name, version(properties.apiVersion()));
            return true;
        }
        LOGGER.info("Vulkan is unavailable: no device meets the 1.4 baseline ({})", rejection);
        return false;
    }

    private static boolean hasGraphicsQueue(VkPhysicalDevice device, MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(device, count, null);
        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(device, count, families);
        for (int i = 0; i < families.capacity(); i++) {
            if ((families.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) {
                return true;
            }
        }
        return false;
    }

    /** {@code major.minor.patch} for a packed {@code VK_API_VERSION_*} value. */
    static String version(int apiVersion) {
        return ((apiVersion >> 22) & 0x7f) + "." + ((apiVersion >> 12) & 0x3ff) + "." + (apiVersion & 0xfff);
    }
}
