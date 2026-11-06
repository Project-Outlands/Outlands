package net.minecraftforge.client.render.vulkan;

import net.minecraftforge.client.render.target.FrameRecording;

/**
 * The requests vanilla makes of a frame, held until the frame is actually recorded.
 *
 * <p><b>Why the target is pinned to the default.</b> Only one colour target exists in this backend: the window's
 * image. A framebuffer that is not the game's main one therefore has nowhere to go, and saying so is better than
 * quietly drawing it to the screen - a wrong picture with no explanation is exactly the failure mode this project
 * keeps rejecting.</p>
 */
final class VulkanFrameRecording implements FrameRecording {

    /** GL_COLOR_BUFFER_BIT. */
    private static final int COLOR_BIT = 0x4000;
    /** GL_DEPTH_BUFFER_BIT. */
    private static final int DEPTH_BIT = 0x100;

    private boolean targetDefault;
    private boolean colorClear;
    private boolean depthClear;
    private float[] clearColor = {0.0F, 0.0F, 0.0F, 1.0F};
    private double clearDepth = 1.0;
    private int width;
    private int height;

    @Override
    public void targetDefault(int width, int height, boolean setViewport) {
        this.targetDefault = true;
        this.width = width;
        this.height = height;
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
        colorClear |= (glMask & COLOR_BIT) != 0;
        depthClear |= (glMask & DEPTH_BIT) != 0;
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

    /** The size the game asked for, so the frame can be checked against the swapchain's extent. */
    int requestedWidth() {
        return width;
    }

    int requestedHeight() {
        return height;
    }
}
