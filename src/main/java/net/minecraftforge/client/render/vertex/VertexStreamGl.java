package net.minecraftforge.client.render.vertex;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraftforge.client.ForgeHooksClient;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * The client-memory draw path as it exists today.
 *
 * <p>This is the original {@code WorldVertexBufferUploader} body, moved. The per-element walk survives because that
 * is what GL needs: the driver reads the pointers when the draw is issued, so they have to be set before it and
 * released after. A backend that copies the bytes has no use for it, which is why it lives in the GL implementation
 * rather than in the interface.</p>
 *
 * <p><b>Why it still goes through {@link ForgeHooksClient#preDraw}.</b> That hook is where Forge lets mods supply
 * vertex attributes vanilla does not have ({@code GENERIC}), and it is the existing extension point. Replacing the
 * walk with direct {@code GlStateManager} calls would quietly drop that - a compatibility change hidden inside a
 * refactor.</p>
 */
final class VertexStreamGl implements VertexStreamBackend {

    @Override
    public void draw(ByteBuffer data, VertexLayout layout, int mode, int vertexCount) {
        if (vertexCount <= 0) {
            return;
        }
        int stride = layout.stride();
        List<VertexLayout.Attribute> attributes = layout.attributes();

        for (int element = 0; element < attributes.size(); element++) {
            VertexFormatElement.EnumUsage usage = attributes.get(element).usage();
            data.position(layout.attributes().get(element).offset());
            ForgeHooksClient.preDraw(usage, formatOf(layout), element, stride, data);
        }

        GlStateManager.glDrawArrays(mode, 0, vertexCount);

        for (int element = 0; element < attributes.size(); element++) {
            VertexFormatElement.EnumUsage usage = attributes.get(element).usage();
            ForgeHooksClient.postDraw(usage, formatOf(layout), element, stride, data);
        }
    }

    /**
     * The format the hooks are written against.
     *
     * <p>{@code preDraw}/{@code postDraw} take the format so they can look up the element; the layout is a
     * translation of the same thing, so this reads it back out. Kept as a method so the shape of the dependency is
     * visible rather than spread through the loop.</p>
     */
    private static net.minecraft.client.renderer.vertex.VertexFormat formatOf(VertexLayout layout) {
        return layout.format();
    }
}
