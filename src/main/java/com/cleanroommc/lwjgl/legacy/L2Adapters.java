package com.cleanroommc.lwjgl.legacy;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL20;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.system.MemoryStack;

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
}
