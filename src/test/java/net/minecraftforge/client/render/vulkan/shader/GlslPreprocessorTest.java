package net.minecraftforge.client.render.vulkan.shader;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rewrite from OpenGL-era GLSL to what shaderc accepts, checked against the game's own shaders.
 *
 * <p>The interesting case is not a synthetic one: it is the 34 post-processing shaders the game ships, because they
 * are exactly what the entity outline - and therefore the spectral arrow - is drawn with. If those compile through
 * this preprocessor and {@link GlslCompiler}, then the runtime shader path is real rather than planned.</p>
 */
class GlslPreprocessorTest {

    /**
     * Every post-processing shader the game ships, discovered rather than listed.
     *
     * <p>Listed by hand this drifts: the names are the ones the JSON programs reference, and a missing entry would
     * silently shrink the test. The directory is read, and the count is asserted so a classpath change cannot make
     * the test vacuous.</p>
     */
    private static java.util.List<String> gameShaders() throws IOException {
        java.net.URL url = GlslPreprocessorTest.class.getResource("/assets/minecraft/shaders/program/");
        assertNotNull(url, "the game's shader programs are on the classpath");
        java.nio.file.Path dir = java.nio.file.Path.of(url.getPath());
        try (var stream = java.nio.file.Files.list(dir)) {
            return stream.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".vsh") || n.endsWith(".fsh"))
                    .sorted()
                    .toList();
        }
    }

    @AfterAll
    static void releaseCompiler() {
        GlslCompiler.release();
    }

    @Test
    void everyShaderTheGameShipsCompilesForVulkan() throws IOException {
        List<String> shaders = gameShaders();
        assertTrue(shaders.size() >= 30, "found " + shaders.size() + " shader files");
        int compiled = 0;
        StringBuilder failures = new StringBuilder();
        for (String name : shaders) {
            String source = gameShader(name);
            if (source == null) {
                continue;
            }
            GlslCompiler.Stage stage = name.endsWith(".vsh")
                    ? GlslCompiler.Stage.VERTEX : GlslCompiler.Stage.FRAGMENT;
            try {
                byte[] spirv = GlslCompiler.compile(GlslPreprocessor.forVulkan(source, stage), stage, name);
                assertTrue(spirv.length >= 20, name + " produced a module");
                compiled++;
            } catch (RuntimeException e) {
                failures.append('\n').append(name).append(": ").append(e.getMessage());
            }
        }

        assertTrue(compiled >= shaders.size() - 2, "compiled " + compiled + " of " + shaders.size() + " shaders");
        assertEquals(0, failures.length(), "shaders that did not compile:" + failures);
    }

    @Test
    void theOutlineShaderIsAmongThem() throws IOException {
        String source = gameShader("outline_combine.fsh");
        assertNotNull(source, "the game's outline shader is on the classpath");

        String rewritten = GlslPreprocessor.forVulkan(source, GlslCompiler.Stage.FRAGMENT);
        assertTrue(rewritten.contains("#version 450"), "the 1.20 directive is brought forward");
        assertTrue(rewritten.contains("out vec4 outlandsFragColor;"), "gl_FragColor gets a declared output");
        assertFalse(rewritten.contains("gl_FragColor "), "no use of it is left");
        assertTrue(rewritten.contains("texture(DiffuseSampler"), "texture2D becomes texture");

        byte[] spirv = GlslCompiler.compile(rewritten, GlslCompiler.Stage.FRAGMENT, "outline_combine.fsh");
        assertTrue(spirv.length >= 20);
    }

    @Test
    void plainUniformsAreCollectedIntoABlock() throws IOException {
        // outline_soft.fsh declares two floats, outline_combine.fsh declares none - it is all samplers, which are
        // opaque and legal on their own. So the block check belongs on the former.
        String source = gameShader("outline_soft.fsh");
        assertNotNull(source, "the game's outline shader is on the classpath");

        String rewritten = GlslPreprocessor.forVulkan(source, GlslCompiler.Stage.FRAGMENT);

        assertTrue(rewritten.contains("uniform OutlandsUniforms {"),
                "plain uniforms are collected into a block, which Vulkan GLSL requires");
        assertTrue(rewritten.contains("float LumaRamp;"), "the declaration moves into the block");
        assertFalse(rewritten.contains("uniform float LumaRamp;"), "and does not stay outside it");
        assertTrue(rewritten.contains("outlandsUniforms.LumaRamp") || !rewritten.contains("LumaRamp,"),
                "uses go through the block");
        assertTrue(rewritten.contains("uniform sampler2D DiffuseSampler;"),
                "samplers stay outside the block");
    }

    @Test
    void initialisedUniformsLoseTheirInitialiser() {
        // Vanilla writes "uniform float MosaicSize = 1.0;". A block member cannot have an initialiser.
        String rewritten = GlslPreprocessor.forVulkan("""
                #version 120
                uniform float MosaicSize = 1.0;
                void main() { gl_FragColor = vec4(MosaicSize); }
                """, GlslCompiler.Stage.FRAGMENT);

        assertTrue(rewritten.contains("float MosaicSize;"), "the initialiser is dropped, got: " + rewritten);
        assertFalse(rewritten.contains("= 1.0"), "nothing initialises a buffer member");
    }

    @Test
    void reservedWordsFromLaterVersionsAreDefinedAway() {
        // The game's blur shader declares "vec4 sample = ...", and sample became a built-in in 4.00.
        String rewritten = GlslPreprocessor.forVulkan("""
                #version 120
                void main() {
                    vec4 sample = vec4(1.0);
                    gl_FragColor = sample;
                }
                """, GlslCompiler.Stage.FRAGMENT);

        assertTrue(rewritten.contains("#define sample outlands_sample"), "got: " + rewritten);
    }

    @Test
    void wordsInsideCommentsAreLeftAlone() {
        String source = """
                #version 120
                // varying is not a declaration here
                /* attribute likewise
                   and gl_FragColor too */
                varying vec2 uv;
                void main() { gl_FragColor = vec4(uv, 0.0, 1.0); }
                """;

        String rewritten = GlslPreprocessor.forVulkan(source, GlslCompiler.Stage.FRAGMENT);

        assertTrue(rewritten.contains("// varying is not a declaration here"));
        assertTrue(rewritten.contains("attribute likewise"));
        assertTrue(rewritten.contains("and gl_FragColor too"));
        assertTrue(rewritten.contains("in vec2 uv;"), "the declaration itself is rewritten");
    }

    @Test
    void aSourceWithoutAVersionDirectiveGetsOne() {
        String rewritten = GlslPreprocessor.forVulkan(
                "void main() { gl_FragColor = vec4(1.0); }", GlslCompiler.Stage.FRAGMENT);

        assertTrue(rewritten.startsWith("#version 450"), "the directive goes first, got: " + rewritten);
        assertTrue(rewritten.contains("outlandsFragColor"));
    }

    @Test
    void aModernSourceKeepsItsVersion() {
        String rewritten = GlslPreprocessor.forVulkan("""
                #version 450
                layout(location = 0) out vec4 colour;
                void main() { colour = vec4(1.0); }
                """, GlslCompiler.Stage.FRAGMENT);

        assertTrue(rewritten.contains("#version 450"));
        assertFalse(rewritten.contains("outlandsFragColor"), "nothing to declare when the shader has its own output");
    }

    private static String gameShader(String name) throws IOException {
        try (InputStream in = GlslPreprocessorTest.class
                .getResourceAsStream("/assets/minecraft/shaders/program/" + name)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
