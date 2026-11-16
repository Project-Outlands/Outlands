package net.minecraftforge.client.render.probe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The item render probe's bookkeeping.
 *
 * <p><b>What is worth pinning.</b> Not the timings - those are whatever the machine did - but the two things that
 * would make the numbers wrong in a way nobody notices: a nested item render counted twice, and a draw attributed to
 * item rendering when no item was being rendered. Both would quietly inflate the figure the whole question rests
 * on.</p>
 */
class ItemRenderProbeTest {

    @Test
    void aNestedRenderIsTimedOnce() {
        ItemRenderProbe.begin();
        ItemRenderProbe.begin();
        ItemRenderProbe.countDraw();
        ItemRenderProbe.end();
        assertTrue(ItemRenderProbe.active(), "the outer render is still open");
        ItemRenderProbe.end();
        assertFalse(ItemRenderProbe.active());

        String report = ItemRenderProbe.reportAndReset(1);
        assertTrue(report.contains("1 renders"), report);
        assertTrue(report.contains("1 draws"), report);
    }

    @Test
    void aDrawOutsideAnItemRenderIsNotCounted() {
        ItemRenderProbe.begin();
        ItemRenderProbe.countDraw();
        ItemRenderProbe.end();

        assertFalse(ItemRenderProbe.active());
        assertTrue(ItemRenderProbe.reportAndReset(1).contains("1 draws"), "the one inside the render");
        assertTrue(ItemRenderProbe.reportAndReset(1).contains("none"), "and the totals are reset by the report");
    }

    @Test
    void anUnbalancedEndDoesNotLeaveTheProbeOpen() {
        // A renderer that throws without a finally would otherwise leave every later draw attributed to items.
        ItemRenderProbe.end();
        assertFalse(ItemRenderProbe.active());
        assertEquals("item rendering: none", ItemRenderProbe.reportAndReset(1));
    }
}
