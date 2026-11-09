package net.minecraftforge.client.render.state.backend;

import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import net.minecraftforge.client.render.vertex.DrawState;
import net.minecraftforge.client.render.vertex.FrameDraws;
import net.minecraftforge.client.render.vertex.VertexLayout;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Display lists on the Vulkan backend: the recorded draws, kept as bytes.
 *
 * <p><b>Why recording is separable from drawing here.</b> A display list exists to avoid rebuilding static geometry.
 * The build pass produces vertex bytes through the ordinary stream path; the only thing that makes it a list is
 * that those bytes are kept instead of submitted. So this class holds them, and {@link #call} is the one operation
 * that needs a frame.</p>
 *
 * <p><b>What is kept per draw.</b> The layout, the primitive mode, the vertex count and the bytes - exactly the
 * arguments of {@link net.minecraftforge.client.render.vertex.VertexStreamBackend#draw}, so a replay is the same
 * call with the same arguments and no re-tessellation. The layout is a translation of the caller's
 * {@code VertexFormat}, so it is identical for every draw in a list and could be hoisted later; keeping it per draw
 * means a future multi-format list needs no change.</p>
 */
final class DisplayListVk implements DisplayListBackend {

    /** One recorded draw. */
    record Draw(VertexLayout layout, int mode, int vertexCount, byte[] vertices) {}

    private final Int2ObjectMap<List<Draw>> lists = new Int2ObjectLinkedOpenHashMap<>();
    private final Int2ObjectMap<List<Draw>> inProgress = new Int2ObjectOpenHashMap<>();
    private Integer recording;
    private int nextName = 1;

    @Override
    public int generate(int range) {
        if (range <= 0) {
            throw new IllegalArgumentException("a display list range must be positive, got " + range);
        }
        int base = nextName;
        nextName += range;
        for (int i = 0; i < range; i++) {
            lists.put(base + i, new ArrayList<>());
        }
        return base;
    }

    @Override
    public void begin(int id, int glMode) {
        if (recording != null) {
            throw new IllegalStateException("already recording display list " + recording
                    + "; GL does not allow nesting");
        }
        if (glMode != 4864 && glMode != 4865) {
            throw new IllegalArgumentException("unknown display list compile mode " + glMode);
        }
        if (!lists.containsKey(id)) {
            throw new IllegalStateException("recording into ungenerated display list " + id);
        }
        recording = id;
        inProgress.put(id, new ArrayList<>());
        if (glMode == 4865) {
            // GL_COMPILE_AND_EXECUTE also runs the commands as it records them. Vanilla does not use it; saying so
            // beats pretending the distinction does not exist.
            throw new UnsupportedOperationException(
                    "GL_COMPILE_AND_EXECUTE is not supported: executing while recording needs a frame, which is the"
                            + " same reason call() is unimplemented");
        }
    }

    @Override
    public void end() {
        if (recording == null) {
            throw new IllegalStateException("glEndList without glNewList");
        }
        int id = recording;
        List<Draw> raw = inProgress.remove(id);
        lists.put(id, consolidate(raw));
        recording = null;
    }

    private static List<Draw> consolidate(List<Draw> raw) {
        if (raw == null || raw.size() <= 1) {
            return raw;
        }
        List<Draw> merged = new ArrayList<>(raw.size());
        Draw current = null;
        for (int i = 0; i < raw.size(); i++) {
            Draw draw = raw.get(i);
            if (current == null) {
                current = draw;
            } else if (canMerge(current, draw)) {
                int totalVertices = current.vertexCount() + draw.vertexCount();
                byte[] combined = new byte[current.vertices().length + draw.vertices().length];
                System.arraycopy(current.vertices(), 0, combined, 0, current.vertices().length);
                System.arraycopy(draw.vertices(), 0, combined, current.vertices().length, draw.vertices().length);
                current = new Draw(current.layout(), current.mode(), totalVertices, combined);
            } else {
                merged.add(current);
                current = draw;
            }
        }
        if (current != null) {
            merged.add(current);
        }
        return merged;
    }

    private static boolean canMerge(Draw a, Draw b) {
        if (a.mode() != b.mode() || !a.layout().equals(b.layout())) {
            return false;
        }
        // Independent primitive modes: QUADS (7), TRIANGLES (4), LINES (1), POINTS (0)
        return a.mode() == 7 || a.mode() == 4 || a.mode() == 1 || a.mode() == 0;
    }

    @Override
    public Integer recording() {
        return recording;
    }

    @Override
    public void call(int id) {
        List<Draw> draws = lists.get(id);
        if (draws == null) {
            // GL's own rule: calling a name that is not a display list does nothing. Vanilla depends on it - the tail
            // of RenderGlobal.renderSky calls this.glSkyList2 unconditionally, but that field is only assigned on the
            // path that compiles lists, so with VBOs enabled it is still the -1 it was declared with. Throwing here
            // turned a harmless call the game has always made into a crash on the frame the sky first rendered.
            return;
        }
        if (!FrameDraws.isCollecting()) {
            int vertices = draws.stream().mapToInt(Draw::vertexCount).sum();
            throw new UnsupportedOperationException(
                    "cannot replay display list " + id + " on the Vulkan backend yet: it holds " + draws.size()
                            + " draws / " + vertices + " vertices, and replaying means drawing, which needs a frame that is collecting. Recording"
                            + " itself worked - that is why the list has contents. Needed: the backend to have begun"
                            + " collecting the frame, which happens once the device exists.");
        }
        // Replaying a list is recording its draws again: GL bakes the vertex data into the list and takes matrix and
        // enable state at call time, which is exactly what DrawState.current() is here.
        for (Draw draw : draws) {
            FrameDraws.record(draw.layout(), draw.mode(), draw.vertexCount(), draw.vertices(),
                    DrawState.current());
        }
    }

    @Override
    public void delete(int id, int range) {
        for (int i = 0; i < range; i++) {
            lists.remove(id + i);
            inProgress.remove(id + i);
        }
    }

    @Override
    public int drawCount(int id) {
        List<Draw> draws = lists.get(id);
        return draws == null ? 0 : draws.size();
    }

    /** Appends one draw to the list being recorded, if any. Returns whether it was consumed. */
    boolean capture(VertexLayout layout, int mode, int vertexCount, ByteBuffer data) {
        if (recording == null) {
            return false;
        }
        int id = recording;
        int bytes = vertexCount * layout.stride();
        byte[] copy = new byte[bytes];
        ByteBuffer view = data.duplicate();
        view.position(0);
        view.get(copy, 0, Math.min(bytes, view.remaining()));
        inProgress.get(id).add(new Draw(layout, mode, vertexCount, copy));
        return true;
    }
}
