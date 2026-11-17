package net.minecraftforge.client.render.vulkan;

import net.minecraftforge.client.render.vertex.DrawState;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where each draw's block lands in the draw-data buffer.
 *
 * <p>The block is written through two views at once: bytes for the scalars, floats for the model-view matrix. The
 * matrix was written at the byte offset, which put the second draw's matrix 512 bytes into a 128-byte-per-draw view -
 * an {@code IndexOutOfBoundsException} on the first frame with more than one draw. The offsets are the whole indexing
 * scheme of the shader ({@code draws[gl_InstanceIndex]}), so they are pinned here rather than discovered again.</p>
 */
class DrawDataPackingTest {

    private static final int STRIDE = VulkanStream.DRAW_DATA_BYTES;

    private static DrawState stateWithMatrix(float seed) {
        DrawState state = new DrawState();
        float[] mvp = state.mvp();
        for (int i = 0; i < 16; i++) {
            mvp[i] = seed + i;
        }
        return state;
    }

    @Test
    void severalDrawsFitOneAfterAnotherWithoutOverlapping() {
        int draws = 5;
        ByteBuffer target = ByteBuffer.allocateDirect(draws * STRIDE).order(ByteOrder.nativeOrder());
        FloatBuffer floats = target.asFloatBuffer();

        for (int i = 0; i < draws; i++) {
            VulkanStream.packDrawData(target, floats, i, stateWithMatrix(i * 100.0F));
        }

        // Each draw's matrix starts at its own stride and is 16 floats long, and the draw after it starts where the
        // block ends - so the last matrix's final float is the last four bytes of the buffer. The values are not the
        // ones that went in: the writer negates Y and remaps Z into [0, w] on the way, which is Vulkan's clip space.
        for (int i = 0; i < draws; i++) {
            for (int f = 0; f < 16; f++) {
                float source = i * 100.0F + f;
                int row = f & 3;
                float expected = row == 1 ? -source
                        : row == 2 ? 0.5F * (source + (i * 100.0F + f + 1))
                        : source;
                float actual = floats.get(i * (STRIDE / Float.BYTES) + f);
                assertEquals(expected, actual, "draw " + i + " component " + f);
            }
        }
        assertNotEquals(0.0F, floats.get((draws - 1) * (STRIDE / Float.BYTES)),
                "the last draw's block has to be inside the buffer, not past its end");
    }

    @Test
    void theScalarsSitWhereTheShaderReadsThem() {
        ByteBuffer target = ByteBuffer.allocateDirect(STRIDE).order(ByteOrder.nativeOrder());
        DrawState state = stateWithMatrix(0.0F);
        VulkanStream.packDrawData(target, target.asFloatBuffer(), 0, state);

        // The offsets the shader's struct declares, checked against the writer in PushConstantLayoutTest; this is the
        // same block, so a packing that moves them would be caught in both places.
        float[] fog = state.fogColor();
        for (int c = 0; c < 4; c++) {
            assertEquals(fog[c], target.getFloat(64 + c * 4), "fogColor[" + c + "] at 64 + 4*" + c);
        }
        assertEquals(state.alphaReference(), target.getFloat(84), "alphaReference at 84");
        assertEquals(state.fogMode(), target.getInt(92), "fogMode at 92");
        assertEquals(state.fogStart(), target.getFloat(96), "fogStart at 96");
        assertEquals(state.pointSize(), target.getFloat(116), "pointSize at 116");
        assertEquals(STRIDE, target.capacity());
    }
}
