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
    private static long frames;
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
        if (visitedThisFrame > maxVisitedPerFrame) {
            maxVisitedPerFrame = visitedThisFrame;
        }
        visitedThisFrame = 0;
    }

    /** Records one section the traversal looked at. */
    public static void visited() {
        visitedThisFrame++;
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
        String report = "terrain cull: " + oneDecimal(nanos / 1_000_000.0 / perFrame) + " ms/frame (worst "
                + oneDecimal(maxNanosPerFrame / 1_000_000.0) + "), " + (long) (visited / perFrame) + " sections/frame"
                + " (at most " + maxVisitedPerFrame + ")";
        nanos = 0;
        frames = 0;
        visited = 0;
        maxVisitedPerFrame = 0;
        maxNanosPerFrame = 0;
        return report;
    }

    private static String oneDecimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
