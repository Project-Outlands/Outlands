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
    };

    private ShaderCompiler() {}

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : ".").resolve(SOURCE_DIR);
        for (String[] shader : SHADERS) {
            Path source = root.resolve(shader[0]);
            Path output = root.resolve(shader[0] + ".spv");
            byte[] spirv = net.minecraftforge.client.render.vulkan.shader.GlslCompiler.compile(
                    Files.readString(source), stage(shader[1]), shader[0], false);
            Files.write(output, spirv);
            System.out.println("compiled " + shader[0] + " -> " + output.getFileName()
                    + " (" + spirv.length + " bytes)");
        }
        net.minecraftforge.client.render.vulkan.shader.GlslCompiler.release();
    }

    private static net.minecraftforge.client.render.vulkan.shader.GlslCompiler.Stage stage(String name) {
        return switch (name) {
            case "vertex" -> net.minecraftforge.client.render.vulkan.shader.GlslCompiler.Stage.VERTEX;
            case "fragment" -> net.minecraftforge.client.render.vulkan.shader.GlslCompiler.Stage.FRAGMENT;
            default -> throw new IllegalArgumentException("unsupported stage: " + name);
        };
    }
}
