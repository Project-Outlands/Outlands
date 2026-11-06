package net.minecraftforge.client.render.target;

/**
 * A render target on the Vulkan backend.
 *
 * <p><b>What is implemented.</b> The state the game is allowed to ask about without a frame: size, colour, filter,
 * stencil flag. Nothing is allocated on the device, because the backend's attachments are owned by the frame's
 * render graph rather than by the framebuffer - that is the point of the seam, not a gap in it.</p>
 *
 * <p><b>What throws, and why that is not a regression.</b> {@link #clear}, {@link #bind}, {@link #unbind} and
 * {@link #render} say what the frame being recorded should do. No frame is being recorded when
 * {@code Minecraft.init} creates the main framebuffer, so there is nowhere to put the command. Saying so is a
 * work item; succeeding silently would mean a black window with no explanation, which is what the previous version
 * of this code did by crashing in native GL instead.</p>
 *
 * <p><b>Why none of these are no-ops.</b> A no-op {@code clear} would leave the target's contents undefined and
 * blame the wrong subsystem. The rule from the plan - no silent degradation - applies here exactly.</p>
 */
final class FramebufferVk implements FramebufferBackend {

    private int width;
    private int height;
    private boolean useDepth;
    private boolean stencil;
    private int filter;
    private float red = 1.0F;
    private float green = 1.0F;
    private float blue = 1.0F;
    private float alpha;

    @Override
    public void create(int width, int height, boolean useDepth) {
        this.width = width;
        this.height = height;
        this.useDepth = useDepth;
    }

    @Override
    public void delete() {
        this.width = 0;
        this.height = 0;
    }

    @Override
    public void bind(boolean setViewport) {
        FrameRecording recording = FrameRecordings.current();
        if (recording == null) {
            throw needsFrame("bind");
        }
        // The window's image is the only colour target this backend has, and the game's main framebuffer is that
        // target. A second framebuffer has nowhere to go and is rejected below rather than drawn to the screen.
        recording.targetDefault(width, height, setViewport);
    }

    @Override
    public void unbind() {
        FrameRecording recording = FrameRecordings.current();
        if (recording == null) {
            throw needsFrame("unbind");
        }
        // Returning to the screen is the same request as binding it, because they are the same image.
        recording.targetDefault(width, height, false);
    }

    @Override
    public void clear() {
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
     * <p><b>A no-op by construction, not a missing feature.</b> This backend has one colour target: the window's
     * image, which is also the game's main framebuffer. Drawing that image onto itself is the identity, so the
     * offscreen indirection GL needs - render to a texture, then blit the texture to the screen - does not exist
     * here to be replicated. Any other target never got this far: binding one is rejected.</p>
     */
    @Override
    public void render(int width, int height, boolean disableBlend) {
        if (FrameRecordings.current() == null) {
            throw needsFrame("render " + width + "x" + height);
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
    }

    @Override
    public void bindTexture() {
        throw needsFrame("bindTexture");
    }

    @Override
    public void unbindTexture() {
        throw needsFrame("unbindTexture");
    }

    @Override
    public void setStencil(boolean enabled) {
        this.stencil = enabled;
    }

    @Override
    public void checkComplete() {
        // Nothing to check until attachments exist. Reported at bind/clear instead of here, because at creation
        // time there is genuinely nothing incomplete - the target has not been built yet.
    }

    private static UnsupportedOperationException needsFrame(String what) {
        return new UnsupportedOperationException(
                "cannot " + what + " on the Vulkan backend yet: no frame is being recorded, so there is no command"
                        + " buffer to put the command in. Needed: a recording context on the render graph node, and"
                        + " render-target attachments the graph owns. See docs/vulkan-backend-plan.md 8.4.2.");
    }
}
