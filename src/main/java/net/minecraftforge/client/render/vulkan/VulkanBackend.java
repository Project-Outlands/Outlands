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
    private VulkanPrograms programs;
    private VulkanDescriptors descriptors;
    private VulkanTextures textureCache;
    /** What the game asks of each frame; installed so Framebuffer's calls land here instead of in GL. */
    private final VulkanFrameRecording frameRecording = new VulkanFrameRecording();

    /** Replays the game's own draws into the frame's rendering instance. */
    private VulkanStream stream;
    private VulkanRenderTargets renderTargets;
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
        if (!isAvailable()) {
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
            programs = new VulkanPrograms(context, swapchain.imageFormat(), swapchain.depthFormat(), textureCache);
            stream.setPrograms(programs);
            // Framebuffers the game creates after this point - the shader groups among them - get their attachments
            // from here. Their formats are the swapchain's, because every pipeline in this renderer is built for that
            // one format (see docs/vulkan-backend-plan.md 11.3 D1).
            renderTargets = new VulkanRenderTargets(context, textureCache, swapchain.imageFormat(),
                    swapchain.depthFormat());
            net.minecraftforge.client.render.target.RenderTargets.install(renderTargets);
            stream.setRenderTargets(renderTargets);
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
            // Mods draw through GL11, which still links against LWJGL3's own natives and would therefore reach real
            // GL - a context that does not exist - or, worse, silently change nothing. The retargeted call sites
            // reach this facade instead, and from here the same frame state vanilla writes to. Installed after the
            // frame state exists and before the first frame is drawn.
            net.minecraftforge.client.render.shim.ShimGl.setImpl(new net.minecraftforge.client.render.shim.FrameShim());
            LOGGER.info("Mod GL calls: routed to the frame state ({} entry points)",
                    net.minecraftforge.lwjgl.shim.FrameShimRules.INSTANCE.entries().size());
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
     * The vertices and fragment the program self-test compiles, written the way a mod would hand them over.
     *
     * <p>Deliberately GLSL a mod could write: a plain {@code uniform vec4 Tint} and a sampler with an explicit binding.
     * The plain uniform is what the preprocessor has to collect into a block - Vulkan has no loose uniforms - and the
     * explicit {@code (set = 1, binding = 0)} is the convention the game's own shaders follow for textures.</p>
     *
     * <p><b>The quad's y is Vulkan's, not the game's.</b> This shader writes clip coordinates straight from the vertex
     * position, so there is no projection matrix to convert: clip y=-1 is the top row of the window, which is why the
     * top-left vertex carries v=0. A shader of the game's that multiplies by {@code ProjMat} instead lands mirrored -
     * that is the orientation question the outline chain has to answer, and it is not one a self-test should guess
     * at.</p>
     */
    private static final String SELF_TEST_VERTEX = """
            #version 450
            layout(location = 0) in vec4 Position;
            layout(location = 2) in vec2 TexCoord;
            layout(location = 0) out vec2 vUv;
            void main() {
                gl_Position = vec4(Position.xy, 0.0, 1.0);
                vUv = TexCoord;
            }
            """;

    private static final String SELF_TEST_FRAGMENT = """
            #version 450
            uniform vec4 Tint;
            layout(set = 1, binding = 0) uniform sampler2D DiffuseSampler;
            layout(location = 0) in vec2 vUv;
            layout(location = 0) out vec4 fragColor;
            void main() {
                fragColor = vec4(texture(DiffuseSampler, vUv).rgb, 1.0) * Tint;
            }
            """;

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
        // Before the restore, so the count still describes this backend's run. Zero with mods installed means the
        // retargeted call sites are not reaching the facade, which is the silent half of this path's failure modes.
        if (net.minecraftforge.client.render.shim.FrameShim.routedCalls() > 0) {
            LOGGER.info("Mod GL calls: {} routed to the frame state",
                    net.minecraftforge.client.render.shim.FrameShim.routedCalls());
        }
        net.minecraftforge.client.render.shim.ShimGl.setImpl(null);
        net.minecraftforge.client.render.target.FrameRecordings.install(null);
        if (renderTargets != null) {
            // The game's framebuffer objects outlive the backend and are never deleted on quit, but their Vulkan
            // images are ours - so they are released here, before the device goes, or the validation layers report
            // them as leaked. See VulkanRenderTargets.close.
            net.minecraftforge.client.render.target.RenderTargets.uninstall(renderTargets);
            renderTargets.close();
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
        if (programs != null) {
            programs.close();
            programs = null;
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
        // Last, so the image counters have seen every destroy: the swapchain's, the textures', the targets'.
        LOGGER.info("{}", VulkanSwapchain.report());
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
