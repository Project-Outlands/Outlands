package net.minecraftforge.client.render;

import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraftforge.client.render.shim.ShimGl;
import net.minecraftforge.client.render.state.backend.ClientArrays;
import net.minecraftforge.client.render.state.backend.FrameStates;
import net.minecraftforge.client.render.vertex.FrameDraws;
import net.minecraftforge.client.render.vertex.VertexConverter;
import net.minecraftforge.client.render.vertex.VertexLayout;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Client arrays, driven the way Immersive Intelligence's {@code TmtTessellator} drives them.
 *
 * <p>The shape is copied from the mod rather than invented: one 40-byte-stride buffer, four arrays pointed into it
 * with a client-memory buffer whose position is the offset, the lightmap declared as the texcoord array of unit 1,
 * and {@code glDrawArrays} per 64k vertices. Getting this wrong is invisible - the model simply does not appear -
 * which is why every component is read back out of the recorded draw instead of asserting "no exception".</p>
 */
class ClientArraysTest {

    private static final int STRIDE = 40;
    private static final int GL_QUADS = 7;
    private static final int GL_VERTEX_ARRAY = 32884;
    private static final int GL_COLOR_ARRAY = 32886;
    private static final int GL_TEXTURE_COORD_ARRAY = 32888;
    private static final int GL_TEXTURE0 = 33984;
    private static final int GL_TEXTURE1 = 33985;

    @BeforeEach
    void selectANonGlBackend() {
        NonGlTestBackend.select();
        FrameStates.clientArrays().reset();
    }

    @AfterEach
    void restoreTheRealGlPath() {
        if (FrameDraws.isCollecting()) {
            FrameDraws.drain();
        }
        FrameStates.clientArrays().reset();
        NonGlTestBackend.restore();
    }

    /**
     * Two vertices in the mod's layout: position at 0, UV at 12, colour at 28, normal at 32, lightmap at 36.
     */
    private static ByteBuffer vertices() {
        ByteBuffer buffer = GLAllocation.createDirectByteBuffer(2 * STRIDE);
        putVertex(buffer, 0, 1.0F, 2.0F, 3.0F, 0.25F, 0.5F, (byte) 255, (byte) 128, (byte) 0, (byte) 255,
                (short) 0x1234, (short) 0x5678);
        putVertex(buffer, 1, 4.0F, 5.0F, 6.0F, 0.75F, 1.0F, (byte) 0, (byte) 255, (byte) 0, (byte) 255,
                (short) 0x9ABC, (short) 0xDEF0);
        return buffer;
    }

    private static void putVertex(ByteBuffer buffer, int index, float x, float y, float z, float u, float v,
                                  byte r, byte g, byte b, byte a, short lightU, short lightV) {
        int at = index * STRIDE;
        buffer.putFloat(at, x).putFloat(at + 4, y).putFloat(at + 8, z);
        // size 4, the way the mod declares it: u, v and two components it never reads.
        buffer.putFloat(at + 12, u).putFloat(at + 16, v).putFloat(at + 20, 0.0F).putFloat(at + 24, 1.0F);
        buffer.put(at + 28, r).put(at + 29, g).put(at + 30, b).put(at + 31, a);
        buffer.put(at + 32, (byte) 0).put(at + 33, (byte) 0).put(at + 34, (byte) 127);
        buffer.putShort(at + 36, lightU).putShort(at + 38, lightV);
    }

    private static void declareTheModsArrays(ByteBuffer buffer) {
        FloatBuffer floats = buffer.asFloatBuffer();
        ShortBuffer shorts = buffer.asShortBuffer();

        floats.position(0);
        ShimGl.glVertexPointer(3, STRIDE, floats);

        // Unit 0: the texture UV. Unit 1: the lightmap, which is the second texcoord array GL keeps.
        OpenGlHelper.setClientActiveTexture(GL_TEXTURE1);
        shorts.position(18);
        ShimGl.glTexCoordPointer(2, STRIDE, shorts);
        ShimGl.glEnableClientState(GL_TEXTURE_COORD_ARRAY);
        OpenGlHelper.setClientActiveTexture(GL_TEXTURE0);
        floats.position(3);
        ShimGl.glTexCoordPointer(4, STRIDE, floats);
        ShimGl.glEnableClientState(GL_TEXTURE_COORD_ARRAY);

        buffer.position(28);
        ShimGl.glColorPointer(4, true, STRIDE, buffer);
        ShimGl.glEnableClientState(GL_COLOR_ARRAY);

        ShimGl.glEnableClientState(GL_VERTEX_ARRAY);
    }

    private static FrameDraws.Draw drawAndCapture() {
        FrameDraws.beginCollecting();
        ShimGl.glDrawArrays(GL_QUADS, 0, 2);
        var recorded = FrameDraws.drain();
        assertEquals(1, recorded.size(), "one glDrawArrays is one draw");
        return (FrameDraws.Draw) recorded.get(0);
    }

    @Test
    void theModsArraysBecomeOneDrawInTheCanonicalLayout() {
        declareTheModsArrays(vertices());

        FrameDraws.Draw draw = drawAndCapture();

        assertEquals(VertexLayout.of(DefaultVertexFormats.BLOCK), draw.layout(),
                "client arrays are normalised to the layout the pipeline knows");
        assertEquals(2, draw.vertexCount());
        assertEquals(GL_QUADS, draw.mode());

        ByteBuffer out = ByteBuffer.wrap(draw.vertices()).order(ByteOrder.nativeOrder());
        int stride = VertexConverter.STRIDE;
        assertEquals(1.0F, out.getFloat(0), 1e-6F, "position comes from the vertex array");
        assertEquals(2.0F, out.getFloat(4), 1e-6F);
        assertEquals(3.0F, out.getFloat(8), 1e-6F);
        assertEquals((byte) 255, out.get(12), "colour comes from the colour array");
        assertEquals((byte) 128, out.get(13));
        assertEquals((byte) 0, out.get(14));
        assertEquals((byte) 255, out.get(15));
        assertEquals(0.25F, out.getFloat(16), 1e-6F, "UV comes from unit 0's texcoord array");
        assertEquals(0.5F, out.getFloat(20), 1e-6F);
        assertEquals((short) 0x1234, out.getShort(24), "and the lightmap from unit 1's");
        assertEquals((short) 0x5678, out.getShort(26));

        int second = stride;
        assertEquals(4.0F, out.getFloat(second), 1e-6F, "the stride walks to the second vertex");
        assertEquals(0.75F, out.getFloat(second + 16), 1e-6F);
        assertEquals((short) 0x9ABC, out.getShort(second + 24));
    }

    @Test
    void theBufferObjectFormReadsThroughTheOffset() {
        // The same data, uploaded instead of pointed at: glVertexPointer(3, GL_FLOAT, 40, 0L) and friends.
        ByteBuffer source = vertices();
        int name = net.minecraftforge.client.render.state.backend.BufferObjects.instance().generate(1);
        ShimGl.glBindBufferARB(34962, name);
        source.position(0);
        ShimGl.glBufferDataARB(34962, source, 35044);

        ShimGl.glVertexPointer(3, 5126, STRIDE, 0L);
        OpenGlHelper.setClientActiveTexture(GL_TEXTURE1);
        ShimGl.glTexCoordPointer(2, 5122, STRIDE, 36L);
        ShimGl.glEnableClientState(GL_TEXTURE_COORD_ARRAY);
        OpenGlHelper.setClientActiveTexture(GL_TEXTURE0);
        ShimGl.glTexCoordPointer(4, 5126, STRIDE, 12L);
        ShimGl.glEnableClientState(GL_TEXTURE_COORD_ARRAY);
        ShimGl.glColorPointer(4, 5121, STRIDE, 28L);
        ShimGl.glEnableClientState(GL_COLOR_ARRAY);
        ShimGl.glEnableClientState(GL_VERTEX_ARRAY);

        FrameDraws.Draw draw = drawAndCapture();
        ByteBuffer out = ByteBuffer.wrap(draw.vertices()).order(ByteOrder.nativeOrder());

        assertEquals(1.0F, out.getFloat(0), 1e-6F);
        assertEquals(0.25F, out.getFloat(16), 1e-6F);
        assertEquals((byte) 255, out.get(12));
        assertEquals((short) 0x1234, out.getShort(24));

        ShimGl.glBindBufferARB(34962, 0);
        net.minecraftforge.client.render.state.backend.BufferObjects.instance().delete(name, 1);
    }

    @Test
    void aDrawWithoutAColourArrayUsesTheCurrentColour() {
        // GL's rule for a disabled array: the value glColor set last. The assembler has to match it or every
        // uncoloured client-array draw turns black.
        ByteBuffer buffer = vertices();
        ShimGl.glVertexPointer(3, STRIDE, buffer.asFloatBuffer());
        ShimGl.glEnableClientState(GL_VERTEX_ARRAY);
        ShimGl.glColor4f(1.0F, 0.0F, 0.0F, 1.0F);

        FrameDraws.Draw draw = drawAndCapture();
        ByteBuffer out = ByteBuffer.wrap(draw.vertices()).order(ByteOrder.nativeOrder());

        assertEquals((byte) 255, out.get(12), "red, from the current colour");
        assertEquals((byte) 0, out.get(13));
        assertEquals((short) 0xFFFF, out.getShort(24), "and full brightness without a lightmap array");
    }

    @Test
    void theClientArrayStateIsTheOneThePatchRoutesTo() {
        // OpenGlHelper.setClientActiveTexture is patched to reach this object; if the shim kept a second copy of the
        // state, the two units would be one.
        ClientArrays arrays = ClientArrays.forNonGl();
        assertNotNull(arrays);
        assertTrue(arrays == FrameStates.clientArrays(), "one instance, not a per-caller one");
    }
}
