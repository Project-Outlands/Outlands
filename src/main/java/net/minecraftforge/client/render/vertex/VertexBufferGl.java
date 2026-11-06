package net.minecraftforge.client.render.vertex;

import java.nio.ByteBuffer;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.vertex.VertexFormat;

/**
 * The buffer as it exists today: a GL buffer object plus a client-memory draw.
 *
 * <p>This is the original {@code VertexBuffer} body moved verbatim. Nothing here is new logic - the point of the
 * move is that the GL path stays byte-for-byte what it was, so a backend switch cannot change vanilla's behaviour
 * on the GL side.</p>
 *
 * <p>{@code GL_STATIC_DRAW} is the usage hint vanilla used, and it is not obviously right for a buffer that is
 * re-uploaded per draw ({@code Tessellator} does exactly that), but changing it here would be a behaviour change
 * smuggled into a refactor.</p>
 */
final class VertexBufferGl implements VertexBufferBackend {

    private static final int GL_STATIC_DRAW = 35044;

    private final VertexFormat format;
    private int glBufferId;
    private int vertexCount;

    VertexBufferGl(VertexFormat format) {
        this.format = format;
    }

    @Override
    public void create() {
        this.glBufferId = OpenGlHelper.glGenBuffers();
    }

    @Override
    public void bind() {
        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, this.glBufferId);
    }

    @Override
    public void upload(ByteBuffer data) {
        bind();
        OpenGlHelper.glBufferData(OpenGlHelper.GL_ARRAY_BUFFER, data, GL_STATIC_DRAW);
        unbind();
        this.vertexCount = data.limit() / format.getSize();
    }

    @Override
    public void draw(int mode, int first, int count) {
        GlStateManager.glDrawArrays(mode, first, count);
    }

    @Override
    public void unbind() {
        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
    }

    @Override
    public void delete() {
        if (glBufferId >= 0) {
            OpenGlHelper.glDeleteBuffers(glBufferId);
            glBufferId = -1;
        }
        vertexCount = 0;
    }

    @Override
    public VertexFormat format() {
        return format;
    }

    @Override
    public int vertexCount() {
        return vertexCount;
    }
}
