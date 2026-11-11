package net.minecraftforge.client.render.vulkan.shader;

import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;

import static org.lwjgl.util.shaderc.Shaderc.shaderc_compilation_status_success;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_initialize;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_release;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_env_version_vulkan_1_2;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_fragment_shader;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_vertex_shader;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_into_spv;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_initialize;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_auto_bind_uniforms;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_generate_debug_info;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_auto_map_locations;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_release;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_optimization_level;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_target_env;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_optimization_level_performance;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_bytes;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_compilation_status;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_error_message;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_release;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_target_env_vulkan;

/**
 * Compiles GLSL to SPIR-V in the running process.
 *
 * <p><b>Why this exists at runtime and not only at build time.</b> The loader's own shaders are compiled ahead of time
 * and committed next to their sources - that is still the right thing for them. Mods are the reason this has to exist
 * here: a shader pack, or a mod that goes through CCL's shader entry point, hands over GLSL as a string while the
 * game is running, and the only way to run it on this backend is to compile it then. Vanilla's own post-processing
 * and the entity outline arrive the same way, through {@code glShaderSource}.</p>
 *
 * <p><b>shaderc, not a handwritten compiler.</b> It is the same compiler the build uses, so a shader compiled here
 * and the same shader compiled into the resources behave identically. It ships as an LWJGL module with natives, which
 * means it arrives with the rest of LWJGL rather than adding a build tool to the user's machine.</p>
 *
 * <p><b>Serialised.</b> A shaderc compiler instance is not safe to use from two threads at once, and the cost of that
 * safety is irrelevant: compilation happens when a shader is first used, not per frame.</p>
 */
public final class GlslCompiler {

    /** Which stage a source is. */
    public enum Stage {
        VERTEX,
        FRAGMENT;

        int kind() {
            return switch (this) {
                case VERTEX -> shaderc_glsl_vertex_shader;
                case FRAGMENT -> shaderc_glsl_fragment_shader;
            };
        }
    }

    /** SPIR-V's magic number, the first word of a module, in native byte order. */
    private static final int SPIRV_MAGIC = 0x07230203;

    private static long compiler;
    private static final Object LOCK = new Object();

    private GlslCompiler() {}

    /**
     * Compiles {@code source} into a SPIR-V module.
     *
     * @param source the GLSL text, exactly as it was handed to {@code glShaderSource}
     * @param stage  which stage it is
     * @param name   what to call it in error messages - the shader's own name, not the file it came from
     * @return the module's bytes, ready for {@code vkCreateShaderModule}
     * @throws GlslCompileException when the source does not compile, carrying the compiler's message. Callers that are
     *                              answering GL must report this the way GL does - a failed compile is not a crash -
     *                              so it is a checked-style exception rather than an IllegalState.
     */
    public static byte[] compile(String source, Stage stage, String name) {
        return compile(source, stage, name, true);
    }

    /**
     * Compiles, optionally without the name table.
     *
     * <p>Names cost size - about twice the module for the loader's own shaders - and only the GL facade needs them,
     * because only it has to answer {@code glGetUniformLocation} by name. A shader this project refers to by explicit
     * binding does not.</p>
     */
    public static byte[] compile(String source, Stage stage, String name, boolean withNames) {
        if (source == null) {
            throw new IllegalArgumentException("shader source is null");
        }
        long options = shaderc_compile_options_initialize();
        if (options == 0L) {
            throw new IllegalStateException("shaderc_compile_options_initialize returned 0");
        }
        long result = 0L;
        try {
            // Vulkan 1.2 is the floor this backend targets for shader capability - it is what descriptor indexing and
            // the spirv-val rules in use here assume - and it is well below the 1.4 the loader's own shaders are
            // compiled against, so a module that works here works there too.
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan,
                    shaderc_env_version_vulkan_1_2);
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);
            // These two are what make GL-style GLSL compile at all. GL binds attributes and uniforms by name at link
            // time; SPIR-V has no such thing, so every input, output and uniform needs an explicit location or
            // binding. Asking shaderc to assign them is the same choice the Vulkan samples make for OpenGL-style
            // sources, and it is what lets an unmodified - or minimally modified - mod shader through.
            shaderc_compile_options_set_auto_map_locations(options, true);
            shaderc_compile_options_set_auto_bind_uniforms(options, true);
            // Names, and this is not a debugging nicety: GL looks uniforms up by name, and without debug info the
            // module carries an empty name table, so a program would have no way to answer glGetUniformLocation.
            // Measured: without this flag the module has no OpName or OpMemberName instruction at all.
            if (withNames) {
                shaderc_compile_options_set_generate_debug_info(options);
            }

            synchronized (LOCK) {
                if (compiler == 0L) {
                    compiler = shaderc_compiler_initialize();
                    if (compiler == 0L) {
                        throw new IllegalStateException("shaderc_compiler_initialize returned 0");
                    }
                }
                result = shaderc_compile_into_spv(compiler, source, stage.kind(), name, "main", options);
            }
            if (result == 0L) {
                throw new IllegalStateException("shaderc returned no result for shader " + name);
            }
            if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                throw new GlslCompileException(name, shaderc_result_get_error_message(result));
            }
            ByteBuffer spirv = shaderc_result_get_bytes(result);
            byte[] bytes = new byte[spirv.remaining()];
            spirv.get(bytes);
            if (bytes.length < 4 || readInt(bytes) != SPIRV_MAGIC) {
                throw new IllegalStateException("shaderc produced " + bytes.length
                        + " bytes for shader " + name + " that are not a SPIR-V module");
            }
            return bytes;
        } finally {
            if (result != 0L) {
                shaderc_result_release(result);
            }
            shaderc_compile_options_release(options);
        }
    }

    /** Whether the native compiler is present, which a dependency problem rather than a shader problem decides. */
    public static boolean available() {
        synchronized (LOCK) {
            return compiler != 0L;
        }
    }

    /** Releases the native compiler. Called when the backend shuts down; compiling again after this starts a new one. */
    public static void release() {
        synchronized (LOCK) {
            if (compiler != 0L) {
                shaderc_compiler_release(compiler);
                compiler = 0L;
            }
        }
    }

    private static int readInt(byte[] bytes) {
        return (bytes[0] & 0xFF) | ((bytes[1] & 0xFF) << 8) | ((bytes[2] & 0xFF) << 16) | ((bytes[3] & 0xFF) << 24);
    }

    /** A shader that does not compile, with the compiler's own message. */
    public static final class GlslCompileException extends RuntimeException {
        private final String shaderName;
        private final String log;

        GlslCompileException(String shaderName, String log) {
            super(shaderName + " does not compile: " + log);
            this.shaderName = shaderName;
            this.log = log == null ? "" : log;
        }

        public String shaderName() {
            return shaderName;
        }

        /** The compiler's message, which names the line and the reason. */
        public String log() {
            return log;
        }
    }
}
