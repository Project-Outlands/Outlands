package net.minecraftforge.client.render.vulkan;

import net.minecraftforge.client.render.vertex.DrawState;
import net.minecraftforge.client.render.vertex.FrameDraws;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraftforge.client.render.vertex.VertexLayout;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;

/**
 * Diagnostic: writes what a frame's draws are, where they land, and which program drew them.
 *
 * <p>It exists because "the magazine is against the right edge of the window" cannot be chased by reading code: the
 * picture says where a draw landed, and this says what the draw was. It found three faults in a row that way - a
 * missing push constant, a black lightmap coordinate, a doubled flip.</p>
 *
 * <p><b>Off unless asked for</b> ({@code -Doutlands.debug.frameDump=true}), because a picture that is wrong is rare and
 * a dump every hundred frames is not free. Bounded when it is on: a handful of frames, then it stops.</p>
 */
final class FrameDump {

    private static final boolean ENABLED = Boolean.getBoolean("outlands.debug.frameDump");
    private static final int EVERY_FRAMES = 120;
    private static final int MAX_DUMPS = 12;

    private static int frames;
    private static int dumps;

    private FrameDump() {}

    /**
     * A file the user creates to dump the very next frame, whatever it contains and whenever it happens.
     *
     * <p>The automatic selection exists to find artifacts nobody knows how to reproduce; a question that already has a
     * moment ("shoot the mob, then look") needs the opposite, and waiting for frame 120 to come round again is how a
     * dump ends up showing the wrong second.</p>
     */
    private static final Path FORCE_FILE = Path.of("logs", "dump-now");
    private static final int MAX_FORCED = 8;
    private static int forced;

    static void maybeDump(List<FrameDraws.Operation> ops) {
        if (!ENABLED) {
            return;
        }
        frames++;
        boolean requested = false;
        if (Files.exists(FORCE_FILE) && forced < MAX_FORCED) {
            requested = true;
            forced++;
            try {
                Files.deleteIfExists(FORCE_FILE);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } else if (dumps >= MAX_DUMPS || frames % EVERY_FRAMES != 0) {
            return;
        }
        if (!requested && !interesting(ops)) {
            return;
        }
        if (!requested) {
            dumps++;
        }
        StringBuilder out = new StringBuilder(1 << 16);
        out.append("# frame ").append(frames).append(", ").append(ops.size()).append(" operations")
                .append(requested ? " (requested)" : "").append('\n');
        int index = 0;
        // Which image a draw lands in is the difference between an outline that never reached its target and one
        // that got lost inside the chain, and it is not visible from the draw itself.
        int target = 0;
        for (FrameDraws.Operation op : ops) {
            if (op instanceof FrameDraws.TargetBind bind) {
                target = bind.target();
            }
            index = append(out, index, op, target);
        }
        try {
            Files.writeString(Path.of("logs/outlands-frame-dump.txt"), out,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Whether a frame is worth dumping on its own.
     *
     * <p>Either a frame with a mod program in it (the model case), or one with a wide draw above the horizon (the
     * floating black box case): both are artifacts that cannot be chased without knowing which draw made them.</p>
     */
    private static boolean interesting(List<FrameDraws.Operation> ops) {
        for (FrameDraws.Operation op : ops) {
            if (!(op instanceof FrameDraws.Draw draw)) {
                continue;
            }
            if (draw.state().program() != null) {
                return true;
            }
            float[] rect = rect(draw);
            if (rect == null) {
                continue;
            }
            // A wide flat thing above the horizon: the floating black box, which is a model part.
            if (rect[1] - rect[0] > 0.25F && rect[2] > 0.05F && rect[3] - rect[2] < 0.6F) {
                return true;
            }
            // A small thing near the top left of the screen: the icon that sits at the same place in every screenshot,
            // which means it is drawn in screen space and something is placing it there.
            if (rect[0] > -0.45F && rect[1] < -0.15F && rect[2] > 0.7F && rect[3] < 1.0F) {
                return true;
            }
        }
        return false;
    }

    /**
     * One line per operation: a draw in full, and everything else by what it does to the frame.
     *
     * <p><b>The non-draw lines are the point.</b> A frame that draws nothing into a target and one that draws into it
     * and then composites it can look identical from the draw list alone, and "is the entity-outline chain running at
     * all" is exactly that question: it is three target binds and two full-screen program quads, or it is nothing.</p>
     */
    private static int append(StringBuilder out, int index, FrameDraws.Operation op, int target) {
        if (op instanceof FrameDraws.Draw draw) {
            DrawState state = draw.state();
            VertexLayout layout = draw.layout();
            float[] mvp = state.mvp();
            out.append(index++).append(' ')
                    .append(state.program() == null ? "stream" : "program " + state.program().id())
                    .append(" mode=").append(draw.mode())
                    .append(" n=").append(draw.vertexCount())
                    .append(" layout=").append(layout == null ? "-" : layout.format().getElements().size() + "elem")
                    .append(" ndc=").append(bounds(draw))
                    .append(String.format(Locale.ROOT, " col=(%.2f,%.2f,%.2f,%.2f)", state.color()[0],
                            state.color()[1], state.color()[2], state.color()[3]))
                    .append(" light=").append(state.lightmap() == null ? "-"
                            : String.format(Locale.ROOT, "(%.3f,%.3f)", state.lightmap()[0], state.lightmap()[1]))
                    .append(" tex=").append(state.texture())
                    .append(" tgt=").append(target)
                    .append(" overlay=").append(state.overlayMode())
                    .append(" proj=").append(projection(state.programBlock()))
                    .append(" depth=").append(state.depthTest() ? "on" : "off")
                    .append('/').append(state.depthWrite() ? "w" : "-")
                    .append('/').append(state.depthFunc())
                    .append(" blend=").append(state.blend() ? "on" : "off")
                    .append(" cull=").append(state.cull() ? "on" : "off")
                    .append(" v0=").append(firstVertex(draw))
                    .append(String.format(Locale.ROOT, " mvpT=(%.3f,%.3f,%.3f)", mvp[12], mvp[13], mvp[14]))
                    .append('\n');
            if (draw.vertexCount() <= ALL_VERTICES_UP_TO) {
                appendAllVertices(out, draw, index - 1);
            }
            return index;
        }
        out.append(index++).append(' ');
        switch (op) {
            case FrameDraws.TargetBind bind -> out.append("bind target=").append(bind.target())
                    .append(' ').append(bind.width()).append('x').append(bind.height())
                    .append(bind.setViewport() ? " +viewport" : "");
            case FrameDraws.Clear clear -> out.append("clear mask=0x").append(Integer.toHexString(clear.mask()))
                    .append(String.format(Locale.ROOT, " color=(%.2f,%.2f,%.2f,%.2f) depth=%.3f stencil=%d",
                            clear.color()[0], clear.color()[1], clear.color()[2], clear.color()[3],
                            clear.depth(), clear.stencil()));
            case FrameDraws.FramebufferCopy copy -> out.append("copy tex=").append(copy.texture())
                    .append(" src=(").append(copy.x()).append(',').append(copy.y()).append(')')
                    .append(' ').append(copy.width()).append('x').append(copy.height())
                    .append(" dst=(").append(copy.xOffset()).append(',').append(copy.yOffset()).append(')');
            case FrameDraws.ReadPixels read -> out.append("readpixels ").append(read.width()).append('x')
                    .append(read.height()).append(" at (").append(read.x()).append(',').append(read.y()).append(')');
            case FrameDraws.TerrainLayer layer -> out.append("terrain layer=").append(layer.layer())
                    .append(" chunks=").append(layer.visibleChunks() == null ? 0 : layer.visibleChunks().size());
            default -> out.append(op.getClass().getSimpleName());
        }
        out.append('\n');
        return index;
    }

    /**
     * The four numbers of a program's uniform block that decide whether a post-processing quad covers the screen.
     *
     * <p>A pass draws one full-screen quad and computes its clip position as {@code ProjMat * Position}, so the matrix
     * being zero is a pass that rasterises nothing - with no validation error, no warning and no difference in the
     * draw list. The block's first member is that matrix in every post-processing stage this project ships, so its
     * x/y scale and two translations are enough: {@code (2/width, -1, -2/height, 1)} for a 854x480 window.</p>
     */
    private static String projection(byte[] block) {
        if (block == null || block.length < 64) {
            return "none(" + (block == null ? -1 : block.length) + "B)";
        }
        java.nio.FloatBuffer floats =
                java.nio.ByteBuffer.wrap(block).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
        return String.format(Locale.ROOT, "(%.6f,%.6f,%.6f,%.6f)",
                floats.get(0), floats.get(3), floats.get(5), floats.get(7));
    }

    private static String bounds(FrameDraws.Draw draw) {
        float[] rect = rect(draw);
        if (rect == null) {
            return "-";
        }
        return String.format(Locale.ROOT, "x[%.2f,%.2f] y[%.2f,%.2f]", rect[0], rect[1], rect[2], rect[3]);
    }

    /**
     * The first vertex as it was handed over: position, uv, colour and lightmap, whichever the layout carries.
     *
     * <p>The point is to tell a black draw apart from a black texture: a vertex with a white colour and a sane uv that
     * still renders black means the sampling is wrong, and a vertex with a zero colour or a zero uv means the geometry
     * was recorded wrong.</p>
     */
    /** Draws up to this many vertices get every one of them listed, not just the first. */
    private static final int ALL_VERTICES_UP_TO = 32;

    /**
     * Writes every vertex of a small draw, one per line.
     *
     * <p>A line primitive is where "the shape is wrong" is a question about vertex order rather than about any state:
     * an eighteen-vertex strip that draws a diagonal across a block has to be read vertex by vertex to tell a wrong
     * order from a vertex that is simply in the wrong place.</p>
     */
    private static void appendAllVertices(StringBuilder out, FrameDraws.Draw draw, int index) {
        VertexLayout layout = draw.layout();
        byte[] data = draw.vertices();
        if (layout == null || data == null) {
            return;
        }
        ByteBuffer view = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder());
        for (int v = 0; v < draw.vertexCount(); v++) {
            int base = v * layout.stride();
            out.append("    ").append(index).append('.').append(v);
            for (VertexLayout.Attribute attribute : layout.attributes()) {
                if (!attribute.isAttribute() || attribute.offset() >= layout.stride()) {
                    continue;
                }
                int at = base + attribute.offset();
                switch (attribute.usage()) {
                    case POSITION -> out.append(String.format(Locale.ROOT, " p(%.3f,%.3f,%.3f)",
                            view.getFloat(at), view.getFloat(at + 4), view.getFloat(at + 8)));
                    case COLOR -> out.append(String.format(Locale.ROOT, " c(%d,%d,%d,%d)",
                            view.get(at) & 0xFF, view.get(at + 1) & 0xFF, view.get(at + 2) & 0xFF,
                            view.get(at + 3) & 0xFF));
                    case UV -> out.append(String.format(Locale.ROOT, " uv(%.3f,%.3f)",
                            view.getFloat(at), view.getFloat(at + 4)));
                    default -> { }
                }
            }
            out.append('\n');
        }
    }

    private static String firstVertex(FrameDraws.Draw draw) {
        VertexLayout layout = draw.layout();
        byte[] data = draw.vertices();
        if (layout == null || data == null || draw.vertexCount() == 0) {
            return "-";
        }
        ByteBuffer view = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder());
        StringBuilder out = new StringBuilder();
        for (VertexLayout.Attribute attribute : layout.attributes()) {
            if (!attribute.isAttribute() || attribute.offset() >= layout.stride()) {
                continue;
            }
            int at = attribute.offset();
            switch (attribute.usage()) {
                case POSITION -> out.append(String.format(Locale.ROOT, " p(%.2f,%.2f,%.2f)",
                        view.getFloat(at), view.getFloat(at + 4), view.getFloat(at + 8)));
                case UV -> out.append(String.format(Locale.ROOT, " uv(%.3f,%.3f)",
                        attribute.type() == net.minecraft.client.renderer.vertex.VertexFormatElement.EnumType.USHORT
                                ? (view.getShort(at) & 0xFFFF) / 65535.0F : view.getFloat(at),
                        attribute.type() == net.minecraft.client.renderer.vertex.VertexFormatElement.EnumType.USHORT
                                ? (view.getShort(at + 2) & 0xFFFF) / 65535.0F : view.getFloat(at + 4)));
                case COLOR -> out.append(String.format(Locale.ROOT, " c(%d,%d,%d,%d)",
                        view.get(at) & 0xFF, view.get(at + 1) & 0xFF, view.get(at + 2) & 0xFF, view.get(at + 3) & 0xFF));
                default -> {
                    if (attribute.count() == 2 && attribute.type()
                            == net.minecraft.client.renderer.vertex.VertexFormatElement.EnumType.USHORT) {
                        out.append(String.format(Locale.ROOT, " l(%d,%d)",
                                view.getShort(at) & 0xFFFF, view.getShort(at + 2) & 0xFFFF));
                    }
                }
            }
        }
        return out.isEmpty() ? "-" : out.toString().trim();
    }

    /** The NDC rectangle the draw's positions cover: {minX, maxX, minY, maxY}, or null when it has no positions. */
    private static float[] rect(FrameDraws.Draw draw) {
        VertexLayout layout = draw.layout();
        byte[] data = draw.vertices();
        if (layout == null || data == null || draw.vertexCount() == 0) {
            return null;
        }
        int stride = layout.stride();
        int positionOffset = -1;
        for (VertexLayout.Attribute attribute : layout.attributes()) {
            if (attribute.usage() == VertexFormatElement.EnumUsage.POSITION) {
                positionOffset = attribute.offset();
                break;
            }
        }
        if (positionOffset < 0) {
            return null;
        }
        ByteBuffer view = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder());
        float[] mvp = draw.state().mvp();
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int vertex = 0; vertex < draw.vertexCount(); vertex++) {
            int at = vertex * stride + positionOffset;
            if (at + 12 > data.length) {
                break;
            }
            float x = view.getFloat(at), y = view.getFloat(at + 4), z = view.getFloat(at + 8);
            float w = mvp[3] * x + mvp[7] * y + mvp[11] * z + mvp[15];
            if (w == 0.0F) {
                continue;
            }
            float cx = (mvp[0] * x + mvp[4] * y + mvp[8] * z + mvp[12]) / w;
            float cy = (mvp[1] * x + mvp[5] * y + mvp[9] * z + mvp[13]) / w;
            minX = Math.min(minX, cx);
            maxX = Math.max(maxX, cx);
            minY = Math.min(minY, cy);
            maxY = Math.max(maxY, cy);
        }
        if (minX > maxX) {
            return null;
        }
        return new float[] {minX, maxX, minY, maxY};
    }
}
