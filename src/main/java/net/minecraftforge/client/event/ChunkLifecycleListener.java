package net.minecraftforge.client.event;

import net.minecraft.world.World;

/**
 * Listener for chunk and world lifecycle events. All methods are default so an implementation only overrides
 * what it needs.
 *
 * @see ChunkLifecycle for the timing, threading and exception contracts
 */
public interface ChunkLifecycleListener {

    /**
     * A chunk was added to the client provider.
     *
     * <p>Fires <b>after</b> the chunk is present in the provider's map and marked loaded, so a listener may
     * look the chunk up.</p>
     */
    default void onChunkLoad(World world, int chunkX, int chunkZ) {}

    /**
     * A chunk was removed from the client provider.
     *
     * <p>Fires <b>after</b> {@code Chunk#onUnload}.</p>
     */
    default void onChunkUnload(World world, int chunkX, int chunkZ) {}

    /** The provider's per-tick reconciliation pass. */
    default void onClientChunkTick(World world) {}

    /**
     * The client world is being replaced or discarded.
     *
     * <p>This is the only reliable cleanup point for per-world state: chunk unload events are not guaranteed
     * to arrive during a world switch.</p>
     */
    default void onWorldUnload(World world) {}
}
