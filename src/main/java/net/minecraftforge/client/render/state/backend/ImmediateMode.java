package net.minecraftforge.client.render.state.backend;

import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraftforge.client.render.vertex.FrameDraws;
import net.minecraftforge.client.render.vertex.VertexLayout;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Accumulates the vertices between {@code glBegin} and {@code glEnd} and hands them on as one draw.
 *
 * <p><b>What immediate mode actually is.</b> Not a primitive the hardware has - it is a client-side buffer that GL
 * fills one vertex at a time and flushes at {@code glEnd}. Vulkan has no equivalent because it does not need one:
 * the same thing is "build the vertices, then one draw call".</p>
 *
 * <p><b>Only one caller in the game.</b> {@code FontRenderer} draws glyph quads this way, and it is the only place
 * vanilla still uses it - the rest of the game goes through {@code Tessellator}. So this covers text and nothing
 * else, which is why it is a small accumulator rather than a general command recorder.</p>
 *
 * <p><b>What it writes.</b> The canonical layout, directly - no conversion step, because there is no input format
 * to convert from. Attributes vanilla sets but the layout has no room for (normals) are accepted and dropped;
 * directional shading is baked into the vertex colour before it reaches here, and fixed-function lighting is not
 * implemented, so a normal would have nothing to act on.</p>
 */
public final class ImmediateMode {

    /** The canonical layout: what {@code VertexConverter} produces and the terrain shader consumes. */
    private static final int STRIDE = 28;

    private int mode = -1;
    private byte[] vertices = new byte[256 * STRIDE];
    private int vertexCount;
    private float texU;
    private float texV;

    /** Whether a {@code glBegin} is open. */
    public boolean isActive() {
        return mode >= 0;
    }

    /**
     * Starts accumulating.
     *
     * @throws IllegalStateException if a begin is already open, which GL also rejects
     */
    public void begin(int glMode) {
        if (mode >= 0) {
            throw new IllegalStateException("glBegin(" + mode + ") is already open");
        }
        mode = glMode;
        vertexCount = 0;
        texU = 0.0F;
        texV = 0.0F;
    }

    public void texCoord(float u, float v) {
        texU = u;
        texV = v;
    }

    /**
     * Adds one vertex with the current texture coordinate and the frame state's current colour.
     *
     * <p>The colour is read here rather than passed in because that is where vanilla puts it:
     * {@code GlStateManager.color(...)} is called once and applies to every vertex until changed.</p>
     */
    public void vertex(float x, float y, float z, float[] color) {
        if (mode < 0) {
            throw new IllegalStateException("glVertex3f outside glBegin");
        }
        int base = vertexCount * STRIDE;
        if (base + STRIDE > vertices.length) {
            vertices = Arrays.copyOf(vertices, Math.max(vertices.length * 2, base + STRIDE));
        }
        ByteBuffer out = ByteBuffer.wrap(vertices).order(ByteOrder.nativeOrder());
        out.putFloat(base, x);
        out.putFloat(base + 4, y);
        out.putFloat(base + 8, z);
        out.put(base + 12, toByte(color[0]));
        out.put(base + 13, toByte(color[1]));
        out.put(base + 14, toByte(color[2]));
        out.put(base + 15, toByte(color[3]));
        out.putFloat(base + 16, texU);
        out.putFloat(base + 20, texV);
        // Full brightness: immediate-mode geometry has no lightmap, and the shader would otherwise multiply by zero.
        out.putShort(base + 24, (short) 0xFFFF);
        out.putShort(base + 26, (short) 0xFFFF);
        vertexCount++;
    }

    /**
     * Finishes and submits the accumulated draw.
     *
     * @return the number of vertices submitted, which is zero when nothing was drawn
     */
    public int end() {
        if (mode < 0) {
            throw new IllegalStateException("glEnd without glBegin");
        }
        int submitted = vertexCount;
        int submittedMode = mode;
        mode = -1;
        vertexCount = 0;
        if (submitted == 0) {
            return 0;
        }
        ByteBuffer data = ByteBuffer.wrap(vertices, 0, submitted * STRIDE).order(ByteOrder.nativeOrder());
        // Straight into the frame's draw list: it is already in the canonical layout, so no conversion follows.
        // A false return means no frame was collecting, and dropping the draw silently is exactly the failure mode
        // this project keeps rejecting - it looks like missing text with no explanation.
        if (!FrameDraws.record(VertexLayout.of(DefaultVertexFormats.BLOCK), submittedMode, submitted, data,
                net.minecraftforge.client.render.vertex.DrawState.current())) {
            throw new IllegalStateException("immediate-mode draw of " + submitted + " vertices arrived while no frame"
                    + " was collecting draws; it would have been dropped");
        }
        return submitted;
    }

    /** Discards an open block without submitting, for teardown. */
    public void reset() {
        mode = -1;
        vertexCount = 0;
    }

    private static byte toByte(float value) {
        int scaled = (int) (value * 255.0F);
        return (byte) Math.max(0, Math.min(255, scaled));
    }
}
