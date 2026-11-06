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
        throw needsFrame("bind");
    }

    @Override
    public void unbind() {
        throw needsFrame("unbind");
    }

    @Override
    public void clear() {
        throw needsFrame("clear (" + width + "x" + height + ", depth=" + useDepth + ")");
    }

    @Override
    public void render(int width, int height, boolean disableBlend) {
        throw needsFrame("render " + width + "x" + height);
    }

    @Override
    public void setColor(float red, float green, float blue, float alpha) {
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
