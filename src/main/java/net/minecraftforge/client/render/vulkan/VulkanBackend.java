package net.minecraftforge.client.render.vulkan;

import net.minecraftforge.client.render.RenderBackend;
import net.minecraftforge.client.render.RenderBackendSelection;
import net.minecraftforge.client.render.ChunkMeshCapture;
import net.minecraftforge.client.render.RenderBackends;
import net.minecraftforge.client.render.graph.RenderGraph;
import net.minecraftforge.client.render.graph.RenderGraphs;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.sdl.SDLVideo;

/**
 * The Vulkan render backend.
 *
 * <p>It owns the frame: instance, surface, device, swapchain, depth attachment and the frame loop. What it does
 * not have yet is anything to draw - every frame clears to black and presents. That is the whole of this
 * milestone: proving the window, the device and the presentation path work on a real machine before any renderer
 * depends on them.</p>
 *
 * <p><b>Why the probe may only answer yes when the frame really is owned.</b> Inside an {@code SDL_WINDOW_VULKAN}
 * window there is no GL context, so no phase can be drawn by the vanilla renderer instead. A backend that cannot
 * cover every phase it is responsible for must not be selected - see {@link RenderBackend}. Clearing the screen
 * counts as owning the frame; drawing terrain does not have to work yet.</p>
 *
 * <p><b>Failure is contained.</b> If setup fails, the window has already been created as a Vulkan window and
 * cannot be turned back into a GL one. So the backend reports the failure, logs it, and presents nothing: the
 * alternative would be a half-initialised state that is harder to diagnose than a black window with a stack
 * trace.</p>
 */
public final class VulkanBackend implements RenderBackend {

    /** Stable id, used in configuration, logs and {@link RenderBackends}. */
    public static final String ID = "vulkan";

    /**
     * The priority this backend is registered with on the {@code vulkan-backend} development branch.
     *
     * <p><b>Branch development only.</b> It is deliberately higher than any other candidate, so that the branch
     * boots into Vulkan without anyone having to pass {@code -Doutlands.render.backend}. Note that
     * {@code vanilla-gl} is not a candidate at all - it is the fallback target - so "above the vanilla renderer"
     * means "the highest-priority candidate there is".</p>
     *
     * <p><b>This value has to be revisited before the branch merges.</b> Whether the shipped default is Vulkan,
     * opt-in through the system property, or decided by a hardware probe is a separate decision that has not been
     * made. The install log in {@link #install()} exists so that forgetting this is visible rather than silent.</p>
     */
    public static final int BRANCH_DEVELOPMENT_PRIORITY = 1_000_000;

    private static final Logger LOGGER = LogManager.getLogger("Outlands|render");

    private static final VulkanBackend INSTANCE = new VulkanBackend();

    private VulkanContext context;
    private VulkanSwapchain swapchain;
    private VulkanTerrainLayer terrainLayer;
    private VulkanDescriptors descriptors;
    private VulkanTextures textureCache;
    /** What the game asks of each frame; installed so Framebuffer's calls land here instead of in GL. */
    private final VulkanFrameRecording frameRecording = new VulkanFrameRecording();

    /** Whether to draw into an offscreen target and composite it back every frame. See {@link #selfTestComposite()}. */
    private final boolean selfTestComposite =
            "composite".equals(System.getProperty("outlands.render.selftest.targets"));
    /** Replays the game's own draws into the frame's rendering instance. */
    private VulkanStream stream;
    private VulkanRenderTargets renderTargets;
    private net.minecraft.client.shader.Framebuffer selfTestFramebuffer;
    private boolean selfTestReported;
    private long window;
    private boolean failed;

    private VulkanBackend() {}

    /**
     * Registers this backend and its selection candidate.
     *
     * <p>Idempotent, and safe to call before anything else asks for a backend. A call after the selection has
     * already happened is ignored: the window flags are fixed by then, so a late registration could not change the
     * outcome, and accepting it would only make the registry disagree with the process.</p>
     */
    public static void install() {
        if (RenderBackends.isFrozen() || RenderBackends.get(ID) != null) {
            return;
        }
        RenderBackends.register(INSTANCE);
        RenderBackendSelection.register(
                new RenderBackendSelection.Candidate(INSTANCE, VulkanBackend::isAvailable,
                        BRANCH_DEVELOPMENT_PRIORITY));
        if (isAvailable()) {
            LOGGER.warn("Vulkan is the preferred render backend (branch development mode): this build boots into "
                    + "Vulkan, which currently clears the screen and draws nothing else. Revisit the default "
                    + "before merge.");
            LOGGER.warn("The capability table does not declare GL 2.0/2.1, so entity outlines (including the one the "
                    + "spectral arrow needs) and post-processing are skipped. Shader objects themselves work - the "
                    + "game's programs ship as precompiled SPIR-V modules and anything else is compiled from GLSL - "
                    + "but a ShaderGroup needs a second render target and a program draw path, and neither exists "
                    + "yet. This is our gap, not the hardware's - the main-menu warning about an old graphics card is "
                    + "suppressed on a non-GL backend for that reason.");
        } else {
            LOGGER.info("Vulkan backend registered as the preferred candidate, but it reports unavailable; the "
                    + "vanilla renderer will be used.");
        }
    }

    /**
     * Whether Vulkan can own the frame on this machine.
     *
     * <p>Answered by {@link VulkanProbe} before the window exists, and cached. The question is "can this own a
     * frame", not "is the renderer finished": the phases are still missing, and they are added behind the same
     * window rather than behind a second decision.</p>
     */
    static boolean isAvailable() {
        return VulkanProbe.isAvailable();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public long sdlWindowFlags() {
        return SDLVideo.SDL_WINDOW_VULKAN;
    }

    @Override
    public boolean isOpenGl() {
        // No GL context is created for this window, so there is no GL state for GlStateView to report either.
        return false;
    }

    @Override
    public void init(long window) {
        if (failed) {
            return;
        }
        this.window = window;
        try {
            context = VulkanContext.create(window);
            // The swapchain first: it is what decides the attachment formats, and the pipeline has to be built for
            // exactly those. Then the phases this backend implements, then the freeze, so the completeness check
            // sees them.
            RenderGraph graph = RenderGraphs.get();
            swapchain = VulkanSwapchain.create(context, window, graph);
            // One descriptor layout and one texture cache for the whole renderer: the terrain node and the streamed
            // draws sample through the same set 0, binding 0, so a texture is uploaded once however many paths use it.
            net.minecraftforge.client.render.texture.TextureBackend textures =
                    java.util.Objects.requireNonNull(
                            net.minecraftforge.client.render.texture.TextureBackends.forNonGl(),
                            "the Vulkan path installs a texture backend before anything can draw");
            descriptors = new VulkanDescriptors(context.device());
            textureCache = new VulkanTextures(context, descriptors, textures,
                    isSrgbFormat(swapchain.imageFormat()));
            terrainLayer = VulkanTerrainLayer.create(context, swapchain.imageFormat(), swapchain.depthFormat(),
                    descriptors, textureCache);
            stream = new VulkanStream(context.device(), context, swapchain.imageFormat(), swapchain.depthFormat(),
                    descriptors, textureCache);
            // Framebuffers the game creates after this point - the shader groups among them - get their attachments
            // from here. Their formats are the swapchain's, because every pipeline in this renderer is built for that
            // one format (see docs/vulkan-backend-plan.md 11.3 D1).
            renderTargets = new VulkanRenderTargets(context, textureCache, swapchain.imageFormat(),
                    swapchain.depthFormat());
            net.minecraftforge.client.render.target.RenderTargets.install(renderTargets);
            stream.setRenderTargets(renderTargets);
            if (Boolean.getBoolean("outlands.render.selftest.targets")) {
                // Allocates a target on the real device: the one part of this that a unit test cannot prove is that
                // the validation layers accept a COLOR_ATTACHMENT|SAMPLED image and a DEPTH-aspect view, and that the
                // handle it registers is the one the texture cache resolves.
                selfTestRenderTargets();
            }
            stream.setTerrain(terrainLayer);
            swapchain.setStream(stream);
            swapchain.setTextures(textureCache);
            swapchain.setFrameRecording(frameRecording);
            net.minecraftforge.client.render.target.FrameRecordings.install(frameRecording);
            // Draws issued from here on belong to the next frame.
            net.minecraftforge.client.render.vertex.FrameDraws.beginCollecting();
            graph.replace(terrainLayer);
            graph.freeze();
            // Only now, once the graph is frozen and the layer exists, does anything start capturing chunk meshes.
            // The consumer is the layer itself: it owns the buffers the meshes become.
            // Enable chunk mesh capture so VulkanTerrainLayer receives chunk meshes.
            ChunkMeshCapture.enable();
        } catch (RuntimeException | LinkageError e) {
            // The window is already a Vulkan window; there is no GL context to fall back to. Record the failure,
            // log it once, and present nothing rather than throwing from inside the render loop every frame.
            failed = true;
            LOGGER.error("Vulkan backend setup failed; the window will stay black", e);
            close();
        }
    }

    @Override
    public void present() {
        if (failed || swapchain == null) {
            return;
        }
        try {
            // The game reports which texture its lightmap lives in when it replaces it; the stream samples that image
            // through descriptor set 1, which is bound once per frame.
            net.minecraftforge.client.render.state.backend.FrameState frameState =
                    net.minecraftforge.client.render.state.backend.FrameStates.forNonGl();
            if (stream != null && frameState != null) {
                stream.setLightmapTexture(frameState.lightmapTexture());
            }
            if (selfTestComposite) {
                selfTestComposite();
            }
            // Whatever the game drew while building this frame is replayed inside it, then the next frame starts
            // collecting from empty.
            swapchain.setPendingDraws(net.minecraftforge.client.render.vertex.FrameDraws.drain());
            try {
                swapchain.drawFrame(window);
            } finally {
                frameRecording.resetForNextFrame();
                net.minecraftforge.client.render.vertex.FrameDraws.beginCollecting();
            }
        } catch (RuntimeException e) {
            failed = true;
            LOGGER.error("Vulkan frame failed; stopping presentation", e);
            close();
        }
    }

    /**
     * A framebuffer drawn into and composited back, once per frame, through the game's own code paths.
     *
     * <p>Gated by {@code -Doutlands.render.selftest.targets=composite}. What it proves is what a unit test cannot: the
     * frame ends its rendering instance, moves the target's images between layouts, draws into them off-screen and
     * composites the result over the window - on the real device, with the validation layers listening. A red square in
     * the window's top-left corner is the expected picture; the square's position is also the orientation check, since
     * a composited image that comes back upside down puts the square in the wrong corner.</p>
     *
     * <p><b>Why the vanilla objects.</b> {@code Framebuffer} is what the game uses: {@code framebufferClear} binds,
     * clears and unbinds, and {@code framebufferRender} binds the target's texture and draws one textured quad through
     * the Tessellator. Driving those means the test exercises the paths a shader group will use, rather than a
     * parallel implementation that could agree with itself and disagree with the game.</p>
     */
    private void selfTestComposite() {
        try {
            if (selfTestFramebuffer == null) {
                selfTestFramebuffer = new net.minecraft.client.shader.Framebuffer(256, 256, true);
                selfTestFramebuffer.setFramebufferColor(1.0F, 0.0F, 0.0F, 1.0F);
                selfTestFramebuffer.setFramebufferFilter(9728);
                LOGGER.info("selftest: composite framebuffer {}x{} texture {}",
                        selfTestFramebuffer.framebufferTextureWidth, selfTestFramebuffer.framebufferTextureHeight,
                        selfTestFramebuffer.framebufferTexture);
            }
            selfTestFramebuffer.framebufferClear();
            selfTestMarker();
            selfTestFramebuffer.framebufferRender(256, 256);
        } catch (RuntimeException e) {
            // Reported once rather than every frame: the point of the self-test is a diagnosable state, not a log flood.
            if (!selfTestReported) {
                selfTestReported = true;
                LOGGER.error("selftest: composite of an offscreen target failed", e);
            }
        }
    }

    /**
     * Draws a green square at the target's top-left, through the ordinary vertex path.
     *
     * <p>The point is orientation: a target cleared to one colour cannot show whether the composite flipped it, but a
     * marker in a corner can. The quad is drawn in the game's own coordinates - an ortho over the target's size with y
     * pointing down, exactly as {@code framebufferRenderExt} sets up for its own quad - so if the composite is faithful
     * the marker appears in the same corner of the window as it had in the target, and a flip shows up as the opposite
     * corner.</p>
     */
    private void selfTestMarker() {
        selfTestFramebuffer.bindFramebuffer(true);
        try {
            net.minecraft.client.renderer.GlStateManager.matrixMode(org.lwjgl.opengl.GL11.GL_PROJECTION);
            net.minecraft.client.renderer.GlStateManager.loadIdentity();
            net.minecraft.client.renderer.GlStateManager.ortho(0.0, 256.0, 256.0, 0.0, 1000.0, 3000.0);
            net.minecraft.client.renderer.GlStateManager.matrixMode(org.lwjgl.opengl.GL11.GL_MODELVIEW);
            net.minecraft.client.renderer.GlStateManager.loadIdentity();
            net.minecraft.client.renderer.GlStateManager.translate(0.0F, 0.0F, -2000.0F);
            net.minecraft.client.renderer.GlStateManager.disableTexture2D();
            net.minecraft.client.renderer.GlStateManager.disableDepth();

            net.minecraft.client.renderer.Tessellator tessellator =
                    net.minecraft.client.renderer.Tessellator.getInstance();
            net.minecraft.client.renderer.BufferBuilder buffer = tessellator.getBuffer();
            buffer.begin(7, net.minecraft.client.renderer.vertex.DefaultVertexFormats.POSITION_COLOR);
            buffer.pos(0.0, 96.0, 0.0).color(0, 255, 0, 255).endVertex();
            buffer.pos(96.0, 96.0, 0.0).color(0, 255, 0, 255).endVertex();
            buffer.pos(96.0, 0.0, 0.0).color(0, 255, 0, 255).endVertex();
            buffer.pos(0.0, 0.0, 0.0).color(0, 255, 0, 255).endVertex();
            tessellator.draw();
        } finally {
            selfTestFramebuffer.unbindFramebuffer();
        }
    }

    /** Creates, resizes and releases a render target, reporting what the device said. Gated by a system property. */
    private void selfTestRenderTargets() {
        net.minecraftforge.client.render.target.RenderTarget target = renderTargets.create(256, 256, true);
        boolean registered = textureCache.hasExternal(target.textureId());
        LOGGER.info("selftest: target {}x{} depth={} texture={} registered={}",
                target.width(), target.height(), target.useDepth(), target.textureId(), registered);
        if (!registered) {
            throw new IllegalStateException("selftest: the target's handle was not registered, so nothing could"
                    + " sample it");
        }
        target.resize(512, 384, true);
        LOGGER.info("selftest: after resize {}x{}, same handle={}",
                target.width(), target.height(), textureCache.hasExternal(target.textureId()));
        target.close();
        LOGGER.info("selftest: after close registered={}", textureCache.hasExternal(target.textureId()));
    }

    @Override
    public void setSwapInterval(int interval) {
        if (swapchain != null) {
            swapchain.setSwapInterval(interval, window);
        }
    }

    @Override
    public int maxTextureSize() {
        // Zero when setup failed: the caller then falls through to the GL query, which will not run either, but
        // reporting a made-up number would be worse than reporting none.
        return context != null ? context.maxTextureSize() : 0;
    }

    @Override
    public Object nativeContext() {
        return context;
    }

    /**
     * Whether a colour format stores sRGB-encoded values.
     *
     * <p>The renderer needs this to decide the format of every texture it creates: sampling has to decode with the
     * same encoding the attachment encodes with, or the picture is brightened twice. Only the formats a swapchain
     * realistically reports are listed, and an unknown one is treated as linear, which is the conservative answer -
     * it renders the bytes as stored rather than inventing a transfer function.</p>
     */
    private static boolean isSrgbFormat(int format) {
        return format == org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB
                || format == org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB
                || format == org.lwjgl.vulkan.VK10.VK_FORMAT_A8B8G8R8_SRGB_PACK32;
    }



    @Override
    public void close() {
        net.minecraftforge.client.render.target.FrameRecordings.install(null);
        if (selfTestFramebuffer != null) {
            selfTestFramebuffer.deleteFramebuffer();
            selfTestFramebuffer = null;
        }
        if (renderTargets != null) {
            // The framebuffers that hold these targets outlive the backend - the game deletes its own after the
            // display goes away - so what is withdrawn here is only the ability to allocate more.
            net.minecraftforge.client.render.target.RenderTargets.uninstall(renderTargets);
            renderTargets = null;
        }
        ChunkMeshCapture.disable();
        if (swapchain != null) {
            swapchain.close();
            swapchain = null;
        }
        if (terrainLayer != null) {
            terrainLayer.close();
            terrainLayer = null;
        }
        if (stream != null) {
            stream.close();
            stream = null;
        }
        if (textureCache != null) {
            textureCache.close();
            textureCache = null;
        }
        if (descriptors != null) {
            descriptors.close();
            descriptors = null;
        }
        if (context != null) {
            context.close();
            context = null;
        }
    }

    /**
     * Test hook: pins the probe answer so tests do not depend on the machine's driver. {@code null} restores real
     * probing. Public only because the selection tests live in {@code net.minecraftforge.client.render}, next to
     * the other backend tests; production code has no reason to call it.
     */
    public static void forceAvailabilityForTests(Boolean value) {
        VulkanProbe.forceForTests(value);
    }
}
