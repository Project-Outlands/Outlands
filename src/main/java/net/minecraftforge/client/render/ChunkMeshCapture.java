package net.minecraftforge.client.render;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.BlockRenderLayer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Where finished chunk meshes go when the renderer is not OpenGL.
 *
 * <p><b>Why a capture point and not a GL interception.</b> Vanilla's chunk build ends with an upload: {@code
 * ChunkRenderDispatcher.uploadChunk} hands a finished {@link BufferBuilder} to either a VBO or a display list. Both
 * are OpenGL. So the patch that calls this class sits before that choice, and it is the only place in the chunk
 * pipeline that knows the layer, the bytes and the chunk at once.</p>
 *
 * <p><b>Why a queue.</b> Uploads happen on the chunk-build workers, and in vanilla they are already marshalled to
 * the client thread before touching GL. The backend draws on the render thread, from inside the graph. A queue
 * between them keeps the two from having to know about each other's threading, and lets a rebuild that finishes
 * mid-frame be picked up next frame instead of forcing a stall.</p>
 *
 * <p><b>What a capture carries.</b> The layer, the chunk's origin, and the vertex bytes in vanilla's own format -
 * the format {@code TerrainVertexFormat} describes, untouched. Nothing is converted here; conversion belongs to
 * whoever builds GPU meshes, which is the backend.</p>
 *
 * <p><b>Disabled by default.</b> While disabled the patch is a single field read and the vanilla path runs
 * unchanged, so this costs nothing on a GL renderer and cannot change its behaviour.</p>
 *
 * <p><b>What this class keeps, and what it does not.</b> The latest capture per chunk and layer, which is the set the
 * backend installs each time it changes - a rebuild supersedes the previous mesh for that layer, so the map is the
 * current world and not a work queue. The backend is told when it changed through {@link #version()}; it does not
 * consume entries, because consuming them would mean installing only the newest chunk and dropping the rest.</p>
 */
public final class ChunkMeshCapture {

    private static final Logger LOGGER = LogManager.getLogger("Outlands|render");

    /** A finished chunk layer, as vanilla produced it. */
    public record Captured(int chunkX, int chunkY, int chunkZ, BlockRenderLayer layer, byte[] vertices,
                           int quadCount) {}

    /** Whether captures are being kept. */
    private static volatile boolean enabled;

    /** Latest capture per (chunk, layer). A rebuild supersedes the previous mesh for the same layer. */
    private static final Map<Key, Captured> CURRENT = new ConcurrentHashMap<>();

    private static final AtomicLong captured = new AtomicLong();
    private static final AtomicLong dropped = new AtomicLong();

    private record Key(int chunkX, int chunkY, int chunkZ, BlockRenderLayer layer) {}

    private ChunkMeshCapture() {}

    /**
     * Turns capture on. Called by the backend when it starts.
     *
     * <p>Turning it off again drops what was kept: the meshes are only valid against the renderer that asked for
     * them.</p>
     */
    public static void enable() {
        enabled = true;
    }

    public static void disable() {
        enabled = false;
        CURRENT.clear();
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Every capture that is still current, one per chunk and layer. Read by the render thread. */
    public static java.util.List<Captured> current() {
        return java.util.List.copyOf(CURRENT.values());
    }

    /**
     * How many captures have ever been taken.
     *
     * <p>The backend compares this against the value it last installed, so a frame with nothing new does not rebuild
     * the packed terrain buffers.</p>
     */
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
            // The length comes from the buffer, not from a vertex-format constant: this class captures vanilla's
            // bytes and has no business knowing what a Vulkan pipeline thinks a vertex is. finishDrawing() has
            // already set position and limit, so the remaining bytes are exactly the finished mesh.
            java.nio.ByteBuffer source = buffer.getByteBuffer();
            source.position(0);
            byte[] vertices = new byte[source.remaining()];
            source.get(vertices);
            source.position(0);

            Captured capture = new Captured(chunk.getPosition().getX(), chunk.getPosition().getY(),
                    chunk.getPosition().getZ(), layer, vertices, quadCount);
            CURRENT.put(new Key(capture.chunkX(), capture.chunkY(), capture.chunkZ(), layer), capture);
            captured.incrementAndGet();
            return true;
        } catch (Throwable t) {
            // A capture failure must not break chunk building: fall through to the vanilla path, which will do
            // whatever it does with no GL context, and log once so it is diagnosable.
            dropped.incrementAndGet();
            LOGGER.error("Failed to capture a chunk mesh for {} at {}; falling back to the GL path", layer, chunk, t);
            return false;
        }
    }

    /** Diagnostics: how many captures were taken, and how many failed. */
    public static String statistics() {
        return "captured=" + captured.get() + " failed=" + dropped.get() + " current=" + CURRENT.size();
    }
}
