package net.minecraftforge.client.render.target;

import net.minecraftforge.client.render.vertex.FrameDraws;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The framebuffer backend, against a fake device.
 *
 * <p><b>What is worth testing here.</b> Not that an image exists - that needs a real GPU - but the bookkeeping the game
 * depends on: which framebuffers get attachments at all, that a resize reuses the same texture handle (the game caches
 * the number), that delete gives the attachment back, and that the window's target never allocates. Getting any of
 * those wrong is a leak, a stale handle, or a frame rendered into an image nobody shows, and none of them throw.</p>
 */
class FramebufferVkTest {

    private final List<FakeTarget> created = new ArrayList<>();

    @BeforeEach
    void installFactory() {
        created.clear();
        RenderTargets.install((width, height, useDepth) -> {
            FakeTarget target = new FakeTarget(width, height, useDepth, created.size() + 1);
            created.add(target);
            return target;
        });
    }

    @AfterEach
    void uninstallFactory() {
        RenderTargets.install(null);
    }

    @Test
    void anOffscreenFramebufferAllocatesOneTargetAndKeepsItsHandle() {
        FramebufferVk framebuffer = new FramebufferVk(false);
        framebuffer.create(320, 240, true);

        assertEquals(1, created.size(), "one target, not one per call");
        assertEquals(320, created.get(0).width());
        assertEquals(240, created.get(0).height());
        assertTrue(created.get(0).useDepth(), "the depth attachment the game asked for");
        int handle = framebuffer.textureId();
        assertEquals(1, handle, "the handle is the target's");

        // A resize is vanilla's createBindFramebuffer on a window change: same framebuffer, same handle.
        framebuffer.create(640, 480, true);
        assertEquals(1, created.size(), "a resize is not a second target");
        assertEquals(640, created.get(0).width(), "the same target was resized");
        assertEquals(handle, framebuffer.textureId(), "the handle has to survive, the game cached it");
    }

    @Test
    void theFilterIsAppliedToTheTargetWhenItExists() {
        FramebufferVk framebuffer = new FramebufferVk(false);
        framebuffer.create(16, 16, false);
        framebuffer.setFilter(9728);

        assertEquals(9728, created.get(0).filter(), "GL_NEAREST reaches the attachment");
        framebuffer.create(32, 32, false);
        assertEquals(9728, created.get(0).filter(), "and survives a resize");
    }

    @Test
    void deletingReleasesTheTargetAndForgetsTheHandle() {
        FramebufferVk framebuffer = new FramebufferVk(false);
        framebuffer.create(64, 64, false);
        int handle = framebuffer.textureId();
        assertTrue(handle != 0);

        framebuffer.delete();

        assertTrue(created.get(0).closed, "the attachment is released");
        assertEquals(0, framebuffer.textureId(), "and there is no handle to sample any more");
    }

    @Test
    void theWindowFramebufferAllocatesNothing() {
        // Its colour attachment is the swapchain image, which the frame is already painting. A second image would mean
        // copying every finished frame into the window.
        FramebufferVk framebuffer = new FramebufferVk(true);
        framebuffer.create(1920, 1080, true);
        framebuffer.setFilter(9728);
        framebuffer.delete();

        assertTrue(created.isEmpty(), "the window has no target of its own");
        assertEquals(0, framebuffer.textureId(), "and nothing to sample: 0 is GL for no texture");
    }

    @Test
    void bindingTheWindowTargetGoesToTheFrame() {
        TestRecording recording = new TestRecording();
        FrameRecordings.install(recording);
        try {
            FramebufferVk framebuffer = new FramebufferVk(true);
            framebuffer.create(800, 600, true);

            framebuffer.bind(true);
            assertTrue(recording.targetDefault, "the frame's own image is what the game means");
            assertEquals(800, recording.width);
            assertEquals(600, recording.height);
            assertTrue(recording.setViewport, "bindFramebuffer(true) asks for the viewport too");

            framebuffer.clear();
            assertTrue(recording.colorClear, "the clear is the colour buffer's");
            assertTrue(recording.depthClear, "and the depth buffer's, because this target has one");
        } finally {
            FrameRecordings.install(null);
        }
    }

    @Test
    void theLoadingScreensWholeSequenceWorksOnTheWindowTarget() {
        // LoadingScreenRenderer.setLoadingProgress, in order: clear the target, bind it, draw the progress text, unbind
        // it, composite it over the window. It is a full-window overlay with no depth, so it is constructed as the
        // window's target - and the sequence has to stay legal, because the alternative was a crash in
        // Minecraft.loadWorld when the target was treated as offscreen.
        TestRecording recording = new TestRecording();
        FrameRecordings.install(recording);
        try {
            FramebufferVk loading = new FramebufferVk(true);
            loading.setFilter(9728);
            loading.create(854, 480, false);

            loading.clear();
            loading.bind(false);
            loading.unbind();
            loading.render(854, 480, false);

            assertEquals(0, loading.textureId(), "a window target has no handle of its own to sample");
            assertTrue(created.isEmpty(), "and no attachment was allocated for it");
        } finally {
            FrameRecordings.install(null);
        }
    }

    @Test
    void anOffscreenTargetsBindAndUnbindAreRecordedAsSwitches() {
        // What the frame recorder replays: end the instance, move both images between layouts, open another one.
        // The handle travels in the operation, and 0 means the window's image - where a frame starts and returns.
        TestRecording recording = new TestRecording();
        FrameRecordings.install(recording);
        FrameDraws.beginCollecting();
        try {
            FramebufferVk framebuffer = new FramebufferVk(false);
            framebuffer.create(300, 200, true);
            int handle = framebuffer.textureId();

            framebuffer.bind(true);
            framebuffer.unbind();

            List<FrameDraws.Operation> ops = List.copyOf(FrameDraws.drain());
            assertEquals(2, ops.size(), "one switch in, one out: " + ops);
            assertTrue(ops.get(0) instanceof FrameDraws.TargetBind, "the first is the switch into the target");
            FrameDraws.TargetBind into = (FrameDraws.TargetBind) ops.get(0);
            assertEquals(handle, into.target());
            assertEquals(300, into.width());
            assertEquals(200, into.height());
            assertTrue(into.setViewport(), "bindFramebuffer(true) asks for the viewport too");
            assertTrue(ops.get(1) instanceof FrameDraws.TargetBind, "and the second is the switch back");
            assertEquals(0, ((FrameDraws.TargetBind) ops.get(1)).target(), "0 is the window's image");
            assertTrue(!recording.targetDefault, "none of it was recorded as the window's own target");
        } finally {
            // drain(), not beginCollecting(): the latter *starts* collecting, which leaked a live collector into
            // every test that ran after this one - DisplayListVkTest's "replaying needs a frame" case then found a
            // frame and passed the wrong branch.
            FrameDraws.drain();
            FrameRecordings.install(null);
        }
    }

    @Test
    void aFramebufferCreatedWithoutARendererFailsWithItsReason() {
        RenderTargets.install(null);
        FramebufferVk framebuffer = new FramebufferVk(false);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> framebuffer.create(100, 100, false));
        assertTrue(failure.getMessage().contains("renderer"), failure.getMessage());
    }

    /**
     * Asking for stencil is answered, not thrown at.
     *
     * <p>Measured: Immersive Engineering asks in {@code preInit} -
     * {@code if (OpenGlHelper.framebufferSupported && IEConfig.stencilBufferEnabled && !fb.isStencilEnabled())
     * stencilBufferEnabled = fb.enableStencil();} - and both of its conditions are true on this backend, so the
     * exception that used to be here failed mod loading. The vanilla contract is a boolean, and a mod that gets
     * false draws its effect without the mask (IE draws the bottling machine's item unclipped) instead of the game
     * not starting.</p>
     */
    @Test
    void askingForStencilIsAnsweredRatherThanThrownAt() {
        FramebufferVk framebuffer = new FramebufferVk(false);
        framebuffer.create(100, 100, true);

        // The depth format this backend picks always carries 8 stencil bits and the image view covers both aspects,
        // so the attachment is there and the answer is yes. What matters for the crash this replaced is that the
        // answer is an answer: Immersive Engineering asks in preInit and branches on it.
        assertEquals(true, framebuffer.setStencil(true), "the depth attachment has stencil bits");
        assertEquals(true, framebuffer.setStencil(false), "not asking for one always succeeds");
    }

    @Test
    void bindingAnOffscreenTargetWithNoFrameSaysSo() {
        // Nothing is collecting, so there is no frame to put the switch in. Saying that is better than appending to a
        // list nobody will replay.
        FrameRecordings.install(null);
        FramebufferVk framebuffer = new FramebufferVk(false);
        framebuffer.create(100, 100, false);
        UnsupportedOperationException failure = assertThrows(UnsupportedOperationException.class,
                () -> framebuffer.bind(false));
        assertTrue(failure.getMessage().contains("no frame"), failure.getMessage());
        assertNull(FrameRecordings.current());
    }

    /** A target that records what was asked of it. */
    private static final class FakeTarget implements RenderTarget {
        private final int id;
        private int width;
        private int height;
        private boolean useDepth;
        private int filter = 9729;
        private boolean closed;

        FakeTarget(int width, int height, boolean useDepth, int id) {
            this.width = width;
            this.height = height;
            this.useDepth = useDepth;
            this.id = id;
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }

        @Override
        public boolean useDepth() {
            return useDepth;
        }

        int filter() {
            return filter;
        }

        @Override
        public int textureId() {
            return id;
        }

        @Override
        public void resize(int width, int height, boolean useDepth) {
            this.width = width;
            this.height = height;
            this.useDepth = useDepth;
        }

        @Override
        public void setFilter(int glFilter) {
            this.filter = glFilter;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** A frame that records what the game asked for. */
    private static final class TestRecording implements FrameRecording {
        boolean targetDefault;
        boolean setViewport;
        int width;
        int height;
        boolean colorClear;
        boolean depthClear;
        float[] clearColor = {0.0F, 0.0F, 0.0F, 1.0F};
        double clearDepth = 1.0;

        @Override
        public void targetDefault(int width, int height, boolean setViewport) {
            this.targetDefault = true;
            this.width = width;
            this.height = height;
            this.setViewport = setViewport;
        }

        @Override
        public boolean isTargetDefault() {
            return targetDefault;
        }

        @Override
        public void setClearColor(float red, float green, float blue, float alpha) {
            clearColor = new float[] {red, green, blue, alpha};
        }

        @Override
        public void setClearDepth(double depth) {
            clearDepth = depth;
        }

        @Override
        public void requestClear(int glMask) {
            colorClear |= (glMask & 0x4000) != 0;
            depthClear |= (glMask & 0x100) != 0;
        }

        @Override
        public float[] pendingClearColor() {
            return clearColor;
        }

        @Override
        public double pendingClearDepth() {
            return clearDepth;
        }

        @Override
        public boolean wantsColorClear() {
            return colorClear;
        }

        @Override
        public boolean wantsDepthClear() {
            return depthClear;
        }

        @Override
        public void resetForNextFrame() {
            targetDefault = false;
            colorClear = false;
            depthClear = false;
        }
    }
}
