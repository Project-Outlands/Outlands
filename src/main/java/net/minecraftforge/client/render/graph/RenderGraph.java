package net.minecraftforge.client.render.graph;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * The frame, as an ordered set of nodes.
 *
 * <p><b>Why a graph and not a list.</b> The loader can name its own phases in order, but it cannot name the pass a
 * mod wants to add between two of them. Ordering by label edges lets a mod say "after {@code terrain.layer}" and
 * survive the loader adding a phase, which an index never does.</p>
 *
 * <p><b>Anchors are declarative.</b> {@link #insertBefore} and {@link #insertAfter} name a label; there is no
 * insert-at-index, because the index a mod picked is invalidated by the next mod that inserts ahead of it. The
 * anchor does not have to exist yet when the edge is declared - it is resolved at {@link #freeze()}, so
 * registration order does not matter.</p>
 *
 * <p><b>Replacement keeps the label.</b> {@link #replace} requires the new node to carry the label it replaces.
 * That is what makes anchors survive a takeover: a mod that replaces {@code terrain.layer} leaves
 * {@code insertAfter(terrain.layer, ...)} from another mod pointing at the new node instead of at a dangling name.
 * A replacement under a different label would be a removal plus an addition, and should say so.</p>
 *
 * <p><b>Ordering is deterministic.</b> Edges decide what must follow what; among nodes that no edge separates,
 * {@link RenderGraphNode#priority()} decides, and the label breaks ties. Registration order never decides
 * anything, because 1.12.2 mod loading order is not fully deterministic.</p>
 *
 * <p><b>Freezing is one-way and does the checking.</b> Cycles, dangling anchors and missing required phases are
 * all found once, at the point where the frame is about to run - not during it. A graph that cannot be ordered is
 * a startup error with a name in it, not a crash in the middle of a frame.</p>
 */
public final class RenderGraph {

    private static final Logger LOGGER = LogManager.getLogger("Outlands|render");

    /** "from must run before to". Stored as labels so an anchor may be declared before it is registered. */
    private record Edge(String from, String to) {}

    private final Map<String, RenderGraphNode> nodes = new LinkedHashMap<>();
    private final List<Edge> edges = new ArrayList<>();
    private RenderGraphNode[] order;
    private boolean frozen;

    /**
     * A graph with every required phase answered by a no-op.
     *
     * <p>The no-ops are the point: a phase that has no provider is a phase nobody can draw, and there is no
     * per-phase fallback to the vanilla renderer. Starting from "all answered, none implemented" means the
     * completeness check is about regressions rather than about first-day wiring.</p>
     */
    public RenderGraph() {
        for (String phase : RenderPhases.REQUIRED) {
            nodes.put(phase, new NoopNode(phase));
        }
        // The loader's own phases have real dependencies between them; see RenderPhases#dependencies.
        for (String[] dependency : RenderPhases.dependencies()) {
            edges.add(new Edge(dependency[0], dependency[1]));
        }
    }

    /** Adds a node with no ordering constraints. It runs in priority order relative to other unconstrained nodes. */
    public synchronized void add(RenderGraphNode node) {
        checkMutable();
        put(node);
    }

    /**
     * Runs {@code node} before {@code anchor}. {@code anchor} may be registered later.
     *
     * <p>Before, not immediately before: several nodes may anchor at the same place, and among them
     * {@link RenderGraphNode#priority()} and then the label decide. A node that must sit directly between two
     * specific phases needs an edge to each of them, not a stronger reading of one edge.</p>
     */
    public synchronized void insertBefore(String anchor, RenderGraphNode node) {
        checkMutable();
        put(node);
        edges.add(new Edge(node.label(), anchor));
    }

    /** Runs {@code node} after {@code anchor}. {@code anchor} may be registered later; see {@link #insertBefore}. */
    public synchronized void insertAfter(String anchor, RenderGraphNode node) {
        checkMutable();
        put(node);
        edges.add(new Edge(anchor, node.label()));
    }

    /**
     * Takes over a phase, or any other labelled node.
     *
     * @param node the replacement; its label must be the one being replaced, which is what keeps other nodes'
     *             anchors valid
     * @throws IllegalArgumentException if the label is unknown or does not match
     */
    public synchronized void replace(RenderGraphNode node) {
        checkMutable();
        if (!nodes.containsKey(node.label())) {
            throw new IllegalArgumentException("Nothing is registered as '" + node.label() + "'; replace() cannot "
                    + "invent a label. Use add() or insertAfter() for a new node.");
        }
        nodes.put(node.label(), node);
    }

    /**
     * Removes a node and every edge that mentioned it.
     *
     * <p>Removing a required phase is allowed here and caught at {@link #freeze()}: the check belongs in one place,
     * and reporting "terrain.layer has no provider" is more useful than "you may not remove it".</p>
     */
    public synchronized void remove(String label) {
        checkMutable();
        if (nodes.remove(label) == null) {
            throw new IllegalArgumentException("Nothing is registered as '" + label + "'");
        }
        edges.removeIf(edge -> edge.from().equals(label) || edge.to().equals(label));
    }

    /** Whether a label currently has a provider. */
    public synchronized boolean canServe(String label) {
        return nodes.containsKey(label);
    }

    /** The labels in the order they will run, or the registration order before {@link #freeze()}. */
    public synchronized List<String> labels() {
        if (order != null) {
            return List.of(java.util.Arrays.stream(order).map(RenderGraphNode::label).toArray(String[]::new));
        }
        return List.copyOf(nodes.keySet());
    }

    /**
     * Orders the nodes and closes the graph.
     *
     * <p>Idempotent. After this the graph is read-only and {@link #run} is legal.</p>
     *
     * @throws IllegalStateException if an anchor is dangling, an edge forms a cycle, or a required phase has no
     *                               provider
     */
    public synchronized void freeze() {
        if (frozen) {
            return;
        }
        checkAnchorsExist();
        checkRequiredPhases();
        order = topologicalOrder();
        frozen = true;
        LOGGER.info("Render graph: {}", labels());
    }

    public synchronized boolean isFrozen() {
        return frozen;
    }

    /**
     * The nodes in the order they will run.
     *
     * <p>For a backend that has to do something to every node before the frame starts drawing - resolving the
     * textures they declared is the current reason - rather than just running them.</p>
     */
    public synchronized List<RenderGraphNode> nodes() {
        RenderGraphNode[] current = order;
        if (current == null) {
            throw new IllegalStateException("RenderGraph.nodes() before freeze()");
        }
        return List.of(current);
    }

    /** Runs every node in order. Allocates nothing. */
    public void run(RenderGraphContext context) {
        RenderGraphNode[] current = order;
        if (current == null) {
            throw new IllegalStateException("RenderGraph.run() before freeze()");
        }
        for (RenderGraphNode node : current) {
            node.run(context);
        }
    }

    private void put(RenderGraphNode node) {
        String label = node.label();
        if (label == null || label.isEmpty()) {
            throw new IllegalArgumentException("A render graph node needs a label");
        }
        RenderGraphNode previous = nodes.put(label, node);
        if (previous != null && !previous.equals(node)) {
            throw new IllegalArgumentException("'" + label + "' is already registered; use replace() to take it over");
        }
    }

    private void checkMutable() {
        if (frozen) {
            throw new IllegalStateException("The render graph is frozen");
        }
    }

    private void checkAnchorsExist() {
        Set<String> dangling = new LinkedHashSet<>();
        for (Edge edge : edges) {
            if (!nodes.containsKey(edge.from())) {
                dangling.add(edge.from());
            }
            if (!nodes.containsKey(edge.to())) {
                dangling.add(edge.to());
            }
        }
        if (!dangling.isEmpty()) {
            throw new IllegalStateException("Render graph anchors with no node: " + dangling
                    + ". A node was removed or never registered; an edge cannot point at nothing.");
        }
    }

    private void checkRequiredPhases() {
        List<String> missing = RenderPhases.REQUIRED.stream().filter(label -> !nodes.containsKey(label)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Render phases with no provider: " + missing
                    + ". Inside a Vulkan window no phase can fall back to the vanilla renderer, so a backend that "
                    + "cannot answer for all of them must not be selected.");
        }
    }

    /**
     * Kahn's algorithm with a priority queue, so the output is a total order that respects every edge and is
     * reproducible. Nodes with no incoming edge are ready immediately; ties go to lower priority, then to the
     * lexicographically smaller label.
     */
    private RenderGraphNode[] topologicalOrder() {
        Map<String, List<String>> successors = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        for (String label : nodes.keySet()) {
            successors.put(label, new ArrayList<>());
            inDegree.put(label, 0);
        }
        for (Edge edge : edges) {
            successors.get(edge.from()).add(edge.to());
            inDegree.merge(edge.to(), 1, Integer::sum);
        }

        Comparator<String> ordering = Comparator
                .comparingInt((String label) -> nodes.get(label).priority())
                .thenComparing(Comparator.naturalOrder());

        PriorityQueue<String> ready = new PriorityQueue<>(ordering);
        inDegree.forEach((label, degree) -> {
            if (degree == 0) {
                ready.add(label);
            }
        });

        List<RenderGraphNode> sorted = new ArrayList<>(nodes.size());
        while (!ready.isEmpty()) {
            String label = ready.poll();
            sorted.add(nodes.get(label));
            for (String next : successors.get(label)) {
                if (inDegree.merge(next, -1, Integer::sum) == 0) {
                    ready.add(next);
                }
            }
        }

        if (sorted.size() != nodes.size()) {
            Set<String> stuck = new HashSet<>(nodes.keySet());
            sorted.forEach(node -> stuck.remove(node.label()));
            throw new IllegalStateException("Render graph has a cycle among: " + stuck
                    + ". Ordering edges must form a DAG; a cycle means two nodes each insist on running first.");
        }
        return sorted.toArray(new RenderGraphNode[0]);
    }

    /** A phase that is answered but does nothing. Replaced when the phase is actually implemented. */
    private record NoopNode(String label) implements RenderGraphNode {
        @Override
        public void run(RenderGraphContext context) {
            // Intentionally empty: the phase exists so the completeness check passes, not to draw anything.
        }
    }
}
