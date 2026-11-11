package net.minecraftforge.client.render.shader;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.minecraftforge.client.render.vulkan.shader.GlslCompiler;
import net.minecraftforge.client.render.vulkan.shader.GlslPreprocessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules a shipped module has to satisfy before it stands in for its source.
 *
 * <p>Each case here is a failure that would otherwise be invisible: a fragment module used as a vertex shader compiles
 * and links and then draws nothing, and a module adopted after its source was replaced runs GLSL that is not in the
 * pack. Both are refused rather than approximated.</p>
 */
class SpirvResourcesTest {

    @AfterAll
    static void releaseCompiler() {
        GlslCompiler.release();
    }

    @BeforeEach
    void reset() {
        GlShaders.reset();
    }

    @Test
    void aModuleOfTheSameStageIsAccepted() {
        byte[] module = compile("void main() { gl_Position = vec4(0.0); }", GlslCompiler.Stage.VERTEX);

        assertNull(SpirvResources.validate(module, GlShaders.VERTEX_SHADER));
    }

    @Test
    void aModuleOfTheOtherStageIsRefused() {
        byte[] module = compile("void main() { gl_FragColor = vec4(1.0); }", GlslCompiler.Stage.FRAGMENT);

        String problem = SpirvResources.validate(module, GlShaders.VERTEX_SHADER);

        assertNotNull(problem, "a fragment module cannot be a vertex shader");
        assertTrue(problem.contains("fragment"), "the reason names what it actually is: " + problem);
        assertTrue(problem.contains("vertex"), "and what it was expected to be");
    }

    @Test
    void somethingThatIsNotAModuleIsRefused() {
        assertNotNull(SpirvResources.validate(new byte[] {1, 2, 3}, GlShaders.FRAGMENT_SHADER));
        assertNotNull(SpirvResources.validate(null, GlShaders.FRAGMENT_SHADER));
        assertNotNull(SpirvResources.validate(new byte[40], GlShaders.FRAGMENT_SHADER),
                "zeros are not a module, whatever their length");
    }

    @Test
    void anAttachedModuleSurvivesTheCompileCall() {
        // This is the whole point: the GLSL is never compiled when a module was shipped.
        int shader = GlShaders.createShader(GlShaders.FRAGMENT_SHADER);
        GlShaders.shaderSource(shader, "#version 120\nvoid main() { this would not compile }");
        byte[] module = compile("void main() { gl_FragColor = vec4(0.5); }", GlslCompiler.Stage.FRAGMENT);

        GlShaders.attachModule(shader, module);
        GlShaders.compileShader(shader);

        assertEquals(1, GlShaders.shaderStatus(shader, GlShaders.COMPILE_STATUS),
                "the module is used without the broken source being compiled");
        assertEquals("", GlShaders.shaderLog(shader));
    }

    @Test
    void aFailedShaderIsNotSilentlyCompiledAfterwards() {
        int shader = GlShaders.createShader(GlShaders.FRAGMENT_SHADER);
        GlShaders.shaderSource(shader, "#version 120\nvoid main() { gl_FragColor = vec4(1.0); }");

        GlShaders.failShader(shader, "module-broken.fsh.spv is not a SPIR-V module");

        assertEquals(0, GlShaders.shaderStatus(shader, GlShaders.COMPILE_STATUS));
        assertTrue(GlShaders.shaderLog(shader).contains("module-broken"), "the reason names the file");
    }

    private static byte[] compile(String body, GlslCompiler.Stage stage) {
        String source = "#version 120\n" + body;
        return GlslCompiler.compile(GlslPreprocessor.forVulkan(source, stage), stage, "test", true);
    }
}
