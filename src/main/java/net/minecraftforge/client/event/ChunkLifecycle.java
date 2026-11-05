package net.minecraftforge.client.event;

import net.minecraft.world.World;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Chunk and world lifecycle notifications, driven from loader patches on {@code ChunkProviderClient}.
 *
 * <p><b>Why this exists.</b> Every renderer that needs to know when a chunk appears or disappears
 * currently mixes into {@code ChunkProviderClient#loadChunk} / {@code #unloadChunk} itself. That is a
 * per-renderer mixin for a piece of information the loader can publish once.</p>
 *
 * <p><b>Exception isolation.</b> A listener that throws is logged and skipped; it never aborts the
 * remaining listeners and never aborts the vanilla call it was triggered from.</p>
 *
 * <p><b>Threading.</b> All callbacks run on the client main thread.</p>
 *
 * <p><b>Pairing is not guaranteed.</b> A world switch may discard chunks without emitting
 * {@link #onChunkUnload}. Anything that allocates per-chunk state must therefore also be able to clean up
 * on {@link #onWorldUnload}.</p>
 */
public final class ChunkLifecycle {

    private static final List<ChunkLifecycleListener> LISTENERS = new CopyOnWriteArrayList<>();

    private ChunkLifecycle() {}

    public static void register(ChunkLifecycleListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        if (LISTENERS.stream().anyMatch(registered -> registered == listener)) {
            throw new IllegalArgumentException("listener is already registered: " + listener.getClass().getName());
        }
        LISTENERS.add(listener);
    }

    public static boolean unregister(ChunkLifecycleListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        return LISTENERS.removeIf(registered -> registered == listener);
    }

    /** Called by the {@code ChunkProviderClient#loadChunk} patch, after the chunk is in the map and marked loaded. */
    public static void fireChunkLoad(World world, int chunkX, int chunkZ) {
        for (ChunkLifecycleListener listener : LISTENERS) {
            try {
                listener.onChunkLoad(world, chunkX, chunkZ);
            } catch (Throwable t) {
                log("onChunkLoad", listener, t);
            }
        }
    }

    /** Called by the {@code ChunkProviderClient#unloadChunk} patch, after {@code Chunk#onUnload}. */
    public static void fireChunkUnload(World world, int chunkX, int chunkZ) {
        for (ChunkLifecycleListener listener : LISTENERS) {
            try {
                listener.onChunkUnload(world, chunkX, chunkZ);
            } catch (Throwable t) {
                log("onChunkUnload", listener, t);
            }
        }
    }

    /** Called by the {@code ChunkProviderClient#tick} patch, at return. */
    public static void fireClientChunkTick(World world) {
        for (ChunkLifecycleListener listener : LISTENERS) {
            try {
                listener.onClientChunkTick(world);
            } catch (Throwable t) {
                log("onClientChunkTick", listener, t);
            }
        }
    }

    /**
     * Called when a client world is replaced or discarded.
     *
     * <p>This is the cleanup backstop: chunk unload events may be skipped during a world switch, so
     * per-world state (including {@code top.outlands.attach.Attachments}) is released here.</p>
     */
    public static void fireWorldUnload(World world) {
        for (ChunkLifecycleListener listener : LISTENERS) {
            try {
                listener.onWorldUnload(world);
            } catch (Throwable t) {
                log("onWorldUnload", listener, t);
            }
        }
    }

    private static void log(String what, ChunkLifecycleListener listener, Throwable t) {
        org.apache.logging.log4j.LogManager.getLogger("Outlands|chunklifecycle")
                .error("{} listener {} failed", what, listener.getClass().getName(), t);
    }
}
