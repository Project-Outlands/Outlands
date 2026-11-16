package net.minecraftforge.client.render.vertex;

import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraftforge.client.render.NonGlTestBackend;
import net.minecraftforge.client.render.shim.ShimGl;
import net.minecraftforge.client.render.state.backend.FrameState;
import net.minecraftforge.client.render.state.backend.FrameStates;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two adjacent draws with nothing to tell them apart are one draw.
 *
 * <p><b>Why this is worth pinning.</b> {@code FontRenderer} draws every glyph as its own {@code glBegin}/{@code
 * glEnd}, which reaches this list as one draw per glyph; measured on a text-heavy frame, 342 of 761 operations were
 * glyph draws of four vertices each. Folding them is only sound if the fold is invisible, so the tests below check both
 * halves: the bookkeeping (one operation, the right vertex count) and the geometry (the same triangles rasterise).</p>
 */
class DrawMergingTest {

    private static final int GL_TRIANGLES = 4;
    private static final int GL_TRIANGLE_STRIP = 5;
    private static final int GL_TRIANGLE_FAN = 6;
    private static final int GL_QUADS = 7;

    private static final int GL_SMOOTH = 7425;
    private static final int GL_FLAT = 7424;
    private static final int GL_FUNC_ADD = 32774;
    private static final int GL_FUNC_SUBTRACT = 32778;

    @BeforeEach
    void selectANonGlBackend() {
        NonGlTestBackend.select();
    }

    @AfterEach
    void restore() {
        FrameDraws.drain();
        NonGlTestBackend.restore();
    }

    private static FrameState state() {
        FrameState state = FrameStates.forNonGl();
        assertNotNull(state, "the test selected a non-GL backend");
        return state;
    }

    /** The layout {@code ImmediateMode} records through: the canonical one, so text and mod geometry share it. */
    private static VertexLayout textLayout() {
        return VertexLayout.of(DefaultVertexFormats.BLOCK);
    }

    /**
     * One glyph quad as {@code FontRenderer} emits it: a four-vertex strip, positions in the first three floats.
     *
     * <p>The order is GL's zigzag - bottom-left, bottom-right, top-left, top-right - because that is what the strip
     * order is for, and a merged strip has to rasterise the same two triangles as this.</p>
     */
    private static ByteBuffer glyph(float x, float y, int stride) {
        ByteBuffer data = ByteBuffer.allocateDirect(4 * stride).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 4; i++) {
            int base = i * stride;
            data.putFloat(base, x + (i & 1));
            data.putFloat(base + 4, y + ((i >> 1) & 1));
            data.putFloat(base + 8, 0.0F);
        }
        return data;
    }

    /** One quad of a triangle list or a quad list: four distinct corners in order. */
    private static ByteBuffer quad(float x, float y, int stride) {
        ByteBuffer data = ByteBuffer.allocateDirect(4 * stride).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 4; i++) {
            int base = i * stride;
            data.putFloat(base, x + (i & 1));
            data.putFloat(base + 4, y + ((i >> 1) & 1));
            data.putFloat(base + 8, 0.0F);
        }
        return data;
    }

    /** Records one draw the way the game does: the state is captured from the live frame state. */
    private static void record(VertexLayout layout, int mode, ByteBuffer data, int vertexCount) {
        FrameDraws.record(layout, mode, vertexCount, data, DrawState.current());
    }

    private static FrameDraws.Draw onlyDraw() {
        List<FrameDraws.Operation> ops = FrameDraws.drain();
        assertEquals(1, ops.size(), "expected one operation, got " + ops.size() + ": " + ops);
        return (FrameDraws.Draw) ops.get(0);
    }

    private static List<String> triangles(ByteBuffer vertices, int vertexCount, int stride) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i + 2 < vertexCount; i++) {
            String p0 = position(vertices, i, stride);
            String p1 = position(vertices, i + 1, stride);
            String p2 = position(vertices, i + 2, stride);
            // A degenerate triangle has two equal corners and rasterises nothing.
            if (p0.equals(p1) || p1.equals(p2) || p0.equals(p2)) {
                continue;
            }
            // GL's strip winding alternates: even triangles are (i, i+1, i+2), odd ones (i+1, i, i+2).
            out.add(i % 2 == 0 ? p0 + " " + p1 + " " + p2 : p1 + " " + p0 + " " + p2);
        }
        return out;
    }

    private static String position(ByteBuffer vertices, int index, int stride) {
        int base = index * stride;
        return vertices.getFloat(base) + "," + vertices.getFloat(base + 4) + "," + vertices.getFloat(base + 8);
    }

    @Test
    void twoGlyphStripsBecomeOneDrawWithADegenerateJoin() {
        VertexLayout layout = textLayout();
        FrameDraws.beginCollecting();
        record(layout, GL_TRIANGLE_STRIP, glyph(0, 0, layout.stride()), 4);
        record(layout, GL_TRIANGLE_STRIP, glyph(8, 0, layout.stride()), 4);

        FrameDraws.Draw merged = onlyDraw();
        assertEquals(GL_TRIANGLE_STRIP, merged.mode(), "a strip stays a strip");
        assertEquals(4 + 2 + 4, merged.vertexCount(), "two degenerate vertices join the strips");
        assertSame(layout, merged.layout());
    }

    @Test
    void theMergedStripRasterisesExactlyTheSameTriangles() {
        VertexLayout layout = textLayout();
        ByteBuffer first = glyph(0, 0, layout.stride());
        ByteBuffer second = glyph(8, 0, layout.stride());

        FrameDraws.beginCollecting();
        record(layout, GL_TRIANGLE_STRIP, first.duplicate(), 4);
        record(layout, GL_TRIANGLE_STRIP, second.duplicate(), 4);
        FrameDraws.Draw merged = onlyDraw();

        List<String> separately = new ArrayList<>(triangles(first, 4, layout.stride()));
        separately.addAll(triangles(second, 4, layout.stride()));
        List<String> folded = triangles(ByteBuffer.wrap(merged.vertices()).order(ByteOrder.nativeOrder()),
                merged.vertexCount(), layout.stride());

        assertEquals(separately, folded, "the join added no triangle that rasterises");
    }

    @Test
    void anOddStripJoinNeedsThreeDegenerateVertices() {
        // Five vertices is an odd strip, so its last triangle sits at the opposite winding from an even one. Two
        // duplicates would leave the new segment starting on the wrong parity, and its first triangle would be
        // back-facing - which culling turns into a hole.
        VertexLayout layout = textLayout();
        ByteBuffer odd = ByteBuffer.allocateDirect(5 * layout.stride()).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 5; i++) {
            odd.putFloat(i * layout.stride(), i);
            odd.putFloat(i * layout.stride() + 4, i * 2);
            odd.putFloat(i * layout.stride() + 8, 0.0F);
        }
        ByteBuffer even = glyph(20, 0, layout.stride());

        FrameDraws.beginCollecting();
        record(layout, GL_TRIANGLE_STRIP, odd.duplicate(), 5);
        record(layout, GL_TRIANGLE_STRIP, even.duplicate(), 4);
        FrameDraws.Draw merged = onlyDraw();

        assertEquals(5 + 3 + 4, merged.vertexCount(), "an odd join takes three duplicates");

        List<String> separately = new ArrayList<>(triangles(odd, 5, layout.stride()));
        separately.addAll(triangles(even, 4, layout.stride()));
        List<String> folded = triangles(ByteBuffer.wrap(merged.vertices()).order(ByteOrder.nativeOrder()),
                merged.vertexCount(), layout.stride());
        assertEquals(separately, folded, "and the winding of the second strip survives the join");
    }

    @Test
    void listsFoldWithoutDegenerateVertices() {
        VertexLayout layout = textLayout();
        FrameDraws.beginCollecting();
        record(layout, GL_QUADS, quad(0, 0, layout.stride()), 4);
        record(layout, GL_QUADS, quad(8, 0, layout.stride()), 4);
        FrameDraws.Draw quads = onlyDraw();
        assertEquals(GL_QUADS, quads.mode());
        assertEquals(8, quads.vertexCount(), "a quad list needs no join vertices");

        FrameDraws.beginCollecting();
        record(layout, GL_TRIANGLES, quad(0, 0, layout.stride()), 4);
        record(layout, GL_TRIANGLES, quad(8, 0, layout.stride()), 4);
        FrameDraws.Draw triangles = onlyDraw();
        assertEquals(8, triangles.vertexCount());
    }

    @Test
    void aScreenOfTextIsOneDraw() {
        // What a debug screen is: hundreds of glyphs, all with the same font atlas and the same state.
        VertexLayout layout = textLayout();
        FrameDraws.beginCollecting();
        for (int i = 0; i < 500; i++) {
            record(layout, GL_TRIANGLE_STRIP, glyph(i * 8, 0, layout.stride()), 4);
        }
        FrameDraws.Draw merged = onlyDraw();
        assertEquals(500 * 4 + 499 * 2, merged.vertexCount(), "500 glyphs, one join between each pair");
    }

    @Test
    void aDifferentStateIsNotFolded() {
        VertexLayout layout = textLayout();
        FrameState state = state();

        state.shadeModel(GL_SMOOTH);
        FrameDraws.beginCollecting();
        record(layout, GL_TRIANGLE_STRIP, glyph(0, 0, layout.stride()), 4);
        state.shadeModel(GL_FLAT);
        record(layout, GL_TRIANGLE_STRIP, glyph(8, 0, layout.stride()), 4);
        assertEquals(2, FrameDraws.drain().size(), "flat and smooth shading are not the same draw");

        // The same for a field the pipeline reads when it builds itself rather than when it converts vertices.
        state.shadeModel(GL_SMOOTH);
        state.blendEquation(GL_FUNC_ADD);
        FrameDraws.beginCollecting();
        record(layout, GL_TRIANGLE_STRIP, glyph(0, 0, layout.stride()), 4);
        state.blendEquation(GL_FUNC_SUBTRACT);
        record(layout, GL_TRIANGLE_STRIP, glyph(8, 0, layout.stride()), 4);
        assertEquals(2, FrameDraws.drain().size(), "a different blend equation is a different pipeline");
    }

    @Test
    void everyFieldThatReachesThePipelineSurvivesTheCopyIntoADraw() {
        // The copy that carries a captured state into a draw used to drop blendColor, blendEquation, logicOp,
        // polygonModeFace/Mode and shadeModel. Nothing reported it: each draw replayed with the default, so
        // glShadeModel(GL_FLAT) - implemented for Immersive Engineering's flat item models - was a silent no-op.
        VertexLayout layout = textLayout();
        FrameState state = state();
        state.shadeModel(GL_FLAT);
        state.blendEquation(GL_FUNC_SUBTRACT);
        state.logicOp(5380);                     // GL_XOR
        state.polygonMode(1032, 6913);           // GL_FRONT, GL_LINE
        state.blendColor(0.25F, 0.5F, 0.75F, 1.0F);

        FrameDraws.beginCollecting();
        record(layout, GL_TRIANGLES, quad(0, 0, layout.stride()), 4);
        FrameDraws.Draw draw = onlyDraw();
        DrawState copied = draw.state();

        assertEquals(GL_FLAT, copied.shadeModel(), "glShadeModel reaches the draw");
        assertEquals(GL_FUNC_SUBTRACT, copied.blendEquation(), "glBlendEquation reaches the draw");
        assertEquals(5380, copied.logicOp(), "glLogicOp reaches the draw");
        assertEquals(6913, copied.polygonModeMode(), "glPolygonMode reaches the draw");
        assertEquals(0.5F, copied.blendColor()[1], 0.001F, "glBlendColor reaches the draw");
    }

    @Test
    void theShimPathFoldsGlyphsToo() {
        // The game does not call record: it calls glBegin/glEnd, and FontRenderer is the only thing in the game that
        // still does. This pins the whole path - accumulator, state capture, fold - because a fold that only worked
        // when called directly would do nothing for the one caller that needs it.
        FrameDraws.beginCollecting();
        for (int i = 0; i < 3; i++) {
            ShimGl.glBegin(GL_TRIANGLE_STRIP);
            ShimGl.glTexCoord2f(0.0F, 0.0F);
            ShimGl.glVertex3f(i, 0.0F, 0.0F);
            ShimGl.glTexCoord2f(1.0F, 0.0F);
            ShimGl.glVertex3f(i + 1, 0.0F, 0.0F);
            ShimGl.glTexCoord2f(0.0F, 1.0F);
            ShimGl.glVertex3f(i, 1.0F, 0.0F);
            ShimGl.glTexCoord2f(1.0F, 1.0F);
            ShimGl.glVertex3f(i + 1, 1.0F, 0.0F);
            ShimGl.glEnd();
        }

        FrameDraws.Draw merged = onlyDraw();
        assertEquals(3 * 4 + 2 * 2, merged.vertexCount(), "three glyphs, two joins");
        assertEquals(GL_TRIANGLE_STRIP, merged.mode());
    }

    @Test
    void aDifferentLayoutIsNotFolded() {
        FrameDraws.beginCollecting();
        record(VertexLayout.of(DefaultVertexFormats.BLOCK), GL_QUADS, quad(0, 0, 28), 4);
        record(VertexLayout.of(DefaultVertexFormats.POSITION_TEX), GL_QUADS, quad(8, 0, 20), 4);
        assertEquals(2, FrameDraws.drain().size(), "two strides cannot share one vertex buffer offset");
    }

    @Test
    void anOperationBetweenTwoDrawsStopsTheFold() {
        VertexLayout layout = textLayout();
        FrameDraws.beginCollecting();
        record(layout, GL_TRIANGLE_STRIP, glyph(0, 0, layout.stride()), 4);
        FrameDraws.recordClear(0x100, new float[] {0, 0, 0, 1}, 1.0, 0, false, new int[] {0, 0, 1, 1});
        record(layout, GL_TRIANGLE_STRIP, glyph(8, 0, layout.stride()), 4);

        List<FrameDraws.Operation> ops = FrameDraws.drain();
        assertEquals(3, ops.size(), "a clear between two draws keeps them apart: " + ops);
        assertTrue(ops.get(1) instanceof FrameDraws.Clear);
    }

    @Test
    void modesWhoseVerticesDoNotConcatenateAreNotFolded() {
        VertexLayout layout = textLayout();
        for (int mode : new int[] {GL_TRIANGLE_FAN, 8, 9}) {   // FAN, QUAD_STRIP, POLYGON
            FrameDraws.beginCollecting();
            record(layout, mode, quad(0, 0, layout.stride()), 4);
            record(layout, mode, quad(8, 0, layout.stride()), 4);
            assertEquals(2, FrameDraws.drain().size(), "mode " + mode + " shares vertices between primitives");
        }
    }
}
