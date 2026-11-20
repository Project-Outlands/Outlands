package net.minecraftforge.client.render.vulkan;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkLayerProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceLimits;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan13Features;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan14Features;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLVulkan;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL20.GL_MAX_TEXTURE_IMAGE_UNITS;
import static org.lwjgl.opengl.GL20.GL_MAX_VERTEX_ATTRIBS;
import static org.lwjgl.opengl.GL20.GL_MAX_FRAGMENT_UNIFORM_COMPONENTS;
import static org.lwjgl.opengl.GL20.GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS;
import static org.lwjgl.opengl.GL20.GL_MAX_VERTEX_UNIFORM_COMPONENTS;
import static org.lwjgl.opengl.GL30.GL_MAX_ARRAY_TEXTURE_LAYERS;
import static org.lwjgl.opengl.GL11.GL_MAX_TEXTURE_SIZE;
import static org.lwjgl.opengl.GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR;
import static org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSurface.VK_KHR_SURFACE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU;
import static org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU;
import static org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_GRAPHICS_BIT;
import static org.lwjgl.vulkan.VK10.vkCreateDevice;
import static org.lwjgl.vulkan.VK10.vkCreateInstance;
import static org.lwjgl.vulkan.VK10.vkDestroyDevice;
import static org.lwjgl.vulkan.VK10.vkDestroyInstance;
import static org.lwjgl.vulkan.VK10.vkEnumerateDeviceExtensionProperties;
import static org.lwjgl.vulkan.VK10.vkEnumerateInstanceExtensionProperties;
import static org.lwjgl.vulkan.VK10.vkEnumerateInstanceLayerProperties;
import static org.lwjgl.vulkan.VK10.vkEnumeratePhysicalDevices;
import static org.lwjgl.vulkan.VK10.vkGetDeviceQueue;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceFeatures;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceQueueFamilyProperties;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;
import static org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3;
import static org.lwjgl.vulkan.VK14.VK_API_VERSION_1_4;

/**
 * The Vulkan instance, surface, device and queue.
 *
 * <p>Everything here is created once and lives until shutdown. The split from {@link VulkanSwapchain} follows the
 * API's own: this is "which device am I talking to", the swapchain is "what am I drawing into", and only the
 * second one gets torn down when the window changes size.</p>
 *
 * <p><b>Order is not arbitrary.</b> The surface has to exist before a physical device is chosen, because whether
 * a queue family can present is a property of the device-and-surface pair, not of the device. So: instance,
 * surface, device.</p>
 *
 * <p><b>Features are queried, then enabled.</b> The backend is designed around four feature groups - dynamic
 * rendering, synchronization2, buffer device address, descriptor indexing - plus three Vulkan 1.4 additions that
 * will make the GL shim cheaper (push descriptors, host image copy, maintenance5). The baseline is 1.3: the three
 * 1.4 features are enabled when the device has them and absent otherwise, because nothing calls them yet. Gating
 * the whole backend on 1.4 rejected devices that can run everything the code does - measured: Turnip reporting
 * 1.3.296. When a caller starts using one of them, the call site checks the feature flag rather than the version.
 * </p>
 */
final class VulkanContext {

    private static final Logger LOGGER = LogManager.getLogger("Outlands|render");

    private static final String VALIDATION_LAYER = "VK_LAYER_KHRONOS_validation";
    /** Set once the instance is created, so a measurement can say whether the layer was in the way. */
    private static volatile boolean validationActive;

    private final VkInstance instance;
    private long debugMessenger;
    private final long surface;
    private final VkPhysicalDevice physicalDevice;
    private final VkDevice device;
    private final VkQueue graphicsQueue;
    private final int graphicsFamily;
    private final String deviceName;
    private final boolean multiDrawIndirect;
    private final boolean drawIndirectFirstInstance;
    private final boolean fillModeNonSolid;
    /**
     * The line widths this device will rasterise, read once.
     *
     * <p><b>Not read per call.</b> {@code vkGetPhysicalDeviceProperties} is a driver query, and the caller asks for a
     * clamped width once per draw: a thousand queries a frame, measured at 0.43 us a draw in a scene of a thousand
     * draws - 0.44 ms of a 15 ms frame spent asking a constant question. The range cannot change while the device
     * lives, so it is read where the device is created, next to the other limits that are.</p>
     */
    private final float lineWidthMin;
    private final float lineWidthMax;

    private VulkanContext(VkInstance instance, long debugMessenger, long surface, VkPhysicalDevice physicalDevice,
                          VkDevice device, VkQueue graphicsQueue, int graphicsFamily, String deviceName,
                          boolean multiDrawIndirect, boolean drawIndirectFirstInstance, boolean fillModeNonSolid,
                          float lineWidthMin, float lineWidthMax) {
        this.instance = instance;
        this.debugMessenger = debugMessenger;
        this.surface = surface;
        this.physicalDevice = physicalDevice;
        this.device = device;
        this.graphicsQueue = graphicsQueue;
        this.graphicsFamily = graphicsFamily;
        this.deviceName = deviceName;
        this.multiDrawIndirect = multiDrawIndirect;
        this.drawIndirectFirstInstance = drawIndirectFirstInstance;
        this.fillModeNonSolid = fillModeNonSolid;
        this.lineWidthMin = lineWidthMin;
        this.lineWidthMax = lineWidthMax;
    }

    /**
     * Whether a pipeline may rasterise as lines or points, which {@code glPolygonMode} asks for.
     *
     * <p>Without it Vulkan rejects a pipeline that is not {@code FILL}, so the mode is clamped rather than passed on -
     * the same shape as the line width and its {@code wideLines} feature.</p>
     */
    boolean supportsNonSolidFill() {
        return fillModeNonSolid;
    }

    boolean supportsIndirect() {
        return multiDrawIndirect && drawIndirectFirstInstance;
    }

    boolean multiDrawIndirect() {
        return multiDrawIndirect;
    }

    boolean drawIndirectFirstInstance() {
        return drawIndirectFirstInstance;
    }

    VkInstance instance() {
        return instance;
    }

    long surface() {
        return surface;
    }

    VkPhysicalDevice physicalDevice() {
        return physicalDevice;
    }

    VkDevice device() {
        return device;
    }

    VkQueue graphicsQueue() {
        return graphicsQueue;
    }

    int graphicsFamily() {
        return graphicsFamily;
    }

    /**
     * The largest 2D texture edge this device can create.
     *
     * <p>Read from the device properties rather than cached at selection time: it is asked at most once, and
     * keeping it next to the query makes it obvious where the number comes from.</p>
     */
    int maxTextureSize() {
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physicalDevice, properties);
            return properties.limits().maxImageDimension2D();
        }
    }

    /**
     * A line width this device will actually rasterise.
     *
     * <p>GL clamps too - {@code glLineWidth} is specified to clamp into the implementation's range - so this is the
     * same rule, not a deviation. Two things make it necessary here: the range is a device limit
     * ({@code lineWidthRange}), and when {@code wideLines} is not enabled the only legal value is 1.0, which the spec
     * states as a validation rule rather than clamping for us.</p>
     */
    float clampLineWidth(float width) {
        return clampLineWidth(width, this.lineWidthMin, this.lineWidthMax);
    }

    /**
     * The clamp itself, apart from the query so it can be stated without a device.
     *
     * <p>Without {@code wideLines} the range is 1.0 to 1.0, and that is the whole of the degraded behaviour: the same
     * arithmetic answers both cases.</p>
     */
    static float clampLineWidth(float width, float min, float max) {
        return Math.max(min, Math.min(max, width));
    }

    /**
     * The alignment a dynamic uniform buffer offset has to be a multiple of.
     *
     * <p>Both a descriptor's own offset and a dynamic offset are checked against this, and the limit is per device -
     * 64 on many, 256 on others. Getting it from the device rather than assuming 256 costs one query and removes the
     * class of failure where it works on the machine it was written on.</p>
     */
    int minUniformBufferOffsetAlignment() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physicalDevice, properties);
            return (int) properties.limits().minUniformBufferOffsetAlignment();
        }
    }

    /**
     * A device limit, named by the GL enum that asks for it, or {@code 0} for one this backend has no answer to.
     *
     * <p><b>The set is what the game asks for, not what GL defines.</b> These six are every limit vanilla reads
     * through {@code GlStateManager.glGetInteger} - the client snooper collects them once during startup - so they
     * are the ones worth a mapping. Anything else keeps returning 0 and the "not implemented" line, which is the
     * honest answer for a limit nobody has needed yet.</p>
     *
     * <p><b>Where the GL name does not line up with a Vulkan one, the unit is what gets matched, not the name.</b>
     * {@code GL_MAX_*_UNIFORM_COMPONENTS} counts 4-byte components in the default uniform block, which Vulkan does
     * not have; its equivalent quantity is how many components fit in one uniform buffer, so the answer is
     * {@code maxUniformBufferRange / 4}. The texture-unit limits are per stage in both APIs, so they map one to
     * one. Returning a number that means something else would be worse than returning none, so nothing is
     * approximated beyond that.</p>
     */
    /** {@code GL_MAX_CUBE_MAP_TEXTURE_SIZE}, which LWJGL keeps out of the classes this file already imports. */
    private static final int GL_MAX_CUBE_MAP_TEXTURE_SIZE = 34076;

    int glLimit(int glEnum) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            // Without this the struct stays zeroed and every answer below is 0 - which the caller reads as "this
            // backend has no answer", so all six limits were reported as unimplemented while the mapping existed.
            vkGetPhysicalDeviceProperties(physicalDevice, properties);
            VkPhysicalDeviceLimits limits = properties.limits();
            return switch (glEnum) {
                case GL_MAX_VERTEX_ATTRIBS -> limits.maxVertexInputAttributes();
                case GL_MAX_TEXTURE_IMAGE_UNITS, GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS ->
                        limits.maxPerStageDescriptorSamplers();
                // GL counts texture units across the whole pipeline; the Vulkan quantity a program can actually use is
                // how many samplers fit in one descriptor set, since that is what every draw binds.
                case GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS -> limits.maxDescriptorSetSamplers();
                case GL_MAX_TEXTURE_SIZE -> limits.maxImageDimension2D();
                case GL_MAX_CUBE_MAP_TEXTURE_SIZE -> limits.maxImageDimensionCube();
                case GL_MAX_ARRAY_TEXTURE_LAYERS -> limits.maxImageArrayLayers();
                // 4-byte components per uniform buffer: the unit GL counts them in. See the method comment.
                case GL_MAX_VERTEX_UNIFORM_COMPONENTS, GL_MAX_FRAGMENT_UNIFORM_COMPONENTS ->
                        (int) (limits.maxUniformBufferRange() / 4L);
                default -> 0;
            };
        }
    }

    String deviceName() {
        return deviceName;
    }

    /**
     * Builds the instance, surface and device for {@code window}.
     *
     * @param window the SDL window handle the surface is created from
     */
    static VulkanContext create(long window) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            List<String> instanceExtensions = new ArrayList<>();
            // SDL knows which platform surface extension this build needs. Asking it is what keeps the backend
            // free of per-platform branching.
            PointerBuffer sdlExtensions = SDLVulkan.SDL_Vulkan_GetInstanceExtensions();
            if (sdlExtensions == null) {
                throw new VkCheck.VulkanException("SDL_Vulkan_GetInstanceExtensions: " + SDLError.SDL_GetError());
            }
            for (int i = 0; i < sdlExtensions.remaining(); i++) {
                instanceExtensions.add(sdlExtensions.getStringUTF8(i));
            }
            addIfMissing(instanceExtensions, VK_KHR_SURFACE_EXTENSION_NAME);

            boolean validation = validationRequested();
            if (validation && hasLayer(VALIDATION_LAYER)
                    && hasInstanceExtension(VK_EXT_DEBUG_UTILS_EXTENSION_NAME)) {
                instanceExtensions.add(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
            } else {
                if (validation) {
                    LOGGER.warn("Vulkan validation was requested but {} is not installed; continuing without it. "
                            + "Install the vulkan-validationlayers package for spec checking.", VALIDATION_LAYER);
                }
                validation = false;
            }
            validationActive = validation;

            int instanceFlags = 0;
            // MoltenVK is not a conformant implementation, so it only appears when asked for explicitly.
            if (hasInstanceExtension(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME)) {
                addIfMissing(instanceExtensions, VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME);
                instanceFlags |= VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR;
            }

            PointerBuffer extensionNames = stack.mallocPointer(instanceExtensions.size());
            for (String name : instanceExtensions) {
                extensionNames.put(stack.UTF8(name));
            }
            extensionNames.flip();

            VkApplicationInfo applicationInfo = VkApplicationInfo.calloc(stack)
                    .sType$Default()
                    .pApplicationName(stack.UTF8("Outlands"))
                    .applicationVersion(1)
                    .pEngineName(stack.UTF8("Outlands"))
                    .engineVersion(1)
                    .apiVersion(VK_API_VERSION_1_3);
            VkInstanceCreateInfo instanceCreateInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(instanceFlags)
                    .pApplicationInfo(applicationInfo)
                    .ppEnabledExtensionNames(extensionNames);
            if (validation) {
                instanceCreateInfo.ppEnabledLayerNames(stack.pointers(stack.UTF8(VALIDATION_LAYER)));
            }

            PointerBuffer instanceOut = stack.mallocPointer(1);
            VkCheck.check(vkCreateInstance(instanceCreateInfo, null, instanceOut), "vkCreateInstance");
            VkInstance instance = new VkInstance(instanceOut.get(0), instanceCreateInfo);

            long debugMessenger = 0L;
            try {
                if (validation) {
                    debugMessenger = VulkanDebugMessenger.install(instance, stack);
                }

                LongBuffer surfaceOut = stack.mallocLong(1);
                if (!SDLVulkan.SDL_Vulkan_CreateSurface(window, instance, null, surfaceOut)) {
                    throw new VkCheck.VulkanException("SDL_Vulkan_CreateSurface: " + SDLError.SDL_GetError());
                }
                long surface = surfaceOut.get(0);

                try {
                    VkPhysicalDevice physicalDevice = pickPhysicalDevice(instance, surface);
                    VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
                    vkGetPhysicalDeviceProperties(physicalDevice, properties);
                    int graphicsFamily = graphicsFamily(physicalDevice, surface);
                    DeviceResult deviceResult = createDevice(physicalDevice, graphicsFamily,
                            properties.apiVersion(), stack);
                    VkDevice device = deviceResult.device();
                    VkQueue queue = queue(device, graphicsFamily, stack);

                    LOGGER.info("Vulkan device: {} ({}) [MDI: {}, firstInstance: {}]", properties.deviceNameString(),
                            VulkanProbe.version(properties.apiVersion()),
                            deviceResult.multiDrawIndirect(), deviceResult.drawIndirectFirstInstance(),
                            deviceResult.fillModeNonSolid());
                    return new VulkanContext(instance, debugMessenger, surface, physicalDevice, device, queue,
                            graphicsFamily, properties.deviceNameString(),
                            deviceResult.multiDrawIndirect(), deviceResult.drawIndirectFirstInstance(),
                            deviceResult.fillModeNonSolid(),
                            properties.limits().lineWidthRange(0), properties.limits().lineWidthRange(1));
                } catch (RuntimeException e) {
                    vkDestroySurfaceKHR(instance, surface, null);
                    throw e;
                }
            } catch (RuntimeException e) {
                if (debugMessenger != 0L) {
                    VulkanDebugMessenger.remove(instance, debugMessenger);
                }
                vkDestroyInstance(instance, null);
                throw e;
            }
        }
    }

    /** Destroys the device, surface and instance, in that order. */
    void close() {
        vkDestroyDevice(device, null);
        vkDestroySurfaceKHR(instance, surface, null);
        if (debugMessenger != 0L) {
            VulkanDebugMessenger.remove(instance, debugMessenger);
            debugMessenger = 0L;
        }
        vkDestroyInstance(instance, null);
    }

    private static void addIfMissing(List<String> list, String name) {
        if (!list.contains(name)) {
            list.add(name);
        }
    }

    private static VkPhysicalDevice pickPhysicalDevice(VkInstance instance, long surface) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
        IntBuffer count = stack.mallocInt(1);
        VkCheck.check(vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices");
        if (count.get(0) == 0) {
            throw new VkCheck.VulkanException("no Vulkan physical device");
        }
        PointerBuffer devices = stack.mallocPointer(count.get(0));
        VkCheck.check(vkEnumeratePhysicalDevices(instance, count, devices), "vkEnumeratePhysicalDevices");

        VkPhysicalDevice best = null;
        int bestScore = -1;
        for (int i = 0; i < devices.capacity(); i++) {
            VkPhysicalDevice candidate = new VkPhysicalDevice(devices.get(i), instance);
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(candidate, properties);

            if (properties.apiVersion() < VK_API_VERSION_1_3) {
                LOGGER.debug("Skipping {}: reports {}", properties.deviceNameString(),
                        VulkanProbe.version(properties.apiVersion()));
                continue;
            }
            if (!hasDeviceExtension(candidate, VK_KHR_SWAPCHAIN_EXTENSION_NAME)) {
                LOGGER.debug("Skipping {}: no {}", properties.deviceNameString(), VK_KHR_SWAPCHAIN_EXTENSION_NAME);
                continue;
            }
            if (graphicsFamily(candidate, surface) < 0) {
                LOGGER.debug("Skipping {}: no queue family that can both draw and present",
                        properties.deviceNameString());
                continue;
            }
            int score = score(properties.deviceType());
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        if (best == null) {
            throw new VkCheck.VulkanException("no Vulkan device meets the 1.3 baseline");
        }
        return best;
        }
    }

    /** Prefers a discrete GPU, then integrated, then virtual; a software rasteriser scores last. */
    private static int score(int deviceType) {
        if (deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) {
            return 3;
        }
        if (deviceType == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU) {
            return 2;
        }
        if (deviceType == VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU) {
            return 1;
        }
        return 0;
    }

    /**
     * The queue family that can both draw and present, or {@code -1}.
     *
     * <p>Picking them separately would need cross-queue synchronisation for every presented frame. On desktop
     * hardware one family does both, and requiring that keeps the frame loop single-queue.</p>
     */
    private static int graphicsFamily(VkPhysicalDevice device, long surface) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
        IntBuffer count = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(device, count, null);
        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(device, count, families);

        IntBuffer presentSupported = stack.mallocInt(1);
        for (int i = 0; i < families.capacity(); i++) {
            if ((families.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) == 0) {
                continue;
            }
            VkCheck.check(vkGetPhysicalDeviceSurfaceSupportKHR(device, i, surface, presentSupported),
                    "vkGetPhysicalDeviceSurfaceSupportKHR");
            if (presentSupported.get(0) != 0) {
                return i;
            }
        }
        return -1;
        }
    }

    private record DeviceResult(VkDevice device, boolean multiDrawIndirect, boolean drawIndirectFirstInstance,
                                boolean fillModeNonSolid) {}

    private static DeviceResult createDevice(VkPhysicalDevice physicalDevice, int family, int deviceApiVersion,
                                             MemoryStack stack) {
        // Query Vulkan 1.0 physical device features (multiDrawIndirect, drawIndirectFirstInstance)
        VkPhysicalDeviceFeatures supported10 = VkPhysicalDeviceFeatures.calloc(stack);
        vkGetPhysicalDeviceFeatures(physicalDevice, supported10);

        // One query per feature struct rather than one chained query. Chaining is legal, but a single wrong link
        // silently reports every feature as unsupported, and the failure looks exactly like "this GPU is too old".
        // Three calls happen once at startup and remove that whole class of mistake.
        VkPhysicalDeviceVulkan12Features supported12 = VkPhysicalDeviceVulkan12Features.calloc(stack)
                .sType$Default();
        vkGetPhysicalDeviceFeatures2(physicalDevice,
                VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(supported12.address()));
        VkPhysicalDeviceVulkan13Features supported13 = VkPhysicalDeviceVulkan13Features.calloc(stack)
                .sType$Default();
        vkGetPhysicalDeviceFeatures2(physicalDevice,
                VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(supported13.address()));
        // Only asked of a device that reports 1.4. On a 1.3 device the struct's sType is unknown and would be
        // ignored, which is legal but means the answers are all "false" for a reason that has nothing to do with
        // the features themselves - and a caller reading that would draw the wrong conclusion.
        boolean vulkan14 = deviceApiVersion >= VK_API_VERSION_1_4;
        // The three 1.4 features are reported, not used, and plan §4.1 (v5) judged that the shim work they were
        // held for should not be written: push descriptors are strictly more work than the per-texture sets bound on
        // change, host image copy would add a second, synchronous upload path for a stall nothing has, and
        // maintenance5's A8_UNORM contradicts the BGRA pixel contract. They stay as a capability report - a device
        // that has them is one where a future trigger (same section) could be met - and never as a branch.
        VkPhysicalDeviceVulkan14Features enable14 = null;
        if (vulkan14) {
            VkPhysicalDeviceVulkan14Features supported14 = VkPhysicalDeviceVulkan14Features.calloc(stack)
                    .sType$Default();
            vkGetPhysicalDeviceFeatures2(physicalDevice,
                    VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(supported14.address()));
            enable14 = VkPhysicalDeviceVulkan14Features.calloc(stack)
                    .sType$Default()
                    .pushDescriptor(supported14.pushDescriptor())
                    .hostImageCopy(supported14.hostImageCopy())
                    .maintenance5(supported14.maintenance5());
            LOGGER.info("Vulkan 1.4 shim features: pushDescriptor={}, hostImageCopy={}, maintenance5={}",
                    supported14.pushDescriptor(), supported14.hostImageCopy(), supported14.maintenance5());
        } else {
            LOGGER.info("Vulkan 1.4 is not available here: push descriptors, host image copy and maintenance5 are "
                    + "off. Nothing uses them yet; the paths that will check the flag when they do.");
        }

        boolean mdi = supported10.multiDrawIndirect();
        boolean firstInstance = supported10.drawIndirectFirstInstance();
        // GL's glLineWidth is real state with a real effect (Immersive Engineering's Tesla coil and its fractal
        // particles draw 4- and 8-pixel lines). Vulkan only honours a width other than 1.0 when the device enables
        // wideLines, so it is asked for rather than assumed - and when it is absent the width is clamped to 1.0
        // instead of being passed to vkCmdSetLineWidth, which the spec forbids.
        boolean wideLines = supported10.wideLines();
        // glPolygonMode(GL_LINE) is how Actinium draws wireframe. Vulkan only honours a polygon mode other than FILL
        // with fillModeNonSolid, and without it a pipeline that asks for LINE fails validation rather than degrading -
        // so the feature is asked for and the answer is kept.
        boolean fillModeNonSolid = supported10.fillModeNonSolid();

        VkPhysicalDeviceFeatures enable10 = VkPhysicalDeviceFeatures.calloc(stack)
                .multiDrawIndirect(mdi)
                .drawIndirectFirstInstance(firstInstance)
                .wideLines(wideLines)
                .fillModeNonSolid(fillModeNonSolid);

        // Enable only what the device reports. Asking for an unsupported feature fails device creation, and the
        // interesting case (a non-conformant implementation such as MoltenVK) should degrade with a named reason.
        VkPhysicalDeviceVulkan12Features enable12 = VkPhysicalDeviceVulkan12Features.calloc(stack)
                .sType$Default()
                .bufferDeviceAddress(supported12.bufferDeviceAddress())
                .descriptorIndexing(supported12.descriptorIndexing())
                .shaderSampledImageArrayNonUniformIndexing(supported12.shaderSampledImageArrayNonUniformIndexing())
                .descriptorBindingVariableDescriptorCount(supported12.descriptorBindingVariableDescriptorCount())
                .runtimeDescriptorArray(supported12.runtimeDescriptorArray())
                .drawIndirectCount(supported12.drawIndirectCount());
        VkPhysicalDeviceVulkan13Features enable13 = VkPhysicalDeviceVulkan13Features.calloc(stack)
                .sType$Default()
                .pNext(enable12.address())
                .dynamicRendering(supported13.dynamicRendering())
                .synchronization2(supported13.synchronization2())
                // Needed by the alpha test: GLSL's discard compiles to OpDemoteToHelperInvocation for a Vulkan 1.3
                // target, and a shader module that declares a capability the device did not enable is rejected -
                // which is how this surfaced. The alpha test is what stops a transparent texel from writing its
                // colour, so it is not optional for the GUI.
                .shaderDemoteToHelperInvocation(supported13.shaderDemoteToHelperInvocation());
        if (enable14 != null) {
            enable14.pNext(enable13.address());
        }

        VulkanPipeline.setNonSolidFill(fillModeNonSolid);
        reportMissing("multiDrawIndirect", mdi);
        reportMissing("drawIndirectFirstInstance", firstInstance);
        if (!wideLines) {
            LOGGER.info("wideLines is not supported: glLineWidth is clamped to 1.0, which is the only width this"
                    + " device rasterises");
        }
        if (!fillModeNonSolid) {
            LOGGER.info("fillModeNonSolid is not supported: glPolygonMode is clamped to GL_FILL, which is the only"
                    + " polygon mode this device rasterises");
        }
        reportMissing("drawIndirectCount", supported12.drawIndirectCount());
        reportMissing("bufferDeviceAddress", supported12.bufferDeviceAddress());
        reportMissing("descriptorIndexing", supported12.descriptorIndexing());
        reportMissing("dynamicRendering", supported13.dynamicRendering());
        reportMissing("synchronization2", supported13.synchronization2());
        reportMissing("shaderDemoteToHelperInvocation", supported13.shaderDemoteToHelperInvocation());
        VkDeviceQueueCreateInfo.Buffer queueCreateInfo = VkDeviceQueueCreateInfo.calloc(1, stack);
        queueCreateInfo.get(0)
                .sType$Default()
                .queueFamilyIndex(family)
                .pQueuePriorities(stack.floats(1.0f));

        PointerBuffer extensionNames = stack.pointers(stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME));

        VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack)
                .sType$Default()
                .pNext(enable14 != null ? enable14.address() : enable13.address())
                .pEnabledFeatures(enable10)
                .pQueueCreateInfos(queueCreateInfo)
                .ppEnabledExtensionNames(extensionNames);

        PointerBuffer deviceOut = stack.mallocPointer(1);
        VkCheck.check(vkCreateDevice(physicalDevice, createInfo, null, deviceOut), "vkCreateDevice");
        return new DeviceResult(new VkDevice(deviceOut.get(0), physicalDevice, createInfo), mdi, firstInstance,
                fillModeNonSolid);
    }

    private static void reportMissing(String feature, boolean supported) {
        if (!supported) {
            LOGGER.warn("Vulkan feature '{}' is not supported by this device; the backend will have to fall back "
                    + "for the paths that need it", feature);
        }
    }

    private static VkQueue queue(VkDevice device, int family, MemoryStack stack) {
        PointerBuffer queueOut = stack.mallocPointer(1);
        vkGetDeviceQueue(device, family, 0, queueOut);
        return new VkQueue(queueOut.get(0), device);
    }

    // The three enumeration helpers below allocate their own buffers instead of taking the caller's stack frame.
    // A driver can report a couple of hundred device extensions, which is ~50 KB of VkExtensionProperties - enough
    // to exhaust LWJGL's default 64 KB stack frame when the caller is already holding one.

    private static boolean hasLayer(String name) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            vkEnumerateInstanceLayerProperties(count, null);
            VkLayerProperties.Buffer available = VkLayerProperties.malloc(count.get(0));
            try {
                vkEnumerateInstanceLayerProperties(count, available);
                return contains(available, name);
            } finally {
                available.free();
            }
        }
    }

    private static boolean hasInstanceExtension(String name) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            vkEnumerateInstanceExtensionProperties((String) null, count, null);
            VkExtensionProperties.Buffer available = VkExtensionProperties.malloc(count.get(0));
            try {
                vkEnumerateInstanceExtensionProperties((String) null, count, available);
                return contains(available, name);
            } finally {
                available.free();
            }
        }
    }

    private static boolean hasDeviceExtension(VkPhysicalDevice device, String name) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            vkEnumerateDeviceExtensionProperties(device, (String) null, count, null);
            VkExtensionProperties.Buffer available = VkExtensionProperties.malloc(count.get(0));
            try {
                vkEnumerateDeviceExtensionProperties(device, (String) null, count, available);
                return contains(available, name);
            } finally {
                available.free();
            }
        }
    }

    private static boolean contains(VkExtensionProperties.Buffer available, String name) {
        for (int i = 0; i < available.capacity(); i++) {
            if (name.equals(available.get(i).extensionNameString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(VkLayerProperties.Buffer available, String name) {
        for (int i = 0; i < available.capacity(); i++) {
            if (name.equals(available.get(i).layerNameString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether validation layers were asked for.
     *
     * <p><b>On by default on this branch.</b> Validation is the only thing that catches misuse which happens to
     * work on one driver; the reference material is emphatic that it belongs on while developing. Like the backend
     * priority, this has to be revisited before merge: shipping it enabled would cost every player performance
     * for a check aimed at us.</p>
     */
    /**
     * Whether the Khronos validation layer runs.
     *
     * <p><b>Off by default, because it costs about as much as the rendering does.</b> Every command goes through the
     * layer's state tracking, and a frame of a thousand draws measured 2 us a draw with it active - against the few
     * hundred nanoseconds a bare {@code vkCmdDrawIndexed} should take. A performance figure taken with the layer on
     * is a figure about the layer. It is still the right thing to run while changing the backend, so
     * {@code -Doutlands.render.vulkan.validation=true} turns it back on and the renderer's line reports which mode a
     * measurement was taken in.</p>
     */
    private static boolean validationRequested() {
        return Boolean.parseBoolean(System.getProperty("outlands.render.vulkan.validation", "false"));
    }

    /** Whether the validation layer is active, for a measurement to say which mode it was taken in. */
    public static boolean validationActive() {
        return validationActive;
    }
}
