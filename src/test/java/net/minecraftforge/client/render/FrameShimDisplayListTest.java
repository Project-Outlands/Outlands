package net.minecraftforge.client.render;

import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraftforge.client.render.shim.ShimGl;
import net.minecraftforge.client.render.state.backend.DisplayListBackend;
import net.minecraftforge.client.render.state.backend.DisplayLists;
import net.minecraftforge.client.render.vertex.FrameDraws;
import net.minecraftforge.client.render.vertex.VertexLayout;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Display lists reached from a mod's own call sites, which is how Immersive Intelligence uses them.
 *
 * <p>The backend itself ({@code DisplayListVk}) is covered by its own test; what this covers is the wiring, which is
 * the part that was missing: the recorder existed, the patch routed vanilla's {@code GlStateManager} into it, and a
 * mod calling {@code GL11.glNewList} directly still landed on real GL. Three entries and three facade methods later
 * it does not.</p>
 */
class FrameShimDisplayListTest {

    private static final int GL_COMPILE = 4864;
    private static final int GL_QUADS = 7;

    @BeforeEach
    void selectANonGlBackend() {
        NonGlTestBackend.select();
    }

    @AfterEach
    void restoreTheRealGlPath() {
        if (FrameDraws.isCollecting()) {
            FrameDraws.drain();
        }
        NonGlTestBackend.restore();
    }

    private static ByteBuffer quad() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(4 * DefaultVertexFormats.BLOCK.getSize());
        buffer.position(0);
        return buffer;
    }

    @Test
    void theThreeCallsReachTheListBackend() {
        DisplayListBackend lists = DisplayLists.instance();
        int id = lists.generate(1);

        ShimGl.glNewList(id, GL_COMPILE);
        assertEquals(id, lists.recording(), "glNewList started a recording on the backend");

        // The draw arrives through the door the stream backend uses, which is how a real build pass reaches it.
        assertEquals(true, DisplayLists.capture(VertexLayout.of(DefaultVertexFormats.BLOCK), GL_QUADS, 4, quad()),
                "a draw while recording belongs to the list");

        ShimGl.glEndList();
        assertNull(lists.recording(), "glEndList closed it");
        assertEquals(1, lists.drawCount(id));
    }

    @Test
    void theReplayPutsTheDrawsIntoTheFrame() {
        DisplayListBackend lists = DisplayLists.instance();
        int id = lists.generate(1);
        ShimGl.glNewList(id, GL_COMPILE);
        DisplayLists.capture(VertexLayout.of(DefaultVertexFormats.BLOCK), GL_QUADS, 4, quad());
        ShimGl.glEndList();

        FrameDraws.beginCollecting();
        ShimGl.glCallList(id);
        var recorded = FrameDraws.drain();

        assertEquals(1, recorded.size(), "the replay reached the frame");
        assertEquals(4, ((FrameDraws.Draw) recorded.get(0)).vertexCount());
    }

    @Test
    void immediateModeGeometryCanBeCompiledIntoAList() {
        // This is the shape Immersive Intelligence's AMTQuads has: glBegin/glVertex between glNewList and glEndList.
        // Before the capture was offered here, the immediate-mode flush threw "arrived while no frame was
        // collecting" - correct for a stray draw, wrong for one being recorded.
        DisplayListBackend lists = DisplayLists.instance();
        int id = lists.generate(1);

        ShimGl.glNewList(id, GL_COMPILE);
        ShimGl.glBegin(GL_QUADS);
        ShimGl.glVertex3f(0.0F, 0.0F, 0.0F);
        ShimGl.glVertex3f(1.0F, 0.0F, 0.0F);
        ShimGl.glVertex3f(1.0F, 1.0F, 0.0F);
        ShimGl.glVertex3f(0.0F, 1.0F, 0.0F);
        ShimGl.glEnd();
        ShimGl.glEndList();

        assertEquals(1, lists.drawCount(id), "the quad became part of the list");
    }

    @Test
    void compileAndExecuteDrawsWhileItRecords() {
        // Immersive Intelligence's AMTQuads: glNewList(4865), draw the quads, glEndList on the frame the model first
        // appears, then glCallList from then on. Refusing 4865 crashed the creative inventory; treating it as
        // GL_COMPILE would make the model invisible for one frame instead.
        DisplayListBackend lists = DisplayLists.instance();
        int id = lists.generate(1);

        FrameDraws.beginCollecting();
        ShimGl.glNewList(id, 4865);
        ShimGl.glBegin(GL_QUADS);
        ShimGl.glVertex3f(0.0F, 0.0F, 0.0F);
        ShimGl.glVertex3f(1.0F, 0.0F, 0.0F);
        ShimGl.glVertex3f(1.0F, 1.0F, 0.0F);
        ShimGl.glVertex3f(0.0F, 1.0F, 0.0F);
        ShimGl.glEnd();
        ShimGl.glEndList();
        var recorded = FrameDraws.drain();

        assertEquals(1, lists.drawCount(id), "the quad is in the list");
        assertEquals(1, recorded.size(), "and it was drawn on the same frame");
    }

    @Test
    void compileAndExecuteWithoutAFrameStillBuildsTheList() {
        // A model built during startup has no frame to draw into. The list is the point; the draw has nowhere to go.
        DisplayListBackend lists = DisplayLists.instance();
        int id = lists.generate(1);

        ShimGl.glNewList(id, 4865);
        ShimGl.glBegin(GL_QUADS);
        ShimGl.glVertex3f(0.0F, 0.0F, 0.0F);
        ShimGl.glVertex3f(1.0F, 0.0F, 0.0F);
        ShimGl.glVertex3f(1.0F, 1.0F, 0.0F);
        ShimGl.glVertex3f(0.0F, 1.0F, 0.0F);
        ShimGl.glEnd();
        ShimGl.glEndList();

        assertEquals(1, lists.drawCount(id), "the list is built even with nothing collecting");
    }

    @Test
    void theBackendIsTheOneThePatchWouldUse() {
        // Not a tautology: vanilla's GlStateManager and the shim have to end up at the same recorder, or a mod's
        // list and the game's list would live in two name spaces that happen to use the same numbers.
        assertSame(DisplayLists.instance(), DisplayLists.forNonGl());
    }
}
