package net.minecraftforge.client.render.target;

import net.minecraft.client.shader.Framebuffer;
import net.minecraftforge.client.render.NonGlTestBackend;
import net.minecraftforge.client.render.vertex.FrameDraws;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The last step of every shader-group effect: draw a target's image over the window.
 *
 * <p><b>Why this is pinned.</b> {@code Framebuffer.framebufferRenderExt} is where the entity outline, and every other
 * post-processing chain, becomes visible - the chain's passes write into targets, and this is the one draw that puts the
 * result on screen. It was skipped entirely on this backend: the method asked
 * {@code OpenGlHelper.isFramebufferEnabled()}, which answers {@code false} for a non-GL renderer (a mod's raw
 * framebuffer has nowhere to go), and read that as "there is no composite to do".</p>
 *
 * <p><b>What the picture looked like.</b> Nothing, and nothing said so: the outline's four passes ran, the glowing
 * entity's silhouette was drawn into the target, the sobel and blit passes sampled each other's images, and the result
 * was never composited. Vanilla was the only thing on screen, so the effect read as "the outline does not work" rather
 * than "the last draw is missing".</p>
 */
class FramebufferCompositeTest {

    private final List<FramebufferVkTest.FakeTarget> created = new ArrayList<>();

    @BeforeEach
    void selectANonGlBackendAndAFakeDevice() {
        NonGlTestBackend.select();
        created.clear();
        RenderTargets.install((width, height, useDepth) -> {
            FramebufferVkTest.FakeTarget target =
                    new FramebufferVkTest.FakeTarget(width, height, useDepth, created.size() + 1);
            created.add(target);
            return target;
        });
    }

    @AfterEach
    void restore() {
        FrameDraws.drain();
        RenderTargets.install(null);
        NonGlTestBackend.restore();
    }

    @Test
    void anOffscreenTargetsCompositeIsRecordedAsADrawSamplingIt() {
        Framebuffer framebuffer = new Framebuffer(320, 240, true);
        int handle = framebuffer.framebufferTexture;
        assertTrue(handle > 0, "an offscreen target has a texture handle to sample");

        FrameDraws.beginCollecting();
        framebuffer.framebufferRenderExt(854, 480, false);

        List<FrameDraws.Operation> ops = FrameDraws.drain();
        assertEquals(1, ops.size(), "the composite is one draw, got " + ops);
        FrameDraws.Draw composite = assertInstanceOf(FrameDraws.Draw.class, ops.get(0));
        assertEquals(4, composite.vertexCount(), "a full-screen quad");
        assertEquals(handle, composite.state().texture(),
                "the composite samples the target's own image, not whatever was bound before it");
    }

    @Test
    void theWindowsCompositeStaysTheIdentity() {
        // The window's colour attachment *is* the swapchain image, so drawing it onto itself is nothing. That case is
        // answered by the backend and has to stay a no-op rather than becoming a quad sampling a texture it has not got.
        Framebuffer window = new Framebuffer(854, 480, true, true);
        assertEquals(0, window.framebufferTexture, "a window target has no handle of its own");

        FrameDraws.beginCollecting();
        window.framebufferRenderExt(854, 480, false);
        assertEquals(0, FrameDraws.drain().size(), "nothing is drawn for the window's own image");
        assertTrue(created.isEmpty(), "and no attachment was allocated for it");
    }
}
