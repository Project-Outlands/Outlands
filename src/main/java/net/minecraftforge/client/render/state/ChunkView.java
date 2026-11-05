package net.minecraftforge.client.render.state;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.chunk.Chunk;
import org.jspecify.annotations.Nullable;

/**
 * Read-only view of the client's loaded chunks and of per-chunk flags vanilla does not expose.
 *
 * <p>Replaces the {@code @Accessor} on {@code ChunkProviderClient.loadedChunks} and on
 * {@code Chunk.hasEntities}.</p>
 *
 * <p><b>Threading.</b> Unlike the other views this one is read from chunk build threads, so it must be safe
 * for concurrent reads while the main thread adds or removes chunks.</p>
 *
 * <p><b>Lifetime.</b> The returned map is a live view. Do not retain it across a tick, and do not retain the
 * {@link Chunk} instances it yields: a chunk that has been unloaded stays reachable through a retained
 * reference and its contents are no longer meaningful.</p>
 */
public interface ChunkView {

    /** See {@link GlStateView#UNAVAILABLE} for the contract. */
    ChunkView UNAVAILABLE = new ChunkView() {
        @Override
        public Long2ObjectMap<Chunk> loadedChunks() {
            throw new UnsupportedOperationException(
                    "chunk data is not readable on this backend; register a ChunkView first");
        }

        @Override
        public boolean hasEntities(Chunk chunk) {
            throw new UnsupportedOperationException(
                    "chunk data is not readable on this backend; register a ChunkView first");
        }
    };

    /**
     * The chunk provider's loaded chunk map, keyed by {@code ChunkPos.asLong(x, z)}.
     *
     * <p>The map is exposed as a read-only view: mutation attempts must fail rather than silently corrupt
     * the provider's state.</p>
     */
    Long2ObjectMap<Chunk> loadedChunks();

    /**
     * Vanilla's {@code hasEntities} flag for a chunk.
     *
     * <p>Vanilla keeps this field private with no accessor; it is needed to decide whether a chunk can be
     * skipped when gathering entities.</p>
     */
    boolean hasEntities(Chunk chunk);

    static boolean isAvailable(@Nullable ChunkView view) {
        return view != null && view != UNAVAILABLE;
    }
}
