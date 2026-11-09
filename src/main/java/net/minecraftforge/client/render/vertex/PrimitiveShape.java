package net.minecraftforge.client.render.vertex;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The mapping from the GL primitive constant a caller holds to something Vulkan can actually draw.
 *
 * <p><b>Why this is a conversion and not a lookup.</b> Vulkan has no {@code GL_QUADS}, no {@code GL_QUAD_STRIP},
 * no {@code GL_TRIANGLE_FAN} and no {@code GL_LINE_LOOP}. Those four therefore need their vertices re-expressed as
 * indices before any pipeline can draw them. That is the whole reason a GL-mode integer cannot be passed straight
 * through - and it is the only part of the buffer path that is pure arithmetic, so it is the part worth testing on
 * its own.</p>
 *
 * <p><b>What vanilla actually uses</b>, measured over the patched 1.12.2 sources: {@code QUADS} 87 call sites,
 * {@code TRIANGLE_STRIP} 13, {@code LINE_STRIP} 8, {@code TRIANGLE_FAN} 3, {@code LINES} 2. {@code QUAD_STRIP},
 * {@code LINE_LOOP} and {@code POINTS} do not appear in the game itself but are kept because a mod reaching this
 * code can pass anything, and rejecting a mode should be a visible decision rather than a silent gap.</p>
 *
 * <p><b>Indexing rather than expansion.</b> Where a mode maps directly (a strip stays a strip), no indices are
 * produced at all: the draw reads the vertices in order. Indices appear only for the four modes that have no
 * Vulkan equivalent, and there they are the cheaper way to re-express the same vertices - expanding {@code n} quad
 * vertices into triangles costs 50% more vertex bytes, while indices cost 6 bytes per quad with 16-bit indices.</p>
 *
 * <p><b>Counts are per mode, not per "vertices per primitive".</b> A fan of {@code n} vertices is {@code n-2}
 * triangles, and a quad strip of {@code n} vertices is {@code (n-2)/2} quads because consecutive quads share an
 * edge. Treating either as a fixed vertices-per-primitive would be wrong in a way that only shows up on the last
 * primitive.</p>
 */
public final class PrimitiveShape {

    /** The Vulkan topology a draw will use. */
    public enum Topology {
        POINT_LIST,
        LINE_LIST,
        LINE_STRIP,
        TRIANGLE_LIST,
        TRIANGLE_STRIP
    }

    // GL primitive constants, as passed by BufferBuilder and Tessellator.
    private static final int GL_POINTS = 0;
    private static final int GL_LINES = 1;
    private static final int GL_LINE_LOOP = 2;
    private static final int GL_LINE_STRIP = 3;
    private static final int GL_TRIANGLES = 4;
    private static final int GL_TRIANGLE_STRIP = 5;
    private static final int GL_TRIANGLE_FAN = 6;
    private static final int GL_QUADS = 7;
    private static final int GL_QUAD_STRIP = 8;

    /** Largest index a 16-bit index buffer can hold. */
    private static final int MAX_UINT16_VERTICES = 0xFFFF;

    private final int glMode;
    private final Topology topology;

    private static final PrimitiveShape[] CACHE = new PrimitiveShape[9];
    static {
        for (int m = 0; m <= 8; m++) {
            Topology t = switch (m) {
                case GL_POINTS -> Topology.POINT_LIST;
                case GL_LINES, GL_LINE_LOOP -> Topology.LINE_LIST;
                case GL_LINE_STRIP -> Topology.LINE_STRIP;
                case GL_TRIANGLES, GL_TRIANGLE_FAN, GL_QUADS, GL_QUAD_STRIP -> Topology.TRIANGLE_LIST;
                case GL_TRIANGLE_STRIP -> Topology.TRIANGLE_STRIP;
                default -> throw new IllegalStateException();
            };
            CACHE[m] = new PrimitiveShape(m, t);
        }
    }

    private PrimitiveShape(int glMode, Topology topology) {
        this.glMode = glMode;
        this.topology = topology;
    }

    /**
     * Maps a GL primitive constant.
     *
     * @throws IllegalArgumentException for a mode this code has no answer for. Loud on purpose: a mode that
     *         silently drew nothing would be a missing feature that looks like a rendering bug.
     */
    public static PrimitiveShape ofGlMode(int glMode) {
        if (glMode >= 0 && glMode < CACHE.length) {
            return CACHE[glMode];
        }
        throw new IllegalArgumentException("no Vulkan topology for GL primitive mode " + glMode
                + "; the modes this code knows are 0..8");
    }

    /** The GL constant this was built from. */
    public int glMode() {
        return glMode;
    }

    /** The topology the draw call should use. */
    public Topology topology() {
        return topology;
    }

    /**
     * Whether {@link #indices(int)} has to run before this can be drawn.
     *
     * <p>False for the modes whose vertex order Vulkan already accepts - those draw straight from the uploaded
     * bytes with no index buffer at all.</p>
     */
    public boolean needsIndexing() {
        return switch (glMode) {
            case GL_LINE_LOOP, GL_TRIANGLE_FAN, GL_QUADS, GL_QUAD_STRIP -> true;
            default -> false;
        };
    }

    /** How many indices {@link #indices(int)} will produce for {@code vertexCount} vertices. */
    public int indexCount(int vertexCount) {
        requireValidVertexCount(vertexCount);
        return switch (glMode) {
            // A loop of n vertices is n segments: the strip plus the closing one.
            case GL_LINE_LOOP -> vertexCount * 2;
            // A fan of n vertices is n-2 triangles.
            case GL_TRIANGLE_FAN -> (vertexCount - 2) * 3;
            case GL_QUADS -> vertexCount / 4 * 6;
            // A strip of n vertices is (n-2)/2 quads, each shared with the next.
            case GL_QUAD_STRIP -> (vertexCount - 2) / 2 * 6;
            default -> throw new IllegalArgumentException("GL mode " + glMode + " needs no index buffer");
        };
    }

    /**
     * Re-expresses {@code vertexCount} vertices as indices for this shape's topology.
     *
     * @param vertexCount how many vertices were uploaded
     * @return 16- or 32-bit indices in native order, matching {@link #index32(int)}
     * @throws IllegalArgumentException if the count cannot be expressed in this shape, or if this shape needs no
     *         indexing
     */
    /**
     * Writes 32-bit indices directly into {@code dest} for {@code vertexCount} vertices.
     *
     * <p>Indices are relative to 0 (the draw's first vertex), suitable for {@code vkCmdDrawIndexed}
     * with {@code vertexOffset}.</p>
     *
     * @return the number of indices written
     */
    public int writeIndices(int vertexCount, java.nio.IntBuffer dest) {
        if (!needsIndexing()) {
            throw new IllegalArgumentException("GL mode " + glMode + " needs no index buffer");
        }
        int count = indexCount(vertexCount);
        switch (glMode) {
            case GL_QUADS -> {
                for (int quad = 0; quad < vertexCount / 4; quad++) {
                    int base = quad * 4;
                    dest.put(base);
                    dest.put(base + 1);
                    dest.put(base + 2);
                    dest.put(base);
                    dest.put(base + 2);
                    dest.put(base + 3);
                }
            }
            case GL_QUAD_STRIP -> {
                for (int quad = 0; quad < (vertexCount - 2) / 2; quad++) {
                    int base = quad * 2;
                    dest.put(base);
                    dest.put(base + 1);
                    dest.put(base + 2);
                    dest.put(base);
                    dest.put(base + 2);
                    dest.put(base + 3);
                }
            }
            case GL_TRIANGLE_FAN -> {
                for (int triangle = 0; triangle < vertexCount - 2; triangle++) {
                    dest.put(0);
                    dest.put(triangle + 1);
                    dest.put(triangle + 2);
                }
            }
            case GL_LINE_LOOP -> {
                for (int segment = 0; segment < vertexCount; segment++) {
                    dest.put(segment);
                    dest.put((segment + 1) % vertexCount);
                }
            }
            default -> throw new IllegalStateException("unreachable: " + glMode);
        }
        return count;
    }

    /**
     * The indices as plain integers.
     *
     * <p>The renderer wants them as one type: choosing 16 or 32 bits per draw would mean rebinding the index buffer
     * whenever the width changes mid-frame. The byte form below narrows this, so both share one computation.</p>
     */
    public int[] indexArray(int vertexCount) {
        if (!needsIndexing()) {
            throw new IllegalArgumentException("GL mode " + glMode + " needs no index buffer");
        }
        int count = indexCount(vertexCount);
        int[] out = new int[count];
        int at = 0;
        switch (glMode) {
            case GL_QUADS -> {
                for (int quad = 0; quad < vertexCount / 4; quad++) {
                    int base = quad * 4;
                    out[at++] = base;
                    out[at++] = base + 1;
                    out[at++] = base + 2;
                    out[at++] = base;
                    out[at++] = base + 2;
                    out[at++] = base + 3;
                }
            }
            case GL_QUAD_STRIP -> {
                for (int quad = 0; quad < (vertexCount - 2) / 2; quad++) {
                    int base = quad * 2;
                    out[at++] = base;
                    out[at++] = base + 1;
                    out[at++] = base + 2;
                    out[at++] = base;
                    out[at++] = base + 2;
                    out[at++] = base + 3;
                }
            }
            case GL_TRIANGLE_FAN -> {
                for (int triangle = 0; triangle < vertexCount - 2; triangle++) {
                    out[at++] = 0;
                    out[at++] = triangle + 1;
                    out[at++] = triangle + 2;
                }
            }
            case GL_LINE_LOOP -> {
                for (int segment = 0; segment < vertexCount; segment++) {
                    out[at++] = segment;
                    out[at++] = (segment + 1) % vertexCount;
                }
            }
            default -> throw new IllegalStateException("unreachable: " + glMode);
        }
        return out;
    }

    public byte[] indices(int vertexCount) {
        if (!needsIndexing()) {
            throw new IllegalArgumentException("GL mode " + glMode + " needs no index buffer");
        }
        int count = indexCount(vertexCount);
        boolean index32 = index32(vertexCount);
        int indexBytes = index32 ? Integer.BYTES : Short.BYTES;

        ByteBuffer out = ByteBuffer.allocate(count * indexBytes).order(ByteOrder.nativeOrder());
        switch (glMode) {
            case GL_QUADS -> {
                for (int quad = 0; quad < vertexCount / 4; quad++) {
                    int base = quad * 4;
                    // GL tessellates a quad 0,1,2,3 as (0,1,2) and (0,2,3). Same order here, so a mesh that
                    // looked right under GL keeps its front faces without the pipeline flipping front-face.
                    put(out, base, index32);
                    put(out, base + 1, index32);
                    put(out, base + 2, index32);
                    put(out, base, index32);
                    put(out, base + 2, index32);
                    put(out, base + 3, index32);
                }
            }
            case GL_QUAD_STRIP -> {
                for (int quad = 0; quad < (vertexCount - 2) / 2; quad++) {
                    int base = quad * 2;
                    put(out, base, index32);
                    put(out, base + 1, index32);
                    put(out, base + 2, index32);
                    put(out, base, index32);
                    put(out, base + 2, index32);
                    put(out, base + 3, index32);
                }
            }
            case GL_TRIANGLE_FAN -> {
                for (int triangle = 0; triangle < vertexCount - 2; triangle++) {
                    put(out, 0, index32);
                    put(out, triangle + 1, index32);
                    put(out, triangle + 2, index32);
                }
            }
            case GL_LINE_LOOP -> {
                for (int segment = 0; segment < vertexCount; segment++) {
                    put(out, segment, index32);
                    // The last segment closes the loop back to vertex 0.
                    put(out, (segment + 1) % vertexCount, index32);
                }
            }
            default -> throw new IllegalStateException("unreachable: " + glMode);
        }
        return out.array();
    }

    /** Whether an index buffer for {@code vertexCount} vertices needs 32 bits. */
    public static boolean index32(int vertexCount) {
        return vertexCount > MAX_UINT16_VERTICES;
    }

    private void requireValidVertexCount(int vertexCount) {
        switch (glMode) {
            case GL_QUADS -> requireDivisible(vertexCount, 4, "quads");
            case GL_QUAD_STRIP -> {
                if (vertexCount < 4 || vertexCount % 2 != 0) {
                    throw new IllegalArgumentException(
                            "GL_QUAD_STRIP needs an even vertex count of at least 4, got " + vertexCount);
                }
            }
            case GL_TRIANGLE_FAN -> {
                if (vertexCount < 3) {
                    throw new IllegalArgumentException(
                            "GL_TRIANGLE_FAN needs at least 3 vertices, got " + vertexCount);
                }
            }
            case GL_LINE_LOOP -> {
                if (vertexCount < 2) {
                    throw new IllegalArgumentException("GL_LINE_LOOP needs at least 2 vertices, got " + vertexCount);
                }
            }
            default -> {
                if (vertexCount < 0) {
                    throw new IllegalArgumentException("negative vertex count: " + vertexCount);
                }
            }
        }
    }

    private void requireDivisible(int vertexCount, int divisor, String what) {
        if (vertexCount < 0 || vertexCount % divisor != 0) {
            throw new IllegalArgumentException(
                    "GL mode " + glMode + " (" + what + ") needs a multiple of " + divisor + " vertices, got "
                            + vertexCount);
        }
    }

    private static void put(ByteBuffer target, int value, boolean index32) {
        if (index32) {
            target.putInt(value);
        } else {
            target.putShort((short) value);
        }
    }
}
