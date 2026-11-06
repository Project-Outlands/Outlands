package net.minecraftforge.client.render.graph;

/**
 * The graph the process runs.
 *
 * <p>One per process, because the frame is one per process. Registration happens during mod loading and the graph
 * is frozen when the backend first runs a frame; after that it is read-only.</p>
 *
 * <p>Mods reach it through {@link #get()} and order themselves against {@link RenderPhases} labels. Nothing here
 * is backend-specific: which backend executes the graph is a separate question with a separate lifetime.</p>
 */
public final class RenderGraphs {

    private static RenderGraph graph = new RenderGraph();

    private RenderGraphs() {}

    public static RenderGraph get() {
        return graph;
    }

    /** Test hook: rebuilds the graph, so each test starts from the eight no-op phases. */
    static void resetForTests() {
        graph = new RenderGraph();
    }
}
