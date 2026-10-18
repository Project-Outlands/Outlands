package org.lwjgl.opengl;

import org.lwjgl.PointerWrapperAbstract;

/**
 * LWJGL2's callback object for {@code AMD_debug_output}. LWJGL3 replaced it with
 * {@link GLDebugMessageAMDCallback}, so this class only carries the handler that the adapter bridges.
 */
public class AMDDebugOutputCallback extends PointerWrapperAbstract {

    public static final int GL_DEBUG_SEVERITY_HIGH = AMDDebugOutput.GL_DEBUG_SEVERITY_HIGH_AMD;
    public static final int GL_DEBUG_SEVERITY_MEDIUM = AMDDebugOutput.GL_DEBUG_SEVERITY_MEDIUM_AMD;
    public static final int GL_DEBUG_SEVERITY_LOW = AMDDebugOutput.GL_DEBUG_SEVERITY_LOW_AMD;
    public static final int GL_DEBUG_CATEGORY_API_ERROR = AMDDebugOutput.GL_DEBUG_CATEGORY_API_ERROR_AMD;
    public static final int GL_DEBUG_CATEGORY_WINDOW_SYSTEM = AMDDebugOutput.GL_DEBUG_CATEGORY_WINDOW_SYSTEM_AMD;
    public static final int GL_DEBUG_CATEGORY_DEPRECATION = AMDDebugOutput.GL_DEBUG_CATEGORY_DEPRECATION_AMD;
    public static final int GL_DEBUG_CATEGORY_UNDEFINED_BEHAVIOR = AMDDebugOutput.GL_DEBUG_CATEGORY_UNDEFINED_BEHAVIOR_AMD;
    public static final int GL_DEBUG_CATEGORY_PERFORMANCE = AMDDebugOutput.GL_DEBUG_CATEGORY_PERFORMANCE_AMD;
    public static final int GL_DEBUG_CATEGORY_SHADER_COMPILER = AMDDebugOutput.GL_DEBUG_CATEGORY_SHADER_COMPILER_AMD;
    public static final int GL_DEBUG_CATEGORY_APPLICATION = AMDDebugOutput.GL_DEBUG_CATEGORY_APPLICATION_AMD;
    public static final int GL_DEBUG_CATEGORY_OTHER = AMDDebugOutput.GL_DEBUG_CATEGORY_OTHER_AMD;

    private final Handler handler;

    public AMDDebugOutputCallback() {
        this(new Handler() {
            @Override
            public void handleMessage(int id, int category, int severity, String message) {
                System.err.println("[LWJGL2] AMD_debug_output 0x" + Integer.toHexString(id) + ": " + message);
            }
        });
    }

    public AMDDebugOutputCallback(Handler handler) {
        super(0L);
        this.handler = handler;
    }

    public Handler getHandler() {
        return handler;
    }

    /** Implementations of this interface can be used to receive AMD_debug_output notifications. */
    public interface Handler {

        void handleMessage(int id, int category, int severity, String message);
    }
}
