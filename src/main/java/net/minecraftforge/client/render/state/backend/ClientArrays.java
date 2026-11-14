package net.minecraftforge.client.render.state.backend;

import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraftforge.client.render.RenderBackendSelection;
import net.minecraftforge.client.render.vertex.DrawState;
import net.minecraftforge.client.render.vertex.FrameDraws;
import net.minecraftforge.client.render.vertex.VertexConverter;
import net.minecraftforge.client.render.vertex.VertexLayout;

import org.jspecify.annotations.Nullable;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/**
 * Fixed-function client arrays, assembled into a draw when {@code glDrawArrays} arrives.
 *
 * <p><b>Why the declaration cannot be dropped.</b> {@code GlStateManager} treats these calls as vertex-input state a
 * pipeline backend has no use for and ignores them, which is right for vanilla - it never uses client arrays. A mod
 * does: Immersive Intelligence's {@code TmtTessellator} points four arrays at one 40-byte-stride buffer, enables
 * them, calls {@code glDrawArrays}, and disables them again, ten thousand times a frame. Ignoring the pointers
 * leaves the draw with no vertices, and the model disappears without an error.</p>
 *
 * <p><b>Where the bytes come from.</b> Two places, and both are measured. The mods in the pack upload into a buffer
 * object and pass offsets ({@code glVertexPointer(3, GL_FLOAT, 40, 0L)}), or they pass a client-memory buffer whose
 * position is the offset ({@code floatBuffer.position(0); glVertexPointer(3, 40, floatBuffer)}). A buffer object's
 * contents come from {@link BufferObjects}; client memory is read straight from the buffer, at draw time, because
 * that is when GL reads it.</p>
 *
 * <p><b>What the assembler writes.</b> The canonical {@link VertexConverter} layout - position, colour, UV, lightmap
 * - for the same reason the immediate-mode path writes it: one pipeline, one shader, and the arrays' own layout
 * never reaches the renderer. Components the arrays do not carry take the neutral value (current colour, zero UV,
 * full brightness), which is what GL's fixed-function pipeline substituted.</p>
 */
public final class ClientArrays {

    /** {@code GL_VERTEX_ARRAY}. */
    public static final int VERTEX_ARRAY = 32884;
    /** {@code GL_NORMAL_ARRAY}. */
    public static final int NORMAL_ARRAY = 32885;
    /** {@code GL_COLOR_ARRAY}. */
    public static final int COLOR_ARRAY = 32886;
    /** {@code GL_TEXTURE_COORD_ARRAY}. */
    public static final int TEXTURE_COORD_ARRAY = 32888;

    private static final int GL_TEXTURE0 = 33984;
    /** Vanilla has two client texture units and the pack uses exactly those. */
    private static final int MAX_UNITS = 8;

    private static final int GL_BYTE = 5120;
    private static final int GL_UNSIGNED_BYTE = 5121;
    private static final int GL_SHORT = 5122;
    private static final int GL_UNSIGNED_SHORT = 5123;
    private static final int GL_INT = 5124;
    private static final int GL_UNSIGNED_INT = 5125;
    private static final int GL_FLOAT = 5126;
    private static final int GL_DOUBLE = 5130;

    private static final int COLOR_OFFSET = 12;
    private static final int UV_OFFSET = 16;
    private static final int LIGHTMAP_OFFSET = 24;

    /**
     * One array's declaration.
     *
     * @param size     components per vertex, as GL's {@code size}
     * @param type     a GL type constant; the two-int overloads leave it 0 and the buffer decides
     * @param stride   bytes between vertices; 0 means tightly packed
     * @param client     the client-memory buffer, or {@code null} when the data is in a buffer object
     * @param clientBase byte address of the first vertex inside that buffer, taken from its position when the
     *                   pointer was declared
     * @param offset     byte offset into the bound buffer object, when {@code client} is {@code null}
     */
    private record Pointer(int size, int type, int stride, @Nullable Buffer client, int clientBase, long offset) {}

    /**
     * The byte address a client-memory pointer was declared with.
     *
     * <p><b>Why it has to be captured here.</b> GL takes an address, not a buffer: {@code glVertexPointer(3, 40,
     * floatBuffer)} reads {@code floatBuffer.position()} once, at that call. The mods reuse one buffer object for
     * every array - {@code TmtTessellator} points the vertex array at position 0 and the texcoord array at position
     * 3 of the same {@code FloatBuffer} - so reading the position at draw time gives every array the last one's
     * address, and the positions come out as texture coordinates.</p>
     */
    private static int baseOf(Buffer buffer) {
        if (buffer instanceof FloatBuffer || buffer instanceof IntBuffer) {
            return buffer.position() * 4;
        }
        if (buffer instanceof ShortBuffer) {
            return buffer.position() * 2;
        }
        return buffer.position();
    }

    private final Pointer[] texCoords = new Pointer[MAX_UNITS];
    private final boolean[] texCoordEnabled = new boolean[MAX_UNITS];
    private @Nullable Pointer vertex;
    private @Nullable Pointer color;
    private @Nullable Pointer normal;
    private boolean vertexEnabled;
    private boolean colorEnabled;
    private boolean normalEnabled;
    private int clientUnit;

    /** Scratch for the vertex being assembled, so a draw does not allocate per vertex. */
    private final float[] component = new float[4];

    ClientArrays() {}

    /** The state for a renderer that is not OpenGL, or {@code null} when the vanilla GL path applies. */
    public static @Nullable ClientArrays forNonGl() {
        return RenderBackendSelection.glPath() ? null : FrameStates.clientArrays();
    }

    /** Enables one array, the way {@code glEnableClientState} does. */
    public void enable(int array) {
        switch (array) {
            case VERTEX_ARRAY -> vertexEnabled = true;
            case COLOR_ARRAY -> colorEnabled = true;
            case NORMAL_ARRAY -> normalEnabled = true;
            case TEXTURE_COORD_ARRAY -> texCoordEnabled[clientUnit] = true;
            default -> FrameStates.reportSkippedOnce("glEnableClientState(" + array + ")",
                    "the array is outside the fixed-function set this backend assembles");
        }
    }

    /** Disables one array. */
    public void disable(int array) {
        switch (array) {
            case VERTEX_ARRAY -> vertexEnabled = false;
            case COLOR_ARRAY -> colorEnabled = false;
            case NORMAL_ARRAY -> normalEnabled = false;
            case TEXTURE_COORD_ARRAY -> texCoordEnabled[clientUnit] = false;
            default -> FrameStates.reportSkippedOnce("glDisableClientState(" + array + ")",
                    "the array is outside the fixed-function set this backend assembles");
        }
    }

    /**
     * {@code glClientActiveTexture}: which unit the next {@code glTexCoordPointer} applies to.
     *
     * <p>Vanilla's patch drops this call today, and with it the distinction between the texture UV and the lightmap:
     * Immersive Intelligence points the unit-1 texcoord array at the lightmap shorts and unit 0 at the UV floats,
     * so collapsing the two units would give every model the lightmap as its texture coordinates.</p>
     */
    public void clientActiveTexture(int glTexture) {
        int unit = glTexture - GL_TEXTURE0;
        if (unit < 0 || unit >= MAX_UNITS) {
            FrameStates.reportSkippedOnce("glClientActiveTexture(" + glTexture + ")",
                    "the unit is outside the range this backend tracks");
            return;
        }
        clientUnit = unit;
    }

    /** {@code glVertexPointer} with a buffer offset. */
    public void vertexPointer(int size, int type, int stride, long offset) {
        vertex = new Pointer(size, type, stride, null, 0, offset);
    }

    /** {@code glVertexPointer} with a client-memory buffer, whose type follows the buffer. */
    public void vertexPointer(int size, int stride, FloatBuffer buffer) {
        vertex = new Pointer(size, typeOf(buffer), stride, buffer, baseOf(buffer), 0L);
    }

    /** {@code glVertexPointer} with a client-memory buffer and an explicit component type. */
    public void vertexPointer(int size, int type, int stride, ByteBuffer buffer) {
        vertex = new Pointer(size, type, stride, buffer, baseOf(buffer), 0L);
    }

    /** {@code glColorPointer} with a buffer offset. */
    public void colorPointer(int size, int type, int stride, long offset) {
        color = new Pointer(size, type, stride, null, 0, offset);
    }

    /** {@code glColorPointer} with a client-memory buffer and an explicit component type. */
    public void colorPointer(int size, int type, int stride, ByteBuffer buffer) {
        color = new Pointer(size, type, stride, buffer, baseOf(buffer), 0L);
    }

    /** {@code glColorPointer} with a client-memory buffer. */
    public void colorPointer(int size, boolean normalized, int stride, ByteBuffer buffer) {
        color = new Pointer(size, normalized ? GL_UNSIGNED_BYTE : GL_BYTE, stride, buffer, baseOf(buffer), 0L);
    }

    /** {@code glTexCoordPointer} with a buffer offset. */
    public void texCoordPointer(int size, int type, int stride, long offset) {
        texCoords[clientUnit] = new Pointer(size, type, stride, null, 0, offset);
    }

    /** {@code glTexCoordPointer} with a client-memory buffer and an explicit component type. */
    public void texCoordPointer(int size, int type, int stride, ByteBuffer buffer) {
        texCoords[clientUnit] = new Pointer(size, type, stride, buffer, baseOf(buffer), 0L);
    }

    /** {@code glTexCoordPointer} with a client-memory float buffer. */
    public void texCoordPointer(int size, int stride, FloatBuffer buffer) {
        texCoords[clientUnit] = new Pointer(size, GL_FLOAT, stride, buffer, baseOf(buffer), 0L);
    }

    /** {@code glTexCoordPointer} with a client-memory short buffer, which is how the lightmap is declared. */
    public void texCoordPointer(int size, int stride, ShortBuffer buffer) {
        texCoords[clientUnit] = new Pointer(size, GL_SHORT, stride, buffer, baseOf(buffer), 0L);
    }

    /** {@code glNormalPointer} with a buffer offset. */
    public void normalPointer(int type, int stride, long offset) {
        normal = new Pointer(3, type, stride, null, 0, offset);
    }

    /** {@code glNormalPointer} with a client-memory buffer. */
    public void normalPointer(int type, int stride, ByteBuffer buffer) {
        normal = new Pointer(3, type, stride, buffer, baseOf(buffer), 0L);
    }

    /**
     * {@code glDrawArrays}: assembles the enabled arrays into one draw.
     *
     * @return whether a draw was submitted; {@code false} means there was no vertex array to read from
     */
    public boolean drawArrays(int mode, int first, int count) {
        Pointer positions = vertex;
        if (!vertexEnabled || positions == null || count <= 0) {
            FrameStates.reportSkippedOnce("glDrawArrays without a vertex array",
                    "no vertex array is enabled, so the draw has no positions to read");
            return false;
        }
        int stride = strideOf(positions);
        byte[] out = new byte[count * VertexConverter.STRIDE];
        ByteBuffer view = ByteBuffer.wrap(out).order(ByteOrder.nativeOrder());
        float[] currentColor = DrawState.current().color();
        for (int i = 0; i < count; i++) {
            int vertexIndex = first + i;
            int at = i * VertexConverter.STRIDE;
            read(positions, vertexIndex, stride, 3);
            view.putFloat(at, component[0]);
            view.putFloat(at + 4, component[1]);
            view.putFloat(at + 8, component[2]);

            if (colorEnabled && color != null) {
                read(color, vertexIndex, strideOf(color), 4);
                view.put(at + COLOR_OFFSET, (byte) clampByte(component[0]));
                view.put(at + COLOR_OFFSET + 1, (byte) clampByte(component[1]));
                view.put(at + COLOR_OFFSET + 2, (byte) clampByte(component[2]));
                view.put(at + COLOR_OFFSET + 3, (byte) clampByte(component[3]));
            } else {
                // No colour array: GL uses the current colour, which is what the state carries.
                view.put(at + COLOR_OFFSET, (byte) clampByte(currentColor[0]));
                view.put(at + COLOR_OFFSET + 1, (byte) clampByte(currentColor[1]));
                view.put(at + COLOR_OFFSET + 2, (byte) clampByte(currentColor[2]));
                view.put(at + COLOR_OFFSET + 3, (byte) clampByte(currentColor[3]));
            }

            Pointer uv = texCoordEnabled[0] ? texCoords[0] : null;
            if (uv != null) {
                read(uv, vertexIndex, strideOf(uv), 2);
                view.putFloat(at + UV_OFFSET, component[0]);
                view.putFloat(at + UV_OFFSET + 4, component[1]);
            }

            Pointer lightmap = texCoordEnabled[1] ? texCoords[1] : null;
            if (lightmap != null) {
                read(lightmap, vertexIndex, strideOf(lightmap), 2);
                view.putShort(at + LIGHTMAP_OFFSET, (short) lightmapBits(component[0]));
                view.putShort(at + LIGHTMAP_OFFSET + 2, (short) lightmapBits(component[1]));
            } else {
                view.putShort(at + LIGHTMAP_OFFSET, (short) 0xFFFF);
                view.putShort(at + LIGHTMAP_OFFSET + 2, (short) 0xFFFF);
            }
        }
        VertexLayout layout = VertexLayout.of(DefaultVertexFormats.BLOCK);
        // A display list being compiled keeps the bytes instead of drawing them, exactly as the stream path does.
        if (DisplayLists.capture(layout, mode, count, view)) {
            return true;
        }
        if (FrameDraws.record(layout, mode, count, view, DrawState.current())) {
            return true;
        }
        throw new IllegalStateException("client-array draw of " + count + " vertices arrived while no frame was"
                + " collecting draws; it would have been dropped");
    }

    /** Forgets every declaration, as a context loss would. */
    public void reset() {
        java.util.Arrays.fill(texCoords, null);
        java.util.Arrays.fill(texCoordEnabled, false);
        vertex = null;
        color = null;
        normal = null;
        vertexEnabled = false;
        colorEnabled = false;
        normalEnabled = false;
        clientUnit = 0;
    }

    /** Whether a normal array is declared, which the assembler records but the canonical layout drops. */
    public boolean hasNormals() {
        return normalEnabled && normal != null;
    }

    private static int strideOf(Pointer pointer) {
        if (pointer.stride() > 0) {
            return pointer.stride();
        }
        return pointer.size() * bytesOf(pointer.type());
    }

    private static int bytesOf(int type) {
        return switch (type) {
            case GL_BYTE, GL_UNSIGNED_BYTE -> 1;
            case GL_SHORT, GL_UNSIGNED_SHORT -> 2;
            case GL_INT, GL_UNSIGNED_INT, GL_FLOAT -> 4;
            case GL_DOUBLE -> 8;
            default -> 4;
        };
    }

    private static int typeOf(Buffer buffer) {
        if (buffer instanceof FloatBuffer) {
            return GL_FLOAT;
        }
        if (buffer instanceof ShortBuffer) {
            return GL_SHORT;
        }
        if (buffer instanceof IntBuffer) {
            return GL_INT;
        }
        if (buffer instanceof ByteBuffer) {
            return GL_UNSIGNED_BYTE;
        }
        return GL_FLOAT;
    }

    /**
     * Reads {@code components} of one vertex into {@link #component}.
     *
     * <p>Values are normalised to 0..1 for the colour path and left as they are otherwise; the caller knows which
     * scale it wants, which is the only difference between reading a position and reading a colour.</p>
     */
    private void read(Pointer pointer, int vertexIndex, int stride, int components) {
        Buffer client = pointer.client();
        if (client != null) {
            readClient(pointer, client, vertexIndex, stride, components);
            return;
        }
        BufferObjects buffers = BufferObjects.instance();
        ByteBuffer source = buffers.view(buffers.bound(BufferObjects.GL_ARRAY_BUFFER));
        if (source == null) {
            FrameStates.reportSkippedOnce("client array pointing into no buffer",
                    "the array was declared with a buffer offset, but no buffer object is bound");
            java.util.Arrays.fill(component, 0.0F);
            return;
        }
        int base = (int) pointer.offset() + vertexIndex * stride;
        for (int i = 0; i < components; i++) {
            component[i] = decode(source, base + i * bytesOf(pointer.type()), pointer.type());
        }
    }

    private void readClient(Pointer pointer, Buffer client, int vertexIndex, int stride, int components) {
        if (client instanceof ByteBuffer bytes) {
            int base = pointer.clientBase() + vertexIndex * stride;
            for (int i = 0; i < components; i++) {
                component[i] = decode(bytes, base + i * bytesOf(pointer.type()), pointer.type());
            }
            return;
        }
        if (client instanceof FloatBuffer floats) {
            // The stride is in bytes and the buffer is in floats, so the stride has to be converted: a 40-byte
            // stride is 10 floats, and reading it as 40 would walk off the end of the buffer.
            int base = pointer.clientBase() / 4 + (vertexIndex * stride) / 4;
            for (int i = 0; i < components; i++) {
                component[i] = floats.get(base + i);
            }
            return;
        }
        if (client instanceof ShortBuffer shorts) {
            int base = pointer.clientBase() / 2 + (vertexIndex * stride) / 2;
            for (int i = 0; i < components; i++) {
                component[i] = shorts.get(base + i);
            }
            return;
        }
        if (client instanceof IntBuffer ints) {
            int base = pointer.clientBase() / 4 + (vertexIndex * stride) / 4;
            for (int i = 0; i < components; i++) {
                component[i] = ints.get(base + i);
            }
            return;
        }
        FrameStates.reportSkippedOnce("client array of an unsupported buffer kind",
                "only byte, short, int and float client arrays are read");
        java.util.Arrays.fill(component, 0.0F);
    }

    private static float decode(ByteBuffer source, int at, int type) {
        if (at < 0 || at + bytesOf(type) > source.limit()) {
            // A draw past the end of the array reads zeros rather than throwing from inside a render loop.
            return 0.0F;
        }
        return switch (type) {
            case GL_FLOAT -> source.getFloat(at);
            case GL_UNSIGNED_BYTE -> (source.get(at) & 0xFF) / 255.0F;
            case GL_BYTE -> source.get(at) / 127.0F;
            case GL_SHORT -> source.getShort(at);
            case GL_UNSIGNED_SHORT -> source.getShort(at) & 0xFFFF;
            case GL_INT -> source.getInt(at);
            case GL_UNSIGNED_INT -> (float) (source.getInt(at) & 0xFFFF_FFFFL);
            case GL_DOUBLE -> (float) source.getDouble(at);
            default -> 0.0F;
        };
    }

    private static int clampByte(float value) {
        int scaled = Math.round(value * 255.0F);
        return Math.max(0, Math.min(255, scaled));
    }

    /**
     * The 16 bits a lightmap coordinate is made of.
     *
     * <p><b>Why the sign has to be undone.</b> The lightmap is two unsigned 16-bit coordinates, but every way of
     * declaring the array hands them back signed: {@code GL_SHORT} and a {@code ShortBuffer} both return -25924 for
     * 0x9ABC. Clamping that to zero gives every vertex in the second half of the lightmap range a black lightmap,
     * which reads as "the model is unlit" rather than as an arithmetic mistake. The bits are what the sampler reads,
     * so the bits are what is written.</p>
     */
    private static int lightmapBits(float value) {
        int rounded = Math.round(value);
        return rounded < 0 ? rounded & 0xFFFF : Math.min(rounded, 0xFFFF);
    }
}
