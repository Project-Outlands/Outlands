package com.cleanroommc.lwjgl.legacy;

import java.nio.ByteBuffer;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * Allocation target for {@code new PointerBuffer(ByteBuffer)}, whose LWJGL3 equivalent is the protected
 * {@code (address, container, mark, position, limit, capacity)} constructor behind {@code PointerBuffer.create}.
 */
public final class ShimPointerBuffer extends PointerBuffer {

    public ShimPointerBuffer(ByteBuffer buffer) {
        super(MemoryUtil.memAddress(buffer), buffer, -1, 0, buffer.remaining() >> 3, buffer.remaining() >> 3);
    }
}
