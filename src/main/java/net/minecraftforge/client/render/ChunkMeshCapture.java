package net.minecraftforge.client.render;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.world.World;
import net.minecraftforge.client.event.ChunkLifecycle;
import net.minecraftforge.client.event.ChunkLifecycleListener;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Where finished chunk meshes go when the renderer is not OpenGL.
 *
 * <p><b>Incremental capture stream.</b> Chunk meshing workers enqueue updates and removals as chunks are built,
 * repositioned, or unloaded. The render thread drains these events on frame boundaries to incrementally update the
 * GPU arena sub-allocator without full repacking or GPU pipeline stalls.</p>
 */
public final class ChunkMeshCapture {

    private static final Logger LOGGER = LogManager.getLogger("Outlands|render");

    /** A finished chunk layer, as vanilla produced it. */
    public record Captured(int chunkX, int chunkY, int chunkZ, BlockRenderLayer layer, byte[] vertices,
                           int quadCount) {}

    /** Uniquely identifies a chunk layer mesh. */
    public record Key(int chunkX, int chunkY, int chunkZ, BlockRenderLayer layer) {}

    /** An incremental mesh event for the GPU backend. */
    public record CaptureEvent(Type type, Key key, Captured capture) {
        public enum Type { UPDATE, REMOVE, CLEAR }

        public static CaptureEvent update(Key key, Captured capture) {
            return new CaptureEvent(Type.UPDATE, key, capture);
        }

        public static CaptureEvent remove(Key key) {
            return new CaptureEvent(Type.REMOVE, key, null);
        }

        public static CaptureEvent clear() {
            return new CaptureEvent(Type.CLEAR, null, null);
        }
    }

    /** Whether captures are being kept. */
    private static volatile boolean enabled;

    /** Active chunk layer keys on the GPU backend (does NOT retain heavy vertex bytes in heap). */
    private static final java.util.Set<Key> ACTIVE_KEYS = ConcurrentHashMap.newKeySet();

    /** Incremental capture event queue consumed by the render thread. */
    private static final Queue<CaptureEvent> PENDING_EVENTS = new ConcurrentLinkedQueue<>();

    private static final AtomicLong captured = new AtomicLong();
    private static final AtomicLong dropped = new AtomicLong();

    private static final ChunkLifecycleListener LIFECYCLE_LISTENER = new ChunkLifecycleListener() {
        @Override
        public void onChunkUnload(World world, int chunkX, int chunkZ) {
            invalidateChunkColumn(chunkX, chunkZ);
        }

        @Override
        public void onWorldUnload(World world) {
            clear();
        }
    };

    private ChunkMeshCapture() {}

    /**
     * Turns capture on. Called by the backend when it starts.
     */
    public static void enable() {
        enabled = true;
        ChunkLifecycle.register(LIFECYCLE_LISTENER);
    }

    public static void disable() {
        enabled = false;
        ChunkLifecycle.unregister(LIFECYCLE_LISTENER);
        ACTIVE_KEYS.clear();
        PENDING_EVENTS.clear();
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Clears all current meshes and notifies the backend to reset its arena allocations. */
    public static void clear() {
        ACTIVE_KEYS.clear();
        PENDING_EVENTS.clear();
        if (enabled) {
            PENDING_EVENTS.add(CaptureEvent.clear());
        }
    }

    /** Whether there are pending capture events for the render thread. */
    public static boolean hasPendingEvents() {
        return !PENDING_EVENTS.isEmpty();
    }

    /** Drains all pending capture events. Called on the render thread. */
    public static List<CaptureEvent> drainEvents() {
        List<CaptureEvent> list = new ArrayList<>();
        CaptureEvent event;
        while ((event = PENDING_EVENTS.poll()) != null) {
            list.add(event);
        }
        return list;
    }

    /**
     * Removes and invalidates all layers for the chunk at (x, y, z).
     * Called when a RenderChunk moves coordinates or deletes GL resources.
     */
    public static void invalidateChunk(int x, int y, int z) {
        if (!enabled) {
            return;
        }
        for (BlockRenderLayer layer : BlockRenderLayer.values()) {
            Key key = new Key(x, y, z, layer);
            if (ACTIVE_KEYS.remove(key)) {
                PENDING_EVENTS.add(CaptureEvent.remove(key));
            }
        }
    }

    /**
     * Removes and invalidates all 16 render chunk sections in the column (chunkX, chunkZ).
     * Called on chunk unload.
     */
    public static void invalidateChunkColumn(int chunkX, int chunkZ) {
        if (!enabled) {
            return;
        }
        int originX = chunkX * 16;
        int originZ = chunkZ * 16;
        for (int y = 0; y < 256; y += 16) {
            invalidateChunk(originX, y, originZ);
        }
    }

    /**
     * @deprecated Meshes are streamed incrementally to the GPU backend and not retained in heap.
     */
    @Deprecated
    public static java.util.List<Captured> current() {
        return java.util.List.of();
    }

    /** How many captures have ever been taken. */
    public static long version() {
        return captured.get();
    }

    /**
     * Called from the patched {@code uploadChunk} before it picks a GL path.
     *
     * @return whether the capture took over, in which case the caller must not touch GL
     */
    public static boolean capture(BlockRenderLayer layer, BufferBuilder buffer, RenderChunk chunk,
                                  CompiledChunk compiled) {
        if (!enabled) {
            return false;
        }
        try {
            int quadCount = buffer.getVertexCount() / 4;
            int chunkX = chunk.getPosition().getX();
            int chunkY = chunk.getPosition().getY();
            int chunkZ = chunk.getPosition().getZ();
            Key key = new Key(chunkX, chunkY, chunkZ, layer);

            if (quadCount == 0) {
                if (ACTIVE_KEYS.remove(key)) {
                    PENDING_EVENTS.add(CaptureEvent.remove(key));
                    captured.incrementAndGet();
                }
                return true;
            }

            java.nio.ByteBuffer source = buffer.getByteBuffer();
            source.position(0);
            byte[] compactVertices = net.minecraftforge.client.render.vulkan.TerrainVertexFormat.compact(source, quadCount);

            Captured capture = new Captured(chunkX, chunkY, chunkZ, layer, compactVertices, quadCount);
            ACTIVE_KEYS.add(key);
            PENDING_EVENTS.add(CaptureEvent.update(key, capture));
            captured.incrementAndGet();

            // Also check if any other layers became empty in this compiled chunk
            if (compiled != null) {
                for (BlockRenderLayer l : BlockRenderLayer.values()) {
                    if (l != layer && compiled.isLayerEmpty(l)) {
                        Key emptyKey = new Key(chunkX, chunkY, chunkZ, l);
                        if (ACTIVE_KEYS.remove(emptyKey)) {
                            PENDING_EVENTS.add(CaptureEvent.remove(emptyKey));
                        }
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            dropped.incrementAndGet();
            LOGGER.error("Failed to capture a chunk mesh for {} at {}; falling back to the GL path", layer, chunk, t);
            return false;
        }
    }

    /** Diagnostics: how many captures were taken, and how many failed. */
    public static String statistics() {
        return "captured=" + captured.get() + " failed=" + dropped.get() + " active=" + ACTIVE_KEYS.size()
                + " pending=" + PENDING_EVENTS.size();
    }

    static void markLayerActiveForTest(int x, int y, int z, BlockRenderLayer layer) {
        ACTIVE_KEYS.add(new Key(x, y, z, layer));
    }

    static boolean isLayerActive(int x, int y, int z, BlockRenderLayer layer) {
        return ACTIVE_KEYS.contains(new Key(x, y, z, layer));
    }
}
