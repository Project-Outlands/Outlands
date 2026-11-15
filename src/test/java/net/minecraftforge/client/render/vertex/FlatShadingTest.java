package net.minecraftforge.client.render.vertex;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code glShadeModel(GL_FLAT)}: a primitive takes the colour of its last vertex.
 *
 * <p><b>Why this is worth doing at all.</b> The shade model was recorded and never applied - the same shape of fault
 * as the blend equation and the polygon mode before it, and the same consequence: a mod asks for one picture and gets
 * another with nothing in the log. It is not a rare state either: vanilla's
 * {@code RenderHelper.disableStandardItemLighting} restores {@code GL_FLAT}, so most unlit geometry is drawn under it,
 * and Immersive Engineering sets {@code GL_SMOOTH} explicitly around its fractal particles and puts it back.</p>
 *
 * <p>The provoking vertex is the <i>last</i> one of each primitive, which is GL's default convention.</p>
 */
class FlatShadingTest {

    /** {@code GL_QUADS}. */
    private static final int QUADS = 7;
    /** {@code GL_TRIANGLES}. */
    private static final int TRIANGLES = 4;

    @Test
    void aQuadTakesTheLastVertexsColour() {
        ByteBuffer target = colours(new int[] {10, 20, 30, 40});
        VertexConverter.applyFlatShading(target, 0, 4, QUADS);
        assertArrayEquals(new int[] {40, 40, 40, 40}, read(target, 4));
    }

    @Test
    void twoQuadsAreHandledSeparately() {
        ByteBuffer target = colours(new int[] {10, 20, 30, 40, 50, 60, 70, 80});
        VertexConverter.applyFlatShading(target, 0, 8, QUADS);
        assertArrayEquals(new int[] {40, 40, 40, 40, 80, 80, 80, 80}, read(target, 8),
                "the second quad does not take the first one's colour");
    }

    @Test
    void aTriangleTakesTheLastVertexsColour() {
        ByteBuffer target = colours(new int[] {10, 20, 30});
        VertexConverter.applyFlatShading(target, 0, 3, TRIANGLES);
        assertArrayEquals(new int[] {30, 30, 30}, read(target, 3));
    }

    @Test
    void aTrailingPartialPrimitiveIsLeftAlone() {
        // Five vertices as quads: one whole quad and one vertex. Touching the stray vertex would corrupt the next
        // draw's first vertex, since the target buffer is shared and this one was written after it.
        ByteBuffer target = colours(new int[] {10, 20, 30, 40, 55});
        VertexConverter.applyFlatShading(target, 0, 5, QUADS);
        assertArrayEquals(new int[] {40, 40, 40, 40, 55}, read(target, 5));
    }

    @Test
    void theTargetOffsetIsRespected() {
        // The stream writes draw after draw into one buffer, so a pass that ignored the offset would flatten the
        // previous draw's vertices into this one's colour.
        ByteBuffer target = colours(new int[] {10, 20, 30, 40, 50, 60, 70, 80});
        VertexConverter.applyFlatShading(target, VertexConverter.STRIDE * 4, 4, QUADS);
        assertArrayEquals(new int[] {10, 20, 30, 40, 80, 80, 80, 80}, read(target, 8),
                "the first quad, written earlier, is untouched");
    }

    @Test
    void anUnsupportedPrimitiveIsLeftSmooth() {
        // A strip needs the triangle order, which this pass does not have; leaving it interpolated is the honest
        // answer and it is reported once.
        ByteBuffer target = colours(new int[] {10, 20, 30, 40});
        VertexConverter.applyFlatShading(target, 0, 4, 5);   // GL_TRIANGLE_STRIP
        assertArrayEquals(new int[] {10, 20, 30, 40}, read(target, 4));
    }

    @Test
    void alphaTravelsWithTheColour() {
        ByteBuffer target = colours(new int[] {10, 20, 30, 40});
        target.put(VertexConverter.STRIDE * 3 + 15, (byte) 7);   // a distinct alpha on the provoking vertex
        VertexConverter.applyFlatShading(target, 0, 4, QUADS);
        assertEquals(7, target.get(15) & 0xFF, "the first vertex takes the provoking alpha too");
    }

    /** A target buffer of {@code count} vertices whose colour channels hold the given values. */
    private static ByteBuffer colours(int[] values) {
        ByteBuffer target = ByteBuffer.allocate(values.length * VertexConverter.STRIDE)
                .order(ByteOrder.nativeOrder());
        for (int i = 0; i < values.length; i++) {
            int at = i * VertexConverter.STRIDE + 12;    // COLOR_OFFSET
            target.put(at, (byte) values[i]).put(at + 1, (byte) 0).put(at + 2, (byte) 0).put(at + 3, (byte) 255);
        }
        return target;
    }

    private static int[] read(ByteBuffer target, int count) {
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            out[i] = target.get(i * VertexConverter.STRIDE + 12) & 0xFF;
        }
        return out;
    }
}
