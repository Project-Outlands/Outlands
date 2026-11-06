package net.minecraftforge.client.render.graph;

import java.util.List;

/**
 * The phases the loader itself depends on.
 *
 * <p>These are the renderer's entry points, named after what they do rather than after the method they used to
 * hang off. They are <b>stable strings, not an enum</b>, because an external renderer has to be able to name them
 * and evolve alongside them without a recompile; the loader keeps the list, and the list is what
 * {@link RenderGraph#freeze()} checks completeness against.</p>
 *
 * <p>A phase is not optional. Inside a Vulkan window no phase can be drawn by the vanilla renderer instead, so a
 * backend that cannot answer "who draws this" for all of them must not have been selected. That is why these
 * start out as no-op nodes rather than as nothing: the answer exists from the first frame, and replacing the node
 * is how a phase gets implemented.</p>
 */
public final class RenderPhases {

    public static final String TERRAIN_SETUP = "terrain.setup";
    public static final String TERRAIN_LAYER = "terrain.layer";
    public static final String TERRAIN_MARK_DIRTY = "terrain.mark_dirty";
    public static final String TERRAIN_RELOAD = "terrain.reload";
    public static final String TERRAIN_QUERY = "terrain.query";
    public static final String TERRAIN_SKY = "terrain.sky";
    public static final String ENTITIES = "entities";
    public static final String BLOCK_ENTITIES = "block_entities";

    /** Every phase that must have a provider. */
    public static final List<String> REQUIRED = List.of(
            TERRAIN_SETUP,
            TERRAIN_QUERY,
            TERRAIN_MARK_DIRTY,
            TERRAIN_RELOAD,
            TERRAIN_SKY,
            TERRAIN_LAYER,
            BLOCK_ENTITIES,
            ENTITIES);

    /**
     * The real dependencies between these phases, as {@code {before, after}} pairs.
     *
     * <p>They are edges rather than a fixed list because they are <i>facts</i>, not a preference: the vanilla
     * renderer cannot draw a layer before {@code setupTerrain} has chosen what is visible, and cannot draw
     * entities before the block entities they stand in front of. Encoding them as order would mean the order is
     * the only thing that can express them, and a mod inserting between two phases would have nothing to anchor
     * to. As edges, they survive insertion and say why they exist.</p>
     *
     * <p>Without them the eight phases are mutually unconstrained, and the tiebreak would put
     * {@code block_entities} first - deterministic, and wrong.</p>
     */
    public static List<String[]> dependencies() {
        return List.of(
                // What is dirty and what has been rebuilt are both inputs to choosing what to draw.
                new String[] {TERRAIN_MARK_DIRTY, TERRAIN_SETUP},
                new String[] {TERRAIN_RELOAD, TERRAIN_SETUP},
                // Queries and the sky read the result of that choice.
                new String[] {TERRAIN_SETUP, TERRAIN_QUERY},
                new String[] {TERRAIN_SETUP, TERRAIN_SKY},
                // Then the layers, then what stands on them.
                new String[] {TERRAIN_SKY, TERRAIN_LAYER},
                new String[] {TERRAIN_LAYER, BLOCK_ENTITIES},
                new String[] {BLOCK_ENTITIES, ENTITIES});
    }

    private RenderPhases() {}
}
