package net.minecraftforge.client.render.vulkan.shader;

import org.lwjgl.util.shaderc.Shaderc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.util.shaderc.Shaderc.shaderc_compilation_status_success;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_initialize;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_release;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_env_version_vulkan_1_4;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_fragment_shader;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_vertex_shader;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_into_spv;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_initialize;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_release;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_optimization_level;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_target_env;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_bytes;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_compilation_status;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_error_message;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_release;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_optimization_level_performance;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_target_env_vulkan;

/**
 * Compiles the loader's own GLSL into the SPIR-V that ships next to it.
 *
 * <p><b>Build-time, not run time.</b> The backend consumes SPIR-V and nothing else - the same thing it will
 * consume from a shader pack - so there is one shader path in the running process, not two. This tool exists so
 * that producing that SPIR-V does not require a contributor to install a Vulkan SDK, and so that the sources stay
 * readable in the tree next to the binaries they produce.</p>
 *
 * <p>Run by the {@code compileShaders} Gradle task. The generated {@code .spv} files are committed: a build
 * without this tool still works, which matters for CI and for anyone who only wants to touch Java.</p>
 */
public final class ShaderCompiler {

    /** Where the sources live, relative to the project directory. */
    private static final String SOURCE_DIR = "src/main/resources/assets/outlands/shaders";

    /** name -> shader stage kind. */
    private static final String[][] SHADERS = {
            {"terrain.vert", "vertex"},
            {"terrain.frag", "fragment"},
            {"stream.vert", "vertex"},
            {"stream.frag", "fragment"},
    };

    private ShaderCompiler() {}

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : ".").resolve(SOURCE_DIR);
        long compiler = shaderc_compiler_initialize();
        if (compiler == 0L) {
            throw new IllegalStateException("shaderc_compiler_initialize returned 0");
        }
        try {
            for (String[] shader : SHADERS) {
                compile(compiler, root, shader[0], shader[1]);
            }
        } finally {
            shaderc_compiler_release(compiler);
        }
    }

    private static void compile(long compiler, Path root, String file, String stage) throws IOException {
        Path source = root.resolve(file);
        Path output = root.resolve(file + ".spv");
        String text = Files.readString(source);

        long options = shaderc_compile_options_initialize();
        if (options == 0L) {
            throw new IllegalStateException("shaderc_compile_options_initialize returned 0");
        }
        long result = 0L;
        try {
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan,
                    shaderc_env_version_vulkan_1_4);
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);

            result = shaderc_compile_into_spv(compiler, text, kind(stage), source.getFileName().toString(),
                    "main", options);
            if (result == 0L) {
                throw new IllegalStateException("shaderc returned no result for " + file);
            }
            if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                throw new IllegalStateException(file + ": " + shaderc_result_get_error_message(result));
            }
            ByteBuffer spirv = shaderc_result_get_bytes(result);
            byte[] bytes = new byte[spirv.remaining()];
            spirv.get(bytes);
            Files.write(output, bytes);
            System.out.println("compiled " + file + " -> " + output.getFileName() + " (" + bytes.length + " bytes)");
        } finally {
            if (result != 0L) {
                shaderc_result_release(result);
            }
            shaderc_compile_options_release(options);
        }
    }

    private static int kind(String stage) {
        return switch (stage) {
            case "vertex" -> shaderc_glsl_vertex_shader;
            case "fragment" -> shaderc_glsl_fragment_shader;
            default -> throw new IllegalArgumentException("unsupported stage: " + stage);
        };
    }
}
