package org.lwjgl.opengl;

import org.lwjgl.PointerWrapperAbstract;

import static org.lwjgl.opengl.ARBDebugOutput.GL_DEBUG_SEVERITY_HIGH_ARB;

/**
 * LWJGL2's callback object for {@code ARB_debug_output}. LWJGL3 replaced it with
 * {@link GLDebugMessageARBCallback}, so this class only carries the handler that the adapter bridges.
 */
public class ARBDebugOutputCallback extends PointerWrapperAbstract {

    public static final int GL_DEBUG_SEVERITY_HIGH = GL_DEBUG_SEVERITY_HIGH_ARB;
    public static final int GL_DEBUG_SEVERITY_MEDIUM = ARBDebugOutput.GL_DEBUG_SEVERITY_MEDIUM_ARB;
    public static final int GL_DEBUG_SEVERITY_LOW = ARBDebugOutput.GL_DEBUG_SEVERITY_LOW_ARB;
    public static final int GL_DEBUG_SOURCE_API = ARBDebugOutput.GL_DEBUG_SOURCE_API_ARB;
    public static final int GL_DEBUG_SOURCE_WINDOW_SYSTEM = ARBDebugOutput.GL_DEBUG_SOURCE_WINDOW_SYSTEM_ARB;
    public static final int GL_DEBUG_SOURCE_SHADER_COMPILER = ARBDebugOutput.GL_DEBUG_SOURCE_SHADER_COMPILER_ARB;
    public static final int GL_DEBUG_SOURCE_THIRD_PARTY = ARBDebugOutput.GL_DEBUG_SOURCE_THIRD_PARTY_ARB;
    public static final int GL_DEBUG_SOURCE_APPLICATION = ARBDebugOutput.GL_DEBUG_SOURCE_APPLICATION_ARB;
    public static final int GL_DEBUG_SOURCE_OTHER = ARBDebugOutput.GL_DEBUG_SOURCE_OTHER_ARB;
    public static final int GL_DEBUG_TYPE_ERROR = ARBDebugOutput.GL_DEBUG_TYPE_ERROR_ARB;
    public static final int GL_DEBUG_TYPE_DEPRECATED_BEHAVIOR = ARBDebugOutput.GL_DEBUG_TYPE_DEPRECATED_BEHAVIOR_ARB;
    public static final int GL_DEBUG_TYPE_UNDEFINED_BEHAVIOR = ARBDebugOutput.GL_DEBUG_TYPE_UNDEFINED_BEHAVIOR_ARB;
    public static final int GL_DEBUG_TYPE_PORTABILITY = ARBDebugOutput.GL_DEBUG_TYPE_PORTABILITY_ARB;
    public static final int GL_DEBUG_TYPE_PERFORMANCE = ARBDebugOutput.GL_DEBUG_TYPE_PERFORMANCE_ARB;
    public static final int GL_DEBUG_TYPE_OTHER = ARBDebugOutput.GL_DEBUG_TYPE_OTHER_ARB;

    private final Handler handler;

    public ARBDebugOutputCallback() {
        this(new Handler() {
            @Override
            public void handleMessage(int source, int type, int id, int severity, String message) {
                System.err.println("[LWJGL2] ARB_debug_output 0x" + Integer.toHexString(id) + ": " + message);
            }
        });
    }

    public ARBDebugOutputCallback(Handler handler) {
        super(0L);
        this.handler = handler;
    }

    public Handler getHandler() {
        return handler;
    }

    /** Implementations of this interface can be used to receive ARB_debug_output notifications. */
    public interface Handler {

        void handleMessage(int source, int type, int id, int severity, String message);
    }
}
