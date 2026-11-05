package net.minecraftforge.lwjgl.shim;

/**
 * What the ruleset chooser is allowed to look at.
 *
 * <p>Deliberately small: a probe that queries drivers is fine, one that creates a device is not, because the
 * choice has to happen before the window exists.</p>
 */
public interface ShimEnvironment {

    /** The backend selected for this process, by id. Never null. */
    String backendId();

    /**
     * Whether a Vulkan loader and at least one physical device were visible.
     *
     * <p>This is a hint, not a guarantee: it says nothing about whether the device supports what the backend
     * needs. A ruleset may use it to avoid rewriting for a backend that will immediately fall back.</p>
     */
    boolean vulkanInstanceAvailable();

    /** The GL version string, or an empty string when no GL context exists. */
    String glVersionString();
}
