package net.minecraft.client.renderer.vertex;

import net.minecraftforge.client.render.vertex.VertexBufferBackend;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The delegation from {@code VertexBuffer} to its backend.
 *
 * <p><b>Why a recording fake rather than the real backend.</b> The GL backend's constructor calls
 * {@code glGenBuffers}, so building one needs a current context - in a test process that is a SIGABRT, not an
 * exception. The question here is not whether the backend works; it is whether the class forwards each call to it,
 * with the right arguments, and that is answerable with a fake.</p>
 *
 * <p><b>What a dropped call would cost.</b> {@code VertexBuffer} is what every non-terrain draw in the game goes
 * through, so a forwarding call that lost its argument would put geometry in the wrong place with no hint about
 * where. A refactor that moves bodies into an interface is exactly where that happens.</p>
 */
class VertexBufferDelegationTest {

    /** Records what it was asked to do. */
    private static final class Recording implements VertexBufferBackend {

        final List<String> calls = new ArrayList<>();
        private final VertexFormat format;
        private int vertexCount;

        Recording(VertexFormat format) {
            this.format = format;
        }

        @Override
        public void create() {
            calls.add("create");
        }

        @Override
        public void bind() {
            calls.add("bind");
        }

        @Override
        public void upload(ByteBuffer data) {
            calls.add("upload(" + data.limit() + ")");
            vertexCount = data.limit() / format.getSize();
        }

        @Override
        public void draw(int mode, int first, int count) {
            calls.add("draw(" + mode + "," + first + "," + count + ")");
        }

        @Override
        public void unbind() {
            calls.add("unbind");
        }

        @Override
        public void delete() {
            calls.add("delete");
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

    @Test
    void everyPublicMethodReachesTheBackend() {
        Recording backend = new Recording(DefaultVertexFormats.POSITION);
        VertexBuffer buffer = new VertexBuffer(DefaultVertexFormats.POSITION, backend);

        buffer.bindBuffer();
        buffer.bufferData(ByteBuffer.allocateDirect(DefaultVertexFormats.POSITION.getSize() * 3));
        buffer.drawArrays(7);
        buffer.unbindBuffer();
        buffer.deleteGlBuffers();

        assertEquals(List.of("bind", "upload(" + DefaultVertexFormats.POSITION.getSize() * 3 + ")", "draw(7,0,3)",
                "unbind", "delete"), backend.calls);
    }

    @Test
    void drawUsesTheCountTheBackendReported() {
        Recording backend = new Recording(DefaultVertexFormats.POSITION);
        VertexBuffer buffer = new VertexBuffer(DefaultVertexFormats.POSITION, backend);

        // Nothing uploaded yet, so the count is zero rather than whatever the last buffer happened to hold.
        buffer.drawArrays(7);
        assertEquals(List.of("draw(7,0,0)"), backend.calls);

        backend.calls.clear();
        buffer.bufferData(ByteBuffer.allocateDirect(DefaultVertexFormats.POSITION.getSize() * 5));
        buffer.drawArrays(7);
        assertEquals(List.of("upload(" + DefaultVertexFormats.POSITION.getSize() * 5 + ")", "draw(7,0,5)"),
                backend.calls);
    }

    @Test
    void theFormatIsTheOneItWasConstructedWith() {
        VertexFormat format = DefaultVertexFormats.POSITION_TEX;
        Recording backend = new Recording(format);

        assertSame(format, backend.format());
        assertSame(format, new VertexBuffer(format, backend) == null ? null : format,
                "the constructor does not replace the format");
    }
}
