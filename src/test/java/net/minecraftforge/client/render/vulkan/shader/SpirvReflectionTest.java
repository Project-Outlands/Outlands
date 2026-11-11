package net.minecraftforge.client.render.vulkan.shader;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the shader modules say about themselves, read back the way a GL implementation has to.
 *
 * <p>Every assertion here is something the facade will ask: which binding is {@code DiffuseSampler} on, what offset is
 * {@code LumaRamp} at, how many bytes is a {@code mat4} member. If these are wrong the pipeline layout and the uniform
 * writes are wrong, and the shader will read garbage rather than fail.</p>
 */
class SpirvReflectionTest {

    @AfterAll
    static void releaseCompiler() {
        GlslCompiler.release();
    }

    @Test
    void theOutlinePassesExposeTheirSamplersByName() throws IOException {
        SpirvReflection.Module module = reflect("outline_combine.fsh");

        assertNotNull(module.sampler("DiffuseSampler"), "the game looks this up by name, got " + module.samplers());
        assertNotNull(module.sampler("OutlineSampler"), "and this, got " + module.samplers());
        assertEquals(2, module.samplers().size(), "the pass reads exactly two textures");
        assertTrue(module.sampler("DiffuseSampler").binding() != module.sampler("OutlineSampler").binding(),
                "two samplers cannot share a binding");
    }

    @Test
    void blockMembersKeepTheirNamesOffsetsAndSizes() throws IOException {
        SpirvReflection.Module module = reflect("outline_soft.fsh");

        SpirvReflection.Block block = module.block("outlandsUniforms");
        assertNotNull(block, "the rewritten plain uniforms live in this block, got " + module.blocks());
        assertEquals(0, block.offsetOf("LumaRamp"), "first float at offset 0");
        assertEquals(4, block.offsetOf("LumaLevel"), "second float follows it");
        assertEquals(8, block.size(), "two floats");
        assertEquals(4, block.members().get(0).size());
    }

    @Test
    void matrixAndVectorMembersCarryTheirRealSizes() throws IOException {
        // sobel.vsh is the vertex half of the outline: ProjMat, InSize, OutSize.
        SpirvReflection.Module module = reflect("sobel.vsh");

        SpirvReflection.Block block = module.block("outlandsUniforms");
        assertNotNull(block, "got " + module.blocks());
        assertEquals(0, block.offsetOf("ProjMat"));
        assertEquals(64, block.members().get(0).size(), "a mat4 is 64 bytes");
        assertEquals(64, block.offsetOf("InSize"));
        assertEquals(8, block.members().get(1).size(), "a vec2 is 8 bytes");
        assertEquals(72, block.offsetOf("OutSize"));
        assertEquals(80, block.size(), "the block rounds up past the last member");
    }

    @Test
    void everyShaderTheGameShipsReflects() throws IOException {
        List<String> shaders = gameShaders();
        assertTrue(shaders.size() >= 30, "found " + shaders.size());
        StringBuilder failures = new StringBuilder();
        for (String name : shaders) {
            try {
                SpirvReflection.Module module = reflect(name);
                for (SpirvReflection.Sampler sampler : module.samplers()) {
                    assertTrue(sampler.binding() >= 0, name + ": " + sampler.name() + " has a binding");
                }
            } catch (RuntimeException e) {
                failures.append('\n').append(name).append(": ").append(e.getMessage());
            }
        }
        assertEquals(0, failures.length(), "modules that did not reflect:" + failures);
    }

    @Test
    void aModuleWithNoUniformsReflectsToNothing() {
        byte[] spirv = GlslCompiler.compile("""
                #version 450
                layout(location = 0) out vec4 colour;
                void main() { colour = vec4(1.0); }
                """, GlslCompiler.Stage.FRAGMENT, "plain.frag");

        SpirvReflection.Module module = SpirvReflection.reflect(spirv);

        assertTrue(module.samplers().isEmpty());
        assertTrue(module.blocks().isEmpty());
    }

    private static SpirvReflection.Module reflect(String name) throws IOException {
        String source = gameShader(name);
        assertNotNull(source, name + " is on the classpath");
        GlslCompiler.Stage stage = name.endsWith(".vsh")
                ? GlslCompiler.Stage.VERTEX : GlslCompiler.Stage.FRAGMENT;
        byte[] spirv = GlslCompiler.compile(GlslPreprocessor.forVulkan(source, stage), stage, name);
        return SpirvReflection.reflect(spirv);
    }

    private static String gameShader(String name) throws IOException {
        try (InputStream in = SpirvReflectionTest.class
                .getResourceAsStream("/assets/minecraft/shaders/program/" + name)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> gameShaders() throws IOException {
        java.net.URL url = SpirvReflectionTest.class.getResource("/assets/minecraft/shaders/program/");
        assertNotNull(url, "the game's shader programs are on the classpath");
        try (var stream = Files.list(Path.of(url.getPath()))) {
            return stream.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".vsh") || n.endsWith(".fsh"))
                    .sorted()
                    .toList();
        }
    }
}
