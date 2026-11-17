package net.minecraftforge.client.render.probe;

/**
 * What the per-frame visibility traversal costs, which is the last item D has left.
 *
 * <p><b>Why this is the measurement that matters now.</b> The other halves of the section-graph architecture are already
 * here in some form: terrain meshes live in this renderer's own buffers, and a layer is drawn with one indirect command
 * per slab rather than one per section. What vanilla still does on the render thread, every frame, is walk the
 * visibility graph - frustum, per-section face encoding and the region cache - and that is the part Sodium moves to
 * its own thread and stores in a dense lattice.</p>
 *
 * <p>Whether it is worth moving depends on what it costs, so it is measured: wall time of
 * {@code RenderGlobal.setupTerrain}, how many sections the walk visited, and the worst frame of each.</p>
 */
public final class TerrainCullProbe {

    /**
     * Off unless asked for. The walk costs nothing at rest - vanilla skips it whenever the camera has not moved or
     * turned - so this is a probe for a specific question ("how expensive is the moving case at this render
     * distance"), not something a normal run should pay for.
     */
    private static volatile boolean enabled = Boolean.getBoolean("outlands.probe.cull");

    private static long startedAt;
    private static long nanos;
    private static long walkStartedAt;
    private static long walkNanos;
    private static long walkMaxNanos;
    private static long walks;
    private static long frames;
    private static long resultSize;
    private static long published;
    private static long maxResultSize;
    private static long visited;
    private static long visitedThisFrame;
    private static long maxVisitedPerFrame;
    private static long maxNanosPerFrame;

    private TerrainCullProbe() {}

    /** Turns the probe on or off for a test, which cannot rely on the launch command. */
    static void forceForTests(boolean value) {
        enabled = value;
    }

    /** Starts timing one frame's traversal. */
    public static void begin() {
        if (enabled) {
            startedAt = System.nanoTime();
        }
    }

    /** Ends timing one frame's traversal and folds the frame into the peaks. */
    public static void end() {
        if (!enabled) {
            return;
        }
        long elapsed = System.nanoTime() - startedAt;
        nanos += elapsed;
        frames++;
        if (elapsed > maxNanosPerFrame) {
            maxNanosPerFrame = elapsed;
        }
        visited += visitedThisFrame;
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
        if (enabled) {
            walkStartedAt = System.nanoTime();
        }
    }

    /** Ends the traversal loop's timing. */
    public static void endWalk() {
        if (!enabled) {
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

    /** Records how many sections the walk ended up publishing. */
    public static void result(int size) {
        if (!enabled) {
            return;
        }
        resultSize = size;
    }

    /** The totals since the last call, formatted for the renderer's line, and reset. */
    public static String reportAndReset() {
        if (!enabled) {
            return null;
        }
        if (frames == 0) {
            return "terrain cull: no walks";
        }
        double perFrame = frames;
        // Per walk, not per frame. vanilla skips the traversal whenever the camera has not moved or turned, and
        // setupTerrain itself runs once per render pass - three of those a frame with Forge's two extra passes. A
        // figure averaged over calls is therefore diluted by calls that did nothing, which is exactly the mistake this
        // split fixes: two runs of the same scene looked 2.6x apart only because one of them walked on more frames.
        String report = "terrain cull: walk " + oneDecimal(walkNanos / 1_000_000.0 / Math.max(1, walks))
                + " ms/walk (worst " + oneDecimal(walkMaxNanos / 1_000_000.0) + ", " + walks + " walks in "
                + frames + " calls), "
                + (long) (visited / Math.max(1, walks)) + " sections/walk (at most " + maxVisitedPerFrame + "),"
                + " published " + (long) (published / Math.max(1, walks)) + " (at most " + maxResultSize + ")";
        walkNanos = 0;
        walkMaxNanos = 0;
        walks = 0;
        nanos = 0;
        frames = 0;
        visited = 0;
        maxVisitedPerFrame = 0;
        published = 0;
        maxResultSize = 0;
        maxNanosPerFrame = 0;
        return report;
    }

    private static String oneDecimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
