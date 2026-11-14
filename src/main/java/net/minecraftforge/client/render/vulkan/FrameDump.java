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
 * Temporary diagnostic: writes what a frame's draws are, where they land, and which program drew them.
 *
 * <p>It exists because "the magazine is against the right edge of the window" cannot be chased by reading code: the
 * picture says where a draw landed, and this says what the draw was. Bounded on purpose - a handful of frames, then
 * it stops - so a normal session leaves a small file and no cost.</p>
 */
final class FrameDump {

    private static final int EVERY_FRAMES = 300;
    private static final int MAX_DUMPS = 8;

    private static int frames;
    private static int dumps;

    private FrameDump() {}

    static void maybeDump(List<FrameDraws.Operation> ops) {
        frames++;
        if (dumps >= MAX_DUMPS || frames % EVERY_FRAMES != 0) {
            return;
        }
        boolean hasProgram = false;
        for (FrameDraws.Operation op : ops) {
            if (op instanceof FrameDraws.Draw draw && draw.state().program() != null) {
                hasProgram = true;
                break;
            }
        }
        if (!hasProgram) {
            return;
        }
        dumps++;
        StringBuilder out = new StringBuilder(1 << 16);
        out.append("# frame ").append(frames).append(", ").append(ops.size()).append(" operations\n");
        int index = 0;
        for (FrameDraws.Operation op : ops) {
            if (!(op instanceof FrameDraws.Draw draw)) {
                continue;
            }
            DrawState state = draw.state();
            VertexLayout layout = draw.layout();
            float[] mvp = state.mvp();
            out.append(index++).append(' ')
                    .append(state.program() == null ? "stream" : "program " + state.program().id())
                    .append(" mode=").append(draw.mode())
                    .append(" n=").append(draw.vertexCount())
                    .append(" layout=").append(layout == null ? "-" : layout.format().getElements().size() + "elem")
                    .append(" ndc=").append(bounds(draw))
                    .append(String.format(Locale.ROOT, " mvpT=(%.3f,%.3f,%.3f)", mvp[12], mvp[13], mvp[14]))
                    .append('\n');
        }
        try {
            Files.writeString(Path.of("logs/outlands-frame-dump.txt"), out,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The NDC rectangle the draw's positions cover, as min/max of x and y after the mvp. */
    private static String bounds(FrameDraws.Draw draw) {
        VertexLayout layout = draw.layout();
        byte[] data = draw.vertices();
        if (layout == null || data == null || draw.vertexCount() == 0) {
            return "-";
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
            return "-";
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
            return "-";
        }
        return String.format(Locale.ROOT, "x[%.2f,%.2f] y[%.2f,%.2f]", minX, maxX, minY, maxY);
    }
}
