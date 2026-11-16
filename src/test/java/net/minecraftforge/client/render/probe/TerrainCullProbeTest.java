package net.minecraftforge.client.render.probe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The traversal probe's bookkeeping.
 *
 * <p>What is pinned is only the way the figures could be silently wrong: a walk that reports no sections because the
 * counter was reset at the wrong time, and a worst-walk figure that is really the last walk.</p>
 */
class TerrainCullProbeTest {

    @Test
    void aFrameReportsItsOwnSectionsAndKeepsTheBusiest() {
        // The probe is off unless asked for, so the test turns it on for its own body.
        TerrainCullProbe.forceForTests(true);
        try {
            assertAccounting();
        } finally {
            TerrainCullProbe.forceForTests(false);
        }
    }

    private static void assertAccounting() {
        TerrainCullProbe.reportAndReset(); // start from a known state

        TerrainCullProbe.begin();
        TerrainCullProbe.beginWalk();
        TerrainCullProbe.visited();
        TerrainCullProbe.verdict(true, true);
        TerrainCullProbe.verdict(false, true);
        TerrainCullProbe.verdict(false, false);
        TerrainCullProbe.result(120);
        TerrainCullProbe.endWalk();
        TerrainCullProbe.end();

        TerrainCullProbe.begin();
        TerrainCullProbe.beginWalk();
        for (int i = 0; i < 7; i++) {
            TerrainCullProbe.visited();
        }
        TerrainCullProbe.result(80);
        TerrainCullProbe.endWalk();
        TerrainCullProbe.end();

        String report = TerrainCullProbe.reportAndReset();
        assertTrue(report.contains("4 sections/walk"), report);
        assertTrue(report.contains("walk "), report);
        assertTrue(report.contains("at most 7"), report);
        assertTrue(report.contains("2 walks in 2 calls"), report);
        assertTrue(report.contains("visible 0 traversable 0 hidden 0"), report);
        assertTrue(report.contains("published 100"), report);
        assertTrue(TerrainCullProbe.reportAndReset().equals("terrain cull: no walks"), "the report resets the totals");
    }
}
