package net.minecraftforge.client.render.target;

import net.minecraftforge.client.render.vertex.FrameDraws;

/**
 * A render target on the Vulkan backend.
 *
 * <p><b>Two kinds of framebuffer, one class.</b> The game's main framebuffer <i>is</i> the swapchain's colour image -
 * the frame is already painting it - while every other framebuffer owns an image of its own. The difference is passed
 * in at construction by the one caller that knows it ({@link FramebufferBackends#forNonGlWindow()}). Conflating them
 * would either copy the whole frame into the window every frame, or render the game into an image nobody shows.</p>
 *
 * <p><b>What is implemented.</b> The state the game is allowed to ask about without a frame: size, colour, filter,
 * stencil flag, and the texture handle of an offscreen target's colour attachment - the last of which is allocated here
 * against the device, through {@link RenderTargets}, because that is who owns the image.</p>
 *
 * <p><b>What still throws, and why that is not a regression.</b> {@link #clear}, {@link #bind}, {@link #unbind} and
 * {@link #render} say what the frame being recorded should do. For the window's target they already mean something
 * (the frame starts on that image), so they are answered. For an offscreen target they mean "end this rendering
 * instance, transition the two images, open another one" - a target switch - which the frame recorder does not record
 * yet. Saying so is the remaining work item; succeeding silently would draw the game into an image nothing shows,
 * which is the failure mode with no exception attached.</p>
 *
 * <p><b>Why none of these are no-ops.</b> A no-op {@code clear} would leave a target's contents undefined and blame the
 * wrong subsystem. The rule from the plan - no silent degradation - applies here exactly.</p>
 */
final class FramebufferVk implements FramebufferBackend {

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("Outlands|render");

    /** Whether this is the game's main framebuffer, whose colour attachment is the window's image. */
    private final boolean window;

    private int width;
    private int height;
    private boolean useDepth;
    private boolean stencil;
    private int filter = 9729;
    private float red = 1.0F;
    private float green = 1.0F;
    private float blue = 1.0F;
    private float alpha;

    /** The offscreen target, or {@code null} for the window's. */
    private RenderTarget target;

    FramebufferVk(boolean window) {
        this.window = window;
    }

    @Override
    public void create(int width, int height, boolean useDepth) {
        this.width = width;
        this.height = height;
        this.useDepth = useDepth;
        if (window) {
            // Nothing to allocate: the attachments are the swapchain's, and they are resized with it.
            LOGGER.info("window framebuffer {}x{}: the swapchain image, no target allocated", width, height);
            return;
        }
        if (target == null) {
            target = factory().create(width, height, useDepth);
            target.setFilter(filter);
        } else {
            target.resize(width, height, useDepth);
        }
    }

    private static RenderTargets.Factory factory() {
        RenderTargets.Factory factory = RenderTargets.current();
        if (factory == null) {
            throw new IllegalStateException("a framebuffer that is not the window's was created before the renderer"
                    + " installed a device, so there is nothing to allocate its attachments on. This is a renderer"
                    + " that failed to initialise, not a problem with the request.");
        }
        return factory;
    }

    @Override
    public int textureId() {
        // The window's colour is not sampleable by handle: it is the swapchain image, and the frame is painting it.
        return target == null ? 0 : target.textureId();
    }

    @Override
    public void delete() {
        if (target != null) {
            target.close();
            target = null;
        }
        this.width = 0;
        this.height = 0;
    }

    @Override
    public boolean isWindow() {
        return window;
    }

    @Override
    public void bind(boolean setViewport) {
        if (!window) {
            // A recorded switch, in its place in the frame's sequence: the backend ends the instance it is in, moves
            // both images to their layouts and opens another one over this target.
            if (!FrameDraws.recordTargetBind(target.textureId(), width, height, setViewport)) {
                throw needsFrame("bind " + width + "x" + height);
            }
            return;
        }
        FrameRecording recording = FrameRecordings.current();
        if (recording == null) {
            throw needsFrame("bind");
        }
        // The window's image is what the frame is already painting, so binding it is the frame's default target.
        recording.targetDefault(width, height, setViewport);
    }

    @Override
    public void unbind() {
        if (!window) {
            // Back to the window's image, which is where a frame starts and usually ends.
            if (!FrameDraws.recordTargetBind(0, width, height, false)) {
                throw needsFrame("unbind " + width + "x" + height);
            }
            return;
        }
        FrameRecording recording = FrameRecordings.current();
        if (recording == null) {
            throw needsFrame("unbind");
        }
        // Returning to the screen is the same request as binding it, because they are the same image.
        recording.targetDefault(width, height, false);
    }

    @Override
    public void clear() {
        if (!window) {
            // The patched Framebuffer runs its own body for a target with an image: bind, GlStateManager.clear,
            // unbind - all three of which are recorded operations. Reaching here means something bypassed that.
            throw new IllegalStateException("clear() on an offscreen framebuffer reached the backend directly;"
                    + " Framebuffer.framebufferClear runs its own body for these, through FrameStateVk's clear");
        }
        FrameRecording recording = FrameRecordings.current();
        if (recording == null) {
            throw needsFrame("clear (" + width + "x" + height + ", depth=" + useDepth + ")");
        }
        // The colour is this target's own; the depth value is whatever GlStateManager.clearDepth last set, because
        // that is the call that owns it. Both converge on the frame recording.
        recording.setClearColor(red, green, blue, alpha);
        // GL_COLOR_BUFFER_BIT, plus depth when this target has one - the same mask vanilla would pass.
        recording.requestClear(0x4000 | (useDepth ? 0x100 : 0));
    }

    /**
     * Draws this target's colour attachment over the current one.
     *
     * <p><b>The vanilla body does this one, for a target with an image.</b> {@code framebufferRenderExt} binds the
     * target's texture, sets up an ortho projection and draws a {@code POSITION_TEX_COLOR} quad through the
     * Tessellator - and every one of those calls already routes through the shim, so the composite is an ordinary
     * streamed draw sampling the target by its handle. Only the window's is answered here, where it is the
     * identity.</p>
     *
     * <p><b>A no-op for the window, not a missing feature.</b> This backend has one colour target for the window: the
     * image the game calls the main framebuffer. Drawing that image onto itself is the identity, so the offscreen
     * indirection GL needs - render to a texture, then blit the texture to the screen - does not exist here to be
     * replicated. An offscreen target's composite is real work and belongs with the target switch, below.</p>
     */
    @Override
    public void render(int width, int height, boolean disableBlend) {
        if (FrameRecordings.current() == null) {
            throw needsFrame("render " + width + "x" + height);
        }
        if (!window) {
            throw new IllegalStateException("composite of an offscreen framebuffer reached the backend directly;"
                    + " Framebuffer.framebufferRenderExt draws it through the stream path");
        }
    }

    @Override
    public void setColor(float red, float green, float blue, float alpha) {
        FrameRecording recording = FrameRecordings.current();
        if (recording != null) {
            recording.setClearColor(red, green, blue, alpha);
        }
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.alpha = alpha;
    }

    @Override
    public void setFilter(int glFilter) {
        this.filter = glFilter;
        if (target != null) {
            target.setFilter(glFilter);
        }
    }

    /**
     * Nothing to do, and that is the honest answer rather than a stub.
     *
     * <p>An offscreen target's colour attachment is registered under its handle when it is created, so it can be
     * sampled at any time without binding anything. The game calls this because GL needs it; here the handle already
     * names the image.</p>
     */
    @Override
    public void bindTexture() {
    }

    /** Counterpart of {@link #bindTexture()}. */
    @Override
    public void unbindTexture() {
    }

    /**
     * Stencil is not implemented, and asking for it says so.
     *
     * <p>Recording the flag and carrying on would give a mod a target whose stencil it cannot use, and the symptom
     * would be a mask that does nothing - a picture that is wrong with no error anywhere. Nothing in vanilla asks for
     * one; the entity outline uses alpha, not stencil.</p>
     */
    @Override
    public void setStencil(boolean enabled) {
        if (enabled) {
            throw new UnsupportedOperationException(
                    "stencil attachments are not implemented on the Vulkan backend. A target created without one and a"
                            + " shader that writes to it would produce a mask nobody applies. See"
                            + " docs/vulkan-backend-plan.md 11.6.");
        }
        this.stencil = false;
    }

    @Override
    public void checkComplete() {
        // Nothing to check: Vulkan has no framebuffer-completeness concept, and every attachment this backend makes
        // has the same extent as its target by construction.
    }

    /** The target this framebuffer owns, for tests and for the frame recorder that will draw into it. */
    RenderTarget target() {
        return target;
    }

    private static UnsupportedOperationException needsFrame(String what) {
        return new UnsupportedOperationException(
                "cannot " + what + " on the Vulkan backend yet: no frame is being recorded, so there is no command"
                        + " buffer to put the command in. Needed: a recording context on the render graph node, and"
                        + " render-target attachments the graph owns. See docs/vulkan-backend-plan.md 8.4.2.");
    }

    private static UnsupportedOperationException needsTargetSwitch(String what) {
        return new UnsupportedOperationException(
                "cannot " + what + " on the Vulkan backend yet: the target exists and its attachment is allocated, but"
                        + " switching the frame's rendering instance from one target to another is not recorded yet."
                        + " Needed: a TargetBind operation in the frame's command stream, which ends the instance,"
                        + " transitions both images and opens another one. See docs/vulkan-backend-plan.md 11.4 S2.");
    }
}
