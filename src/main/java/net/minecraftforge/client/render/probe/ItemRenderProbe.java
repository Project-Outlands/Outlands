package net.minecraftforge.client.render.probe;

/**
 * What item rendering costs per frame, measured rather than assumed.
 *
 * <p><b>Why this exists.</b> A display-list cache for items (the trick Sodium's descendants all carry) is only worth
 * building if emitting item quads is actually a cost, and the honest answer depends on the screen: a hotbar is a
 * handful of items, a JEI page of high-poly models or a wall of large chests is hundreds. Guessing either way is how a
 * cache gets built for a case that never happens, or skipped for the one that does.</p>
 *
 * <p><b>What it measures.</b> The wall time of {@code RenderItem.renderItem(ItemStack, IBakedModel)} - the one place
 * both the world and the GUI paths converge - and how many of the frame's draws were recorded inside it. Nesting is
 * timed once: a built-in renderer that draws another item is inside the outer measurement, not added to it.</p>
 *
 * <p><b>Cost.</b> Two {@code nanoTime} calls per top-level item render, plus one boolean read per recorded draw. On a
 * frame with a hundred items that is a few microseconds, which is why it is on by default;
 * {@code -Doutlands.probe.items=false} turns it off.</p>
 */
public final class ItemRenderProbe {

    private static final boolean ENABLED = !"false".equals(System.getProperty("outlands.probe.items"));

    private static int depth;
    private static long startedAt;
    private static long renders;
    private static long nanos;
    private static long draws;

    private ItemRenderProbe() {}

    /** Enters one item render. Every call must be paired with {@link #end()} in a finally block. */
    public static void begin() {
        if (!ENABLED) {
            return;
        }
        if (depth++ == 0) {
            startedAt = System.nanoTime();
        }
    }

    /** Leaves one item render. */
    public static void end() {
        if (!ENABLED) {
            return;
        }
        if (depth > 0 && --depth == 0) {
            nanos += System.nanoTime() - startedAt;
            renders++;
        }
    }

    /** Whether the calling thread is inside an item render, i.e. whether a draw belongs to one. */
    public static boolean active() {
        return depth > 0;
    }

    /** Records one draw that was emitted from inside an item render. */
    public static void countDraw() {
        draws++;
    }

    /**
     * The totals since the last call, formatted for the renderer's line, and reset.
     *
     * @param frames how many frames elapsed since the last report, used for the per-frame figures
     */
    public static String reportAndReset(long frames) {
        if (!ENABLED) {
            return "item rendering: probe off";
        }
        long reportRenders = renders;
        long reportNanos = nanos;
        long reportDraws = draws;
        renders = 0;
        nanos = 0;
        draws = 0;
        if (reportRenders == 0 && reportDraws == 0) {
            return "item rendering: none";
        }
        double perFrame = frames <= 0 ? 0.0 : (double) frames;
        return "item rendering: " + reportRenders + " renders"
                + (frames > 0 ? " (" + oneDecimal(reportRenders / perFrame) + "/frame)" : "")
                + ", " + oneDecimal(reportNanos / 1_000_000.0) + " ms"
                + (frames > 0 ? " (" + oneDecimal(reportNanos / 1_000_000.0 / perFrame) + " ms/frame)" : "")
                + ", " + reportDraws + " draws"
                + (frames > 0 ? " (" + oneDecimal(reportDraws / perFrame) + "/frame)" : "");
    }

    private static String oneDecimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
