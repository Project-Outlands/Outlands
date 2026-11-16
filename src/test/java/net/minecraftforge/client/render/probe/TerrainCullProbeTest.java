package net.minecraftforge.client.render.probe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The traversal probe's bookkeeping.
 *
 * <p>Two numbers decide whether the visibility walk moves off the render thread: its cost per frame and how many
 * sections it looked at. Both are pinned here only in the way they could be silently wrong - a frame that reports no
 * sections because the counter was reset at the wrong time, and a worst-frame figure that is really the last frame.</p>
 */
class TerrainCullProbeTest {

    @Test
    void aFrameReportsItsOwnSectionsAndKeepsTheBusiest() {
        TerrainCullProbe.reportAndReset(); // start from a known state

        TerrainCullProbe.begin();
        TerrainCullProbe.beginWalk();
        TerrainCullProbe.visited();
        TerrainCullProbe.endWalk();
        TerrainCullProbe.end();

        TerrainCullProbe.begin();
        TerrainCullProbe.beginWalk();
        for (int i = 0; i < 7; i++) {
            TerrainCullProbe.visited();
        }
        TerrainCullProbe.endWalk();
        TerrainCullProbe.end();

        String report = TerrainCullProbe.reportAndReset();
        assertTrue(report.contains("4 sections/walk"), report);
        assertTrue(report.contains("walk "), report);
        assertTrue(report.contains("setup "), report);
        assertTrue(report.contains("at most 7"), report);
        assertTrue(report.contains("2 walks in 2 calls"), report);
        assertTrue(TerrainCullProbe.reportAndReset().equals("terrain cull: none"), "the report resets the totals");
    }
}
