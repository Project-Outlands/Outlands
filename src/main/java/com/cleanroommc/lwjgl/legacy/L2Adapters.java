package com.cleanroommc.lwjgl.legacy;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.AMDDebugOutput;
import org.lwjgl.opengl.AMDDebugOutputCallback;
import org.lwjgl.opengl.ARBDebugOutput;
import org.lwjgl.opengl.ARBDebugOutputCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLDebugMessageAMDCallback;
import org.lwjgl.opengl.GLDebugMessageARBCallback;
import org.lwjgl.opengl.GLDebugMessageCallback;
import org.lwjgl.opengl.GLSync;
import org.lwjgl.opengl.KHRDebug;
import org.lwjgl.opengl.KHRDebugCallback;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.ALCdevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.MemoryUtil.memByteBuffer;
import static org.lwjgl.system.MemoryUtil.memUTF8;

/**
 * Stack-neutral replacements for LWJGL2 members whose declaring class still exists in LWJGL3
 * but no longer carries the member. Every method is static, so a call site only changes owner.
 */
public final class L2Adapters {

    private L2Adapters() {}

    public static ByteBuffer getBuffer(PointerBuffer buffer) {
        return memByteBuffer(memAddress(buffer), buffer.remaining() << 3);
    }

    public static PointerBuffer position(PointerBuffer buffer, int position) {
        return (PointerBuffer) buffer.position(position);
    }

    public static PointerBuffer flip(PointerBuffer buffer) {
        return (PointerBuffer) buffer.flip();
    }

    /** LWJGL3 dropped {@code AL.create()}; this is the standard open-device/create-context/init-capabilities sequence. */
    public static void alCreate() {
        if (ALC10.alcGetCurrentContext() != 0L) {
            return;
        }
        long device = ALC10.alcOpenDevice((ByteBuffer) null);
        if (device == 0L) {
            throw new IllegalStateException("failed to open the default OpenAL device");
        }
        long context = ALC10.alcCreateContext(device, (IntBuffer) null);
        if (context == 0L) {
            ALC10.alcCloseDevice(device);
            throw new IllegalStateException("failed to create an OpenAL context");
        }
        ALC10.alcMakeContextCurrent(context);
        ALCCapabilities capabilities = ALC.createCapabilities(device);
        AL.createCapabilities(capabilities);
    }

    public static boolean isCreated() {
        return ALC10.alcGetCurrentContext() != 0L;
    }

    public static ALCdevice getDevice() {
        return ALCdevice.wrap(ALC10.alcGetContextsDevice(ALC10.alcGetCurrentContext()));
    }

    public static String alcGetString(ALCdevice device, int pname) {
        return ALC10.alcGetString(device.device, pname);
    }

    public static boolean alcIsExtensionPresent(ALCdevice device, String extension) {
        return ALC10.alcIsExtensionPresent(device.device, extension);
    }

    public static void alcGetInteger(ALCdevice device, int pname, IntBuffer data) {
        ALC10.alcGetIntegerv(device.device, pname, data);
    }

    public static int glGetActiveUniformSize(int program, int index) {
        MemoryStack stack = MemoryStack.stackPush();
        try {
            IntBuffer size = stack.ints(0);
            GL20.glGetActiveUniform(program, index, size, stack.ints(0));
            return size.get(0);
        } finally {
            stack.pop();
        }
    }

    public static int glGetActiveUniformType(int program, int index) {
        MemoryStack stack = MemoryStack.stackPush();
        try {
            IntBuffer type = stack.ints(0);
            GL20.glGetActiveUniform(program, index, stack.ints(0), type);
            return type.get(0);
        } finally {
            stack.pop();
        }
    }

    public static void glShaderSource(int shader, ByteBuffer source) {
        GL20.glShaderSource(shader, memUTF8(source, source.remaining()));
    }

    public static String glGetActiveUniform(int program, int index, int maxLength) {
        MemoryStack stack = MemoryStack.stackPush();
        try {
            return GL20.glGetActiveUniform(program, index, maxLength, stack.ints(0), stack.ints(0));
        } finally {
            stack.pop();
        }
    }

    public static String glGetActiveUniform(int program, int index, int maxLength, IntBuffer size) {
        MemoryStack stack = MemoryStack.stackPush();
        try {
            return GL20.glGetActiveUniform(program, index, maxLength, size, stack.ints(0));
        } finally {
            stack.pop();
        }
    }

    public static GLSync glFenceSync(int condition, int flags) {
        return GLSync.wrap(GL32.glFenceSync(condition, flags));
    }

    public static int glGetSynci(GLSync sync, int pname) {
        MemoryStack stack = MemoryStack.stackPush();
        try {
            return GL32.glGetSynci(sync.getPointer(), pname, stack.ints(0));
        } finally {
            stack.pop();
        }
    }

    public static void glWaitSync(GLSync sync, int flags, long timeout) {
        GL32.glWaitSync(sync.getPointer(), flags, timeout);
    }

    public static void glDeleteSync(GLSync sync) {
        GL32.glDeleteSync(sync.getPointer());
    }

    public static int glClientWaitSync(GLSync sync, int flags, long timeout) {
        return GL32.glClientWaitSync(sync.getPointer(), flags, timeout);
    }

    /** The callbacks are kept alive here because the driver only holds the native trampoline address. */
    private static GLDebugMessageCallback khrDebugCallback;
    private static GLDebugMessageARBCallback arbDebugCallback;
    private static GLDebugMessageAMDCallback amdDebugCallback;

    public static void glDebugMessageCallback(KHRDebugCallback callback) {
        khrDebugCallback = GLDebugMessageCallback.create(
                (source, type, id, severity, length, message, userParam) -> callback.getHandler()
                        .handleMessage(source, type, id, severity, text(message, length)));
        if (GL.getCapabilities().OpenGL43) {
            GL43.glDebugMessageCallback(khrDebugCallback, 0L);
        } else {
            KHRDebug.glDebugMessageCallback(khrDebugCallback, 0L);
        }
    }

    public static void glDebugMessageCallbackARB(ARBDebugOutputCallback callback) {
        arbDebugCallback = GLDebugMessageARBCallback.create(
                (source, type, id, severity, length, message, userParam) -> callback.getHandler()
                        .handleMessage(source, type, id, severity, text(message, length)));
        ARBDebugOutput.glDebugMessageCallbackARB(arbDebugCallback, 0L);
    }

    public static void glDebugMessageCallbackAMD(AMDDebugOutputCallback callback) {
        amdDebugCallback = GLDebugMessageAMDCallback.create(
                (id, category, severity, length, message, userParam) -> callback.getHandler()
                        .handleMessage(id, category, severity, text(message, length)));
        AMDDebugOutput.glDebugMessageCallbackAMD(amdDebugCallback, 0L);
    }

    private static String text(long message, int length) {
        return length < 0 ? MemoryUtil.memUTF8(message) : MemoryUtil.memUTF8(message, length);
    }
}
