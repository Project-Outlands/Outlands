package net.minecraftforge.client.render;

import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;

/**
 * Answers the two questions the game asks <em>about</em> GL rather than <em>through</em> it: which feature level
 * exists, and what the renderer calls itself.
 *
 * <p><b>Why these two are not shim work.</b> Both are questions a backend can answer truthfully. A Vulkan
 * backend has no GL context, so asking the driver is not merely undesirable - it dereferences a null function
 * pointer and takes the process down (measured: {@code Java_org_lwjgl_opengl_GL11C_nglGetString} SIGSEGV during
 * {@code OpenGlHelper.initializeTextures}). But the <em>answers</em> still exist: the backend knows which feature
 * level it implements and what to call itself.</p>
 *
 * <p><b>Why answering is not a lie.</b> Vanilla branches on the capability flags: {@code useVbo()} decides
 * between VBOs and display lists, {@code shadersSupported} decides whether post-processing exists. Those branches
 * must agree with what the backend actually implements, or vanilla takes a path nobody built. So the declared
 * table is not "pretending a context exists", it is the backend's contract stated in the only vocabulary vanilla
 * understands - see {@code docs/vulkan-backend-plan.md} §8.4.1 bucket A.</p>
 *
 * <p><b>The table must be exactly the shim contract.</b> §8.4 fixes that contract at GL 2.1 plus
 * {@code ARB_framebuffer_object}. Declaring more makes vanilla choose paths that are not implemented; declaring
 * less makes it choose the degraded paths (display lists, no shaders) that are not implemented either. The two
 * have to match, so both are stated once, here and in {@link #declareShimContract()}.</p>
 */
public final class GlAnswers {

    private static final int GL_VENDOR = 7936;
    private static final int GL_RENDERER = 7937;
    private static final int GL_VERSION = 7938;

    private GlAnswers() {}

    /**
     * The capability table declared by the active non-GL backend, falling back to the shim contract.
     *
     * <p>Only reached when there is no GL context to ask. A GL backend keeps the table it always had, cached by
     * {@link GLContext#getCapabilities()}.</p>
     */
    public static ContextCapabilities capabilities() {
        ContextCapabilities declared = RenderBackendSelection.get().glCapabilities();
        return declared != null ? declared : declareShimContract();
    }

    /**
     * An identification string, or {@code null} when the backend declines to answer.
     *
     * <p>Callers are expected to tolerate {@code null}: these strings are printed, never parsed by the game
     * itself.</p>
     */
    public static @Nullable String glString(int name) {
        RenderBackend backend = RenderBackendSelection.get();
        if (backend.isOpenGl()) {
            return GL11.glGetString(name);
        }
        String answer = backend.glString(name);
        if (answer != null) {
            return answer;
        }
        return switch (name) {
            case GL_VENDOR -> "Cleanroom";
            case GL_RENDERER -> "Cleanroom " + backend.id() + " backend";
            // Deliberately shaped like a GL version: anything that splits this string expects that shape, and the
            // number it will read is the one the shim contract promises.
            case GL_VERSION -> "2.1 Cleanroom (" + backend.id() + ")";
            default -> null;
        };
    }

    /**
     * The capability table implied by the shim contract: GL 2.1 plus {@code ARB_framebuffer_object}.
     *
     * <p>Public because the Vulkan backend hands this back rather than describing the same set twice.</p>
     */
    public static ContextCapabilities declareShimContract() {
        // blank(), not new ContextCapabilities(): the latter calls GL.getCapabilities() and there is no
        // context to ask.
        ContextCapabilities caps = ContextCapabilities.blank();
        // 1.3 - multitexture, and the GL path to the texture env combiner.
        caps.OpenGL13 = true;
        // 1.4 - blend_func_separate, which vanilla requires before it will use framebuffers at all.
        caps.OpenGL14 = true;
        // 1.5 - buffer objects. Answering false here selects display lists, which no backend implements.
        caps.OpenGL15 = true;
        // 2.0 / 2.1, which is what §8.4 fixes the contract at: vanilla's post-processing is GLSL 1.20, and every
        // one of the game's five program files is now answered by a module this project ships.
        //
        // This was the last switch to turn: while it was off, RenderGlobal never built its entity-outline ShaderGroup
        // and the world had no outlines at all. What made it safe to turn on is the chain, not the declaration -
        // GlShaders keeping GL's object model, the game's programs shipping as AOT modules, the program draw path,
        // and offscreen targets that can be sampled inside the frame that painted them.
        caps.OpenGL20 = true;
        caps.OpenGL21 = true;
        // The ARB forms of the same three, because vanilla still takes the extension branch when it finds one: with
        // them false, ShaderLinkHelper installs nothing and every ShaderGroup fails on its first bind, which is how
        // an outline pass turns into an exception in the middle of a frame.
        caps.GL_ARB_shader_objects = true;
        caps.GL_ARB_vertex_shader = true;
        caps.GL_ARB_fragment_shader = true;
        // The one extension the contract names: vanilla's Framebuffer needs it below GL 3.0.
        caps.GL_ARB_framebuffer_object = true;
        caps.GL_EXT_blend_func_separate = true;
        // Extensions vanilla's own init asks about when the core version is older; they are implied by 1.3+ but
        // answering them keeps the ARB fallback branches from being taken.
        caps.GL_ARB_multitexture = true;
        caps.GL_ARB_texture_env_combine = true;
        caps.GL_ARB_vertex_buffer_object = true;
        return caps;
    }
}
