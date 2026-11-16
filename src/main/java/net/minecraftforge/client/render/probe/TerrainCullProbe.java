package net.minecraftforge.client.render.probe;

/**
 * What the per-frame visibility traversal costs, which is the last item D has left.
 *
 * <p><b>Why this is the measurement that matters now.</b> The other halves of the section-graph architecture are already
 * here in some form: terrain meshes live in this renderer's own buffers, and a layer is drawn with one indirect command
 * per slab rather than one per section. What vanilla still does on the render thread, every frame, is walk the
 * visibility graph - frustum, per-section face encoding, the region cache and the raster occluder - and that is the
 * part Sodium moves to its own thread and stores in a dense lattice.</p>
 *
 * <p>Whether it is worth moving depends on what it costs, so it is measured: wall time of
 * {@code RenderGlobal.setupTerrain}, how many sections the walk visited, and the worst frame of each.</p>
 */
public final class TerrainCullProbe {

    private static final boolean ENABLED = !"false".equals(System.getProperty("outlands.probe.cull"));

    private static long startedAt;
    private static long nanos;
    private static long walkStartedAt;
    private static long walkNanos;
    private static long walkMaxNanos;
    private static long walks;
    private static long frames;
    private static long visibleThisWalk;
    private static long traversableThisWalk;
    private static long hiddenThisWalk;
    private static long visible;
    private static long traversable;
    private static long hidden;
    private static long resultSize;
    private static long published;
    private static long maxResultSize;
    private static long visited;
    private static long visitedThisFrame;
    private static long maxVisitedPerFrame;
    private static long maxNanosPerFrame;

    private TerrainCullProbe() {}

    /** Starts timing one frame's traversal. */
    public static void begin() {
        if (ENABLED) {
            startedAt = System.nanoTime();
        }
    }

    /** Ends timing one frame's traversal and folds the frame into the peaks. */
    public static void end() {
        if (!ENABLED) {
            return;
        }
        long elapsed = System.nanoTime() - startedAt;
        nanos += elapsed;
        frames++;
        if (elapsed > maxNanosPerFrame) {
            maxNanosPerFrame = elapsed;
        }
        visited += visitedThisFrame;
        visible += visibleThisWalk;
        traversable += traversableThisWalk;
        hidden += hiddenThisWalk;
        visibleThisWalk = 0;
        traversableThisWalk = 0;
        hiddenThisWalk = 0;
        published += resultSize;
        if (resultSize > maxResultSize) {
            maxResultSize = resultSize;
        }
        resultSize = 0;
        if (visitedThisFrame > maxVisitedPerFrame) {
            maxVisitedPerFrame = visitedThisFrame;
        }
        visitedThisFrame = 0;
    }

    /** Starts timing the traversal loop itself, which is the part Sodium moves off the render thread. */
    public static void beginWalk() {
        if (ENABLED) {
            walkStartedAt = System.nanoTime();
        }
    }

    /** Ends the traversal loop's timing. */
    public static void endWalk() {
        if (!ENABLED) {
            return;
        }
        long elapsed = System.nanoTime() - walkStartedAt;
        walkNanos += elapsed;
        walks++;
        if (elapsed > walkMaxNanos) {
            walkMaxNanos = elapsed;
        }
    }

    /** Records one section the traversal looked at. */
    public static void visited() {
        visitedThisFrame++;
    }

    /**
     * Records the verdict a section got: drawn, walked through but not drawn, or cut.
     *
     * <p>The three counts are what makes two runs comparable at all: with different scenes, "the walk took 4 ms" says
     * nothing, while "it culled 62% of what it reached" is the same statement in any scene.</p>
     */
    public static void verdict(boolean rendered, boolean traversed) {
        if (!ENABLED) {
            return;
        }
        if (!traversed) {
            hiddenThisWalk++;
        } else if (rendered) {
            visibleThisWalk++;
        } else {
            traversableThisWalk++;
        }
    }

    /** Records how many sections the walk ended up publishing. */
    public static void result(int size) {
        if (!ENABLED) {
            return;
        }
        resultSize = size;
    }

    /** The totals since the last call, formatted for the renderer's line, and reset. */
    public static String reportAndReset() {
        if (!ENABLED) {
            return "terrain cull: probe off";
        }
        if (frames == 0) {
            return "terrain cull: none";
        }
        double perFrame = frames;
        // Per walk, not per frame. vanilla skips the traversal whenever the camera has not moved or turned, and
        // setupTerrain itself runs once per render pass - three of those a frame with Forge's two extra passes. A
        // figure averaged over calls is therefore diluted by calls that did nothing, which is exactly the mistake this
        // split fixes: two runs of the same scene looked 2.6x apart only because one of them walked on more frames.
        String report = "terrain cull: walk " + oneDecimal(walkNanos / 1_000_000.0 / Math.max(1, walks))
                + " ms/walk (worst " + oneDecimal(walkMaxNanos / 1_000_000.0) + ", " + walks + " walks in "
                + frames + " calls), setup " + oneDecimal(nanos / 1_000_000.0 / perFrame) + " ms/call (worst "
                + oneDecimal(maxNanosPerFrame / 1_000_000.0) + "), "
                + (long) (visited / Math.max(1, walks)) + " sections/walk (at most " + maxVisitedPerFrame + "),"
                + " visible " + (long) (visible / Math.max(1, walks))
                + " traversable " + (long) (traversable / Math.max(1, walks))
                + " hidden " + (long) (hidden / Math.max(1, walks))
                + ", published " + (long) (published / Math.max(1, walks)) + " (at most " + maxResultSize + ")";
        walkNanos = 0;
        walkMaxNanos = 0;
        walks = 0;
        nanos = 0;
        frames = 0;
        visited = 0;
        maxVisitedPerFrame = 0;
        visible = 0;
        traversable = 0;
        hidden = 0;
        published = 0;
        maxResultSize = 0;
        maxNanosPerFrame = 0;
        return report;
    }

    private static String oneDecimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
