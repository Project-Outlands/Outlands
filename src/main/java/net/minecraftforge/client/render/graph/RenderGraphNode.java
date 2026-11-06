package net.minecraftforge.client.render.graph;

/**
 * One unit of work in the frame.
 *
 * <p><b>Granularity is a pass, not a draw.</b> A node is "draw the terrain's solid layer", not "draw this quad".
 * Vanilla issues tens of thousands of draw calls per frame; a graph that dispatched per draw would spend more time
 * in the scheduler than in the driver. The node count is meant to be in the tens.</p>
 *
 * <p><b>The label is the identity.</b> Anchors, replacement and diagnostics all name a node by its label, so a
 * label has to be stable and unique. The phase labels in {@link RenderPhases} are the ones the loader itself
 * depends on; anything else belongs to whoever registered it.</p>
 */
public interface RenderGraphNode {

    /** Stable, unique label. Used as the anchor other nodes order themselves against. */
    String label();

    /**
     * Ordering among nodes that are not otherwise ordered relative to each other. Lower runs first.
     *
     * <p>This is a tiebreak, not a scheduling priority: it cannot move a node across an edge. When two nodes share
     * a priority the label decides, so the result is reproducible regardless of registration order - which matters
     * because mod loading order is not fully deterministic in 1.12.2.</p>
     */
    default int priority() {
        return 0;
    }

    /** Runs the node. Called once per frame, inside the backend's frame. */
    void run(RenderGraphContext context);
}
