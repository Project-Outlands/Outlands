package net.minecraftforge.client.render.vertex;

import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * The draws a frame asked for, held until the frame is recorded.
 *
 * <p><b>Why a list and not a command buffer.</b> Vanilla issues its frame before {@code Display.update()} hands it
 * over, so its draws arrive while no command buffer is recording. Handing them a command buffer would mean
 * splitting the backend's frame in two; holding them as data means the existing record-and-submit path stays as it
 * is, and the draws are replayed inside the rendering instance it already opens.</p>
 *
 * <p><b>Order is preserved.</b> Vanilla draws in the order it always did, so the list replays into the same
 * picture - the only difference from a command buffer is that the work happens at the end of the frame instead of
 * during it.</p>
 *
 * <p><b>Not everything in the list is a draw.</b> {@code glCopyTexSubImage2D} reads the framebuffer the frame is
 * painting into, so it has to happen at the point in the sequence where the game issued it - after the draws it
 * reads and before the draws that sample the result. That is why the list holds operations rather than draws: a
 * main-menu frame is "draw the panorama, copy the framebuffer into a texture, draw that texture three times", and
 * reordering the middle step would blur nothing.</p>
 */
public final class FrameDraws {

    /**
     * The primitive modes whose vertices concatenate into the same picture: a triangle list, a strip, a quad list.
     *
     * <p>Left out on purpose: a fan shares its first vertex with every triangle, a polygon is one outline, a quad
     * strip's quads share edges, and two line loops joined would be one loop. Those stay separate rather than being
     * merged into something GL never drew.</p>
     */
    private static final int GL_TRIANGLES = 4;
    private static final int GL_TRIANGLE_STRIP = 5;
    private static final int GL_QUADS = 7;

    /** One thing the frame asked a backend to do, in the order it asked. */
    public sealed interface Operation permits Draw, FramebufferCopy, ReadPixels, TerrainLayer, Clear, TargetBind {}

    /**
     * One recorded draw: its vertices plus the state it was issued with.
     *
     * <p>The state is part of the draw, not of the frame. Reading it at replay time would apply one matrix and one
     * set of enable bits to every draw in the frame, which is not what GL did.</p>
     */
    public static final class Draw implements Operation {
        private VertexLayout layout;
        private int mode;
        private int vertexCount;
        private byte[] vertices;
        private byte[] ownedBuffer;
        private final DrawState state;

        public Draw() {
            this.state = new DrawState();
        }

        public Draw(VertexLayout layout, int mode, int vertexCount, byte[] vertices, DrawState state) {
            this.layout = layout;
            this.mode = mode;
            this.vertexCount = vertexCount;
            this.vertices = vertices;
            this.state = new DrawState();
            this.state.copyFrom(state);
        }

        public void set(VertexLayout layout, int mode, int vertexCount, byte[] vertices, DrawState stateSource) {
            this.layout = layout;
            this.mode = mode;
            this.vertexCount = vertexCount;
            this.vertices = vertices;
            this.state.copyFrom(stateSource);
        }

        public void set(VertexLayout layout, int mode, int vertexCount, ByteBuffer data, int bytes, DrawState stateSource) {
            this.layout = layout;
            this.mode = mode;
            this.vertexCount = vertexCount;
            if (this.ownedBuffer == null || this.ownedBuffer.length < bytes) {
                this.ownedBuffer = new byte[Math.max(bytes, this.ownedBuffer == null ? 256 : this.ownedBuffer.length * 2)];
            }
            int oldPos = data.position();
            data.position(0);
            data.get(this.ownedBuffer, 0, bytes);
            data.position(oldPos);
            this.vertices = this.ownedBuffer;
            this.state.copyFrom(stateSource);
        }

        public VertexLayout layout() { return layout; }
        public int mode() { return mode; }
        public int vertexCount() { return vertexCount; }
        public byte[] vertices() { return vertices; }
        public DrawState state() { return state; }

        /**
         * Whether {@code other} can be folded into this draw.
         *
         * <p>Same primitive mode, same vertex layout and the same state - then the two are one draw with more
         * vertices and the picture is unchanged. This is what turns text from one draw per glyph into one draw per
         * run of same-state glyphs: {@code FontRenderer} emits every glyph as its own {@code glBegin}/{@code glEnd},
         * and measured on a text-heavy frame that was 342 of 761 operations.</p>
         */
        public boolean mergeableWith(int otherMode, VertexLayout otherLayout, DrawState otherState) {
            return vertexCount > 0
                    && otherMode == mode
                    && (mode == GL_TRIANGLES || mode == GL_TRIANGLE_STRIP || mode == GL_QUADS)
                    && layout.equals(otherLayout)
                    && state.sameAs(otherState);
        }

        /**
         * Appends another draw's vertices, with the degenerate vertices a strip needs at the join.
         *
         * <p><b>Why a strip needs them and a list does not.</b> A strip's triangles are implied by vertex order, so two
         * strips butted together would have a real triangle spanning the gap. Duplicating the last vertex of what is
         * already here and the first vertex of the new segment makes every triangle across the join zero-area, which
         * rasterises nothing. An odd number of vertices needs one duplicate more, because the winding of a strip
         * alternates and the new segment has to start on the same parity it would have had as a draw of its own.</p>
         */
        public void append(int addedVertices, ByteBuffer data, int stride) {
            int used = vertexCount * stride;
            int degenerate = mode == GL_TRIANGLE_STRIP ? ((vertexCount & 1) == 0 ? 2 : 3) : 0;
            ensureOwned(used + (degenerate + addedVertices) * stride, used);

            int at = used;
            for (int i = 0; i < degenerate; i++) {
                if (i == degenerate - 1) {
                    // The first vertex of the new segment: the join collapses onto it and the segment then starts with
                    // the winding it would have had on its own.
                    data.get(0, vertices, at, stride);
                } else {
                    System.arraycopy(vertices, used - stride, vertices, at, stride);
                }
                at += stride;
            }
            data.get(0, vertices, at, addedVertices * stride);
            vertexCount += degenerate + addedVertices;
        }

        /**
         * Grows the owned array to hold {@code needed} bytes, keeping the {@code used} already in it.
         *
         * <p>Also takes ownership when the vertices are somebody else's array - the display-list replay records
         * captured bytes without copying them, and appending to one of those would write into a compiled list.</p>
         */
        private void ensureOwned(int needed, int used) {
            if (vertices != ownedBuffer || ownedBuffer == null || ownedBuffer.length < needed) {
                int capacity = Math.max(needed, Math.max(256, ownedBuffer == null ? 256 : ownedBuffer.length * 2));
                byte[] grown = new byte[capacity];
                if (used > 0) {
                    System.arraycopy(vertices, 0, grown, 0, used);
                }
                ownedBuffer = grown;
                vertices = grown;
            }
        }
    }

    /**
     * A framebuffer-to-texture copy, at its place in the sequence.
     */
    public record FramebufferCopy(int texture, int level, int xOffset, int yOffset, int x, int y, int width,
                                  int height) implements Operation {}

    /**
     * One {@code glReadPixels}: the framebuffer into the caller's buffer, at this point in the sequence.
     *
     * <p><b>Where the pixels come from.</b> The copy is recorded into the frame's command buffer like everything else,
     * so it reads the framebuffer exactly as GL would have: after the draws that preceded it and before the ones that
     * follow. That is the whole reason this is an operation and not an immediate call - the backend has not drawn
     * anything at the moment the game asks.</p>
     *
     * <p><b>When the caller sees them.</b> The buffer is filled after the frame's fence signals, which is later than
     * GL delivers them. A caller that reads the buffer in the same method gets what was there before; one that reads
     * it after {@code Display.update()} gets the right pixels. No mod in the pack reads synchronously, and the honest
     * alternative - starting the frame early at the call site - would restructure the frame for a feature nothing
     * uses that way.</p>
     */
    public record ReadPixels(int x, int y, int width, int height, int format, int type,
                             java.nio.ByteBuffer destination) implements Operation {}

    /**
     * One render layer of the captured terrain, at its place in the sequence.
     */
    public record TerrainLayer(
            int layer,
            float[] mvp,
            double viewX, double viewY, double viewZ,
            int fogMode,
            float fogStart,
            float fogEnd,
            float fogDensity,
            float[] fogColor,
            it.unimi.dsi.fastutil.longs.LongArrayList visibleChunks
    ) implements Operation {
        public TerrainLayer(int layer, float[] mvp) {
            this(layer, mvp, 0.0, 0.0, 0.0, 0, 0.0f, 1.0f, 1.0f, null, null);
        }

        public TerrainLayer(int layer, float[] mvp, double viewX, double viewY, double viewZ) {
            this(layer, mvp, viewX, viewY, viewZ, 0, 0.0f, 1.0f, 1.0f, null, null);
        }

        public TerrainLayer {
            if (mvp.length != 16) {
                throw new IllegalArgumentException("an mvp matrix has 16 values, got " + mvp.length);
            }
        }
    }

    /**
     * A change of what the frame is drawing into, at its place in the sequence.
     *
     * <p><b>Why this is an operation and not a new frame.</b> Vanilla switches render targets while it builds a frame -
     * a shadow or post-processing chain writes into one image, reads it, writes the next. The backend records such a
     * frame later, in one command buffer, so the switch has to travel with the draws as another entry in the sequence:
     * it means "end the rendering instance, move both images to their new layouts, open another one over this target".</p>
     *
     * <p><b>{@code target} is the texture handle</b> - the same number the game samples the target by - and {@code 0}
     * means the window's image, which is where a frame starts and where it usually returns. A handle rather than an
     * object because this list is backend-neutral: a GL backend replays the same sequence with {@code glBindFramebuffer}.</p>
     */
    public record TargetBind(int target, int width, int height, boolean setViewport) implements Operation {}

    /**
     * An attachment clear requested mid-frame.
     */
    /**
     * An attachment clear.
     *
     * @param stencil the value {@code glClearStencil} set, which the stencil aspect is cleared to
     */
    public record Clear(int mask, float[] color, double depth, int stencil, boolean scissorEnabled, int[] scissorBox)
            implements Operation {
        public Clear {
            if (color == null || color.length != 4) {
                throw new IllegalArgumentException("clear color must have 4 components");
            }
            if (scissorBox == null || scissorBox.length != 4) {
                throw new IllegalArgumentException("scissor box must have 4 values");
            }
        }
    }

    private static final Object LOCK = new Object();
    private static final List<Operation> ACTIVE_DRAWS = new ArrayList<>(1024);

    /** The target the recorded operations currently draw into; {@code 0} is the window's image. */
    private static int recordingTarget;
    private static final List<Operation> DRAINED_DRAWS = new ArrayList<>(1024);
    private static final List<Draw> DRAW_POOL = new ArrayList<>(1024);
    private static int poolCursor = 0;
    private static volatile boolean collecting;

    private FrameDraws() {}

    private static Draw obtainDraw() {
        if (poolCursor >= DRAW_POOL.size()) {
            Draw d = new Draw();
            DRAW_POOL.add(d);
            poolCursor++;
            return d;
        }
        return DRAW_POOL.get(poolCursor++);
    }

    /** Starts collecting. Called by a backend that will replay the list later in the frame. */
    public static void beginCollecting() {
        synchronized (LOCK) {
            ACTIVE_DRAWS.clear();
            poolCursor = 0;
            recordingTarget = 0;
            collecting = true;
        }
    }

    /** Stops collecting and returns what was recorded, in order. */
    public static List<Operation> drain() {
        synchronized (LOCK) {
            collecting = false;
            DRAINED_DRAWS.clear();
            DRAINED_DRAWS.addAll(ACTIVE_DRAWS);
            ACTIVE_DRAWS.clear();
            return DRAINED_DRAWS;
        }
    }

    /** Whether a backend is collecting this frame. */
    public static boolean isCollecting() {
        return collecting;
    }

    /**
     * Adds one draw.
     *
     * <p><b>Folded into the previous draw when nothing differs.</b> The fold happens here rather than at replay time
     * because this list is what every consumer reads: one draw carrying more vertices is cheaper to hold, to walk and
     * to dump than two that differ in nothing but their bytes. The state comparison is {@link DrawState#sameAs}, so the
     * fold only ever happens between draws that would configure the pipeline identically.</p>
     *
     * @return {@code false} when nothing is collecting, which means the caller has no frame to record into
     */
    public static boolean record(VertexLayout layout, int mode, int vertexCount, ByteBuffer data, DrawState state) {
        if (!collecting) {
            return false;
        }
        int bytes = vertexCount * layout.stride();
        if (data.limit() < bytes) {
            throw new IllegalArgumentException("a draw of " + vertexCount + " vertices at stride " + layout.stride()
                    + " needs " + bytes + " bytes, the buffer has " + data.limit());
        }
        synchronized (LOCK) {
            Draw previous = lastRecordedDraw();
            if (vertexCount > 0 && previous != null && previous.mergeableWith(mode, layout, state)) {
                previous.append(vertexCount, data, layout.stride());
                return true;
            }
            // A draw that did not fold is either a real state change or only a different texture. Counting the second
            // is what turns "batching would help" into a number: it is the set of draws one indirect submission could
            // replace if the texture were selected per draw rather than per bind.
            Draw draw = obtainDraw();
            draw.set(layout, mode, vertexCount, data, bytes, state);
            ACTIVE_DRAWS.add(draw);
        }
        return true;
    }

    /**
     * The draw at the end of the list, or {@code null} when the last thing recorded was not a draw.
     *
     * <p>A copy, a clear or a target switch between two draws is a reason not to fold them: the operation has to stay
     * between them in the sequence.</p>
     */
    private static @Nullable Draw lastRecordedDraw() {
        int size = ACTIVE_DRAWS.size();
        if (size == 0) {
            return null;
        }
        Operation last = ACTIVE_DRAWS.get(size - 1);
        return last instanceof Draw draw ? draw : null;
    }

    /**
     * Adds one draw with an existing vertex byte array directly without defensive copying.
     * Used by display lists whose vertex bytes are already immutable and captured.
     *
     * @return {@code false} when nothing is collecting
     */
    public static boolean record(VertexLayout layout, int mode, int vertexCount, byte[] vertices, DrawState state) {
        if (!collecting) {
            return false;
        }
        synchronized (LOCK) {
            Draw draw = obtainDraw();
            draw.set(layout, mode, vertexCount, vertices, state);
            ACTIVE_DRAWS.add(draw);
        }
        return true;
    }

    /**
     * Adds a framebuffer-to-texture copy at this point in the sequence.
     *
     * @return {@code false} when nothing is collecting, which means there is no frame to copy inside
     */
    /** Records one {@code glReadPixels}; {@code false} when no frame is collecting, so there is nothing to read. */
    public static boolean recordReadPixels(int x, int y, int width, int height, int format, int type,
                                           java.nio.ByteBuffer destination) {
        if (!collecting) {
            return false;
        }
        synchronized (LOCK) {
            ACTIVE_DRAWS.add(new ReadPixels(x, y, width, height, format, type, destination));
        }
        return true;
    }

    public static boolean recordFramebufferCopy(int texture, int level, int xOffset, int yOffset, int x, int y,
                                                int width, int height) {
        if (!collecting) {
            return false;
        }
        synchronized (LOCK) {
            ACTIVE_DRAWS.add(new FramebufferCopy(texture, level, xOffset, yOffset, x, y, width, height));
        }
        return true;
    }

    /**
     * Adds one terrain layer draw at this point in the sequence.
     *
     * @return {@code false} when nothing is collecting, which means there is no frame to draw inside
     */
    public static boolean recordTerrainLayer(int layer, float[] mvp) {
        return recordTerrainLayer(layer, mvp, 0.0, 0.0, 0.0);
    }

    /**
     * Adds one terrain layer draw at this point in the sequence with camera viewpoint coordinates.
     *
     * @return {@code false} when nothing is collecting, which means there is no frame to draw inside
     */
    public static boolean recordTerrainLayer(int layer, float[] mvp, double viewX, double viewY, double viewZ) {
        return recordTerrainLayer(layer, mvp, viewX, viewY, viewZ, 0, 0.0f, 1.0f, 1.0f, null, null);
    }

    public static boolean recordTerrainLayer(int layer, float[] mvp, double viewX, double viewY, double viewZ,
                                             int fogMode, float fogStart, float fogEnd, float fogDensity, float[] fogColor,
                                             it.unimi.dsi.fastutil.longs.LongArrayList visibleChunks) {
        if (!collecting) {
            return false;
        }
        synchronized (LOCK) {
            ACTIVE_DRAWS.add(new TerrainLayer(layer, mvp, viewX, viewY, viewZ,
                    fogMode, fogStart, fogEnd, fogDensity, fogColor, visibleChunks));
        }
        return true;
    }

    /**
     * Adds an attachment clear at this point in the sequence.
     *
     * @return {@code false} when nothing is collecting, which means there is no frame to clear inside
     */
    public static boolean recordClear(int mask, float[] color, double depth, int stencil, boolean scissorEnabled,
                                      int[] scissorBox) {
        if (!collecting) {
            return false;
        }
        synchronized (LOCK) {
            ACTIVE_DRAWS.add(new Clear(mask, color, depth, stencil, scissorEnabled, scissorBox));
        }
        return true;
    }

    /**
     * Adds a render-target switch at this point in the sequence.
     *
     * @param target the target's texture handle, or {@code 0} for the window's image
     * @return {@code false} when nothing is collecting, which means there is no frame to switch inside
     */
    public static boolean recordTargetBind(int target, int width, int height, boolean setViewport) {
        if (!collecting) {
            return false;
        }
        synchronized (LOCK) {
            ACTIVE_DRAWS.add(new TargetBind(target, width, height, setViewport));
            recordingTarget = target;
        }
        return true;
    }

    /**
     * The target the operations recorded so far are drawing into, or {@code 0} for the window's image.
     *
     * <p>Needed because the window's image is the frame's default and binding it therefore records nothing on its own:
     * a frame that switches into a target and back out has to know whether the way back is already implied or whether
     * it has to be recorded. Without this, everything drawn after a switch back was still recorded into the target it
     * had left - the composite of the entity outline went into the very framebuffer it was sampling.</p>
     */
    public static int recordingTarget() {
        synchronized (LOCK) {
            return recordingTarget;
        }
    }

    /** How many operations are waiting. Diagnostics. */
    public static int pending() {
        synchronized (LOCK) {
            return ACTIVE_DRAWS.size();
        }
    }
}
