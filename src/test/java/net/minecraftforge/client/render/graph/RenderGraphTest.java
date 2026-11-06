package net.minecraftforge.client.render.graph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The graph's ordering contract: edges decide, priority breaks ties, labels break priority ties, and everything
 * that cannot be ordered is a startup error rather than a frame-time surprise.
 */
class RenderGraphTest {

    private RenderGraph graph;

    @BeforeEach
    void reset() {
        RenderGraphs.resetForTests();
        graph = RenderGraphs.get();
    }

    private static RenderGraphNode node(String label, int priority, List<String> log) {
        return new RenderGraphNode() {
            @Override
            public String label() {
                return label;
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public void run(RenderGraphContext context) {
                log.add(label);
            }
        };
    }

    private static RenderGraphContext context() {
        return new RenderGraphContext() {
            @Override
            public int width() {
                return 1;
            }

            @Override
            public int height() {
                return 1;
            }

            @Override
            public Object nativeContext() {
                return null;
            }
        };
    }

    @Test
    void everyRequiredPhaseHasAProviderFromTheStart() {
        for (String phase : RenderPhases.REQUIRED) {
            assertTrue(graph.canServe(phase), phase + " must be answered before anything is registered");
        }
        graph.freeze();
    }

    @Test
    void requiredPhasesRunInDependencyOrder() {
        graph.freeze();
        // Derived from RenderPhases#dependencies, not from the order the constants happen to be declared in:
        // dirty and reload feed setup, setup feeds the queries and the sky, the sky precedes the layers, and what
        // stands on the terrain is drawn after it.
        assertEquals(List.of(
                RenderPhases.TERRAIN_MARK_DIRTY,
                RenderPhases.TERRAIN_RELOAD,
                RenderPhases.TERRAIN_SETUP,
                RenderPhases.TERRAIN_QUERY,
                RenderPhases.TERRAIN_SKY,
                RenderPhases.TERRAIN_LAYER,
                RenderPhases.BLOCK_ENTITIES,
                RenderPhases.ENTITIES), graph.labels());
    }

    @Test
    void insertAfterPlacesTheNodeAfterItsAnchor() {
        graph.insertAfter(RenderPhases.TERRAIN_LAYER, node("mine", 0, new ArrayList<>()));
        graph.freeze();

        List<String> order = graph.labels();
        assertTrue(order.indexOf("mine") > order.indexOf(RenderPhases.TERRAIN_LAYER));
        // Not necessarily *immediately* after: block_entities anchors at the same place, and both are only
        // constrained to follow terrain.layer. Anchoring expresses before/after, never adjacency.
        assertTrue(order.indexOf("mine") > order.indexOf(RenderPhases.TERRAIN_LAYER));
    }

    @Test
    void insertBeforePlacesTheNodeBeforeItsAnchor() {
        graph.insertBefore(RenderPhases.TERRAIN_LAYER, node("mine", 0, new ArrayList<>()));
        graph.freeze();

        List<String> order = graph.labels();
        assertTrue(order.indexOf("mine") < order.indexOf(RenderPhases.TERRAIN_LAYER));
        // Same anchor as terrain.sky, which also precedes terrain.layer; priority and then the label decide which
        // of the two runs first, not the order they were registered in.
    }

    @Test
    void anAnchorMayBeDeclaredBeforeItIsRegistered() {
        // Edges are resolved at freeze, so registration order is not a constraint.
        graph.insertAfter("later", node("mine", 0, new ArrayList<>()));
        graph.add(node("later", 0, new ArrayList<>()));

        graph.freeze();

        List<String> order = graph.labels();
        assertTrue(order.indexOf("mine") > order.indexOf("later"));
    }

    @Test
    void replaceKeepsTheLabelSoOtherAnchorsSurvive() {
        List<String> log = new ArrayList<>();
        graph.replace(node(RenderPhases.TERRAIN_LAYER, 0, log));
        graph.insertAfter(RenderPhases.TERRAIN_LAYER, node("mine", 0, new ArrayList<>()));
        graph.freeze();

        List<String> order = graph.labels();
        assertEquals(1, order.stream().filter(RenderPhases.TERRAIN_LAYER::equals).count());
        assertTrue(order.indexOf("mine") > order.indexOf(RenderPhases.TERRAIN_LAYER));

        graph.run(context());
        assertEquals(List.of(RenderPhases.TERRAIN_LAYER), log, "the replacement must be what runs");
    }

    @Test
    void replaceRejectsAnUnknownLabel() {
        assertThrows(IllegalArgumentException.class, () -> graph.replace(node("nope", 0, new ArrayList<>())));
    }

    @Test
    void duplicateRegistrationIsRejected() {
        graph.add(node("mine", 0, new ArrayList<>()));
        assertThrows(IllegalArgumentException.class, () -> graph.add(node("mine", 0, new ArrayList<>())));
    }

    @Test
    void priorityOrdersUnconstrainedNodesAndLabelBreaksTies() {
        graph.add(node("b", 0, new ArrayList<>()));
        graph.add(node("a", 0, new ArrayList<>()));
        graph.add(node("early", -10, new ArrayList<>()));
        graph.freeze();

        List<String> order = graph.labels();
        assertTrue(order.indexOf("early") < order.indexOf("a"));
        assertTrue(order.indexOf("a") < order.indexOf("b"), "equal priority must fall back to the label");
    }

    @Test
    void registrationOrderDoesNotDecideAnything() {
        RenderGraph first = new RenderGraph();
        first.add(node("x", 0, new ArrayList<>()));
        first.add(node("y", 0, new ArrayList<>()));
        first.freeze();

        RenderGraph second = new RenderGraph();
        second.add(node("y", 0, new ArrayList<>()));
        second.add(node("x", 0, new ArrayList<>()));
        second.freeze();

        assertEquals(first.labels(), second.labels());
    }

    @Test
    void aCycleIsReportedWithTheLabelsInvolved() {
        graph.insertAfter("a", node("b", 0, new ArrayList<>()));
        graph.insertAfter("b", node("a", 0, new ArrayList<>()));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> graph.freeze());
        assertTrue(error.getMessage().contains("cycle"), error.getMessage());
        assertTrue(error.getMessage().contains("a"), error.getMessage());
        assertTrue(error.getMessage().contains("b"), error.getMessage());
    }

    @Test
    void aDanglingAnchorIsReported() {
        graph.insertAfter("missing", node("mine", 0, new ArrayList<>()));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> graph.freeze());
        assertTrue(error.getMessage().contains("missing"), error.getMessage());
    }

    @Test
    void removingARequiredPhaseIsCaughtAtFreeze() {
        graph.remove(RenderPhases.ENTITIES);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> graph.freeze());
        assertTrue(error.getMessage().contains(RenderPhases.ENTITIES), error.getMessage());
    }

    @Test
    void removeDropsTheEdgesThatMentionedTheNode() {
        graph.add(node("mine", 0, new ArrayList<>()));
        graph.insertAfter("mine", node("after", 0, new ArrayList<>()));
        graph.remove("mine");

        // 'after' is still registered and its edge is gone, so nothing dangles.
        graph.freeze();
        assertFalse(graph.labels().contains("mine"));
        assertTrue(graph.labels().contains("after"));
    }

    @Test
    void freezingIsOneWay() {
        graph.freeze();
        assertTrue(graph.isFrozen());
        assertThrows(IllegalStateException.class, () -> graph.add(node("late", 0, new ArrayList<>())));
        assertThrows(IllegalStateException.class, () -> graph.remove(RenderPhases.ENTITIES));
    }

    @Test
    void runBeforeFreezeIsRefused() {
        assertThrows(IllegalStateException.class, () -> graph.run(context()));
    }

    @Test
    void everyNodeRunsOncePerFrameInOrder() {
        List<String> log = new ArrayList<>();
        graph.replace(node(RenderPhases.TERRAIN_LAYER, 0, log));
        graph.replace(node(RenderPhases.ENTITIES, 0, log));
        graph.freeze();

        graph.run(context());
        graph.run(context());

        assertEquals(4, log.size(), "two nodes, two frames");
        assertEquals(List.of(RenderPhases.TERRAIN_LAYER, RenderPhases.ENTITIES), log.subList(0, 2));
    }
}
