package net.minecraftforge.client.render.shader;

import net.minecraftforge.client.render.vulkan.shader.GlslCompiler;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code glShaderSource} on a shader that has already compiled, and what a linked program keeps.
 *
 * <p><b>The two halves of GL's rule.</b> A shader object's module belongs to the text it was compiled from, so replacing
 * the text means the next compile compiles the new text - otherwise a recompile is a silent no-op that reports success.
 * A linked program's executable, on the other hand, is fixed when it links: recompiling an attached stage changes the
 * stage, and the program keeps drawing with what it linked until it is linked again.</p>
 *
 * <p><b>Why it is worth pinning.</b> CodeChickenLib's {@code ShaderProgram} is the shape that hits this: it caches
 * uniform <i>values</i> and only uploads a uniform whose value changed, and it invalidates that cache only on link. If a
 * recompiled stage reached a program that was never re-linked, the program would draw with the new module and the old
 * uniform table - a wrong picture with a successful compile status, which is the failure mode this project has spent the
 * most time on. Measured: no mod in the pack calls the recompile path today, so the only thing keeping it honest is
 * this test.</p>
 */
class ShaderRecompileTest {

    private static final String VERTEX_WITH_A = """
            #version 120
            attribute vec4 Position;
            uniform vec4 uA;
            void main() { gl_Position = Position + uA; }
            """;

    private static final String VERTEX_WITH_B = """
            #version 120
            attribute vec4 Position;
            uniform vec4 uB;
            void main() { gl_Position = Position + uB; }
            """;

    private static final String FRAGMENT = """
            #version 120
            void main() { gl_FragColor = vec4(1.0); }
            """;

    @AfterAll
    static void releaseCompiler() {
        GlslCompiler.release();
    }

    @BeforeEach
    void reset() {
        GlShaders.reset();
    }

    @Test
    void newTextIsCompiledByTheNextCompileCall() {
        int vertex = GlShaders.createShader(GlShaders.VERTEX_SHADER);
        GlShaders.shaderSource(vertex, VERTEX_WITH_A);
        GlShaders.compileShader(vertex);
        assertEquals(1, GlShaders.shaderStatus(vertex, GlShaders.COMPILE_STATUS));

        GlShaders.shaderSource(vertex, VERTEX_WITH_B);
        assertEquals(0, GlShaders.shaderStatus(vertex, GlShaders.COMPILE_STATUS),
                "a module belongs to the text it was compiled from, so new text is not compiled yet");

        GlShaders.compileShader(vertex);
        assertEquals(1, GlShaders.shaderStatus(vertex, GlShaders.COMPILE_STATUS),
                "and the next compile call compiles the new text rather than returning early");

        int program = GlShaders.createProgram();
        GlShaders.attachShader(program, vertex);
        GlShaders.attachShader(program, shader(GlShaders.FRAGMENT_SHADER, FRAGMENT));
        GlShaders.linkProgram(program);
        assertNotNull(GlShaders.program(program).uniform("uB"), "the new text's uniform is the one that linked");
        assertNull(GlShaders.program(program).uniform("uA"), "and the old text's is gone");
    }

    @Test
    void theSameTextKeepsTheModuleItWasGiven() {
        // A resource reload hands the same text to a new shader object, and a precompiled module is attached beside it
        // afterwards. Re-setting the text must not throw the module away: for the shaders this project replaces, the
        // module is the only thing that can run, because the source beside it is not compilable at all.
        int vertex = GlShaders.createShader(GlShaders.VERTEX_SHADER);
        GlShaders.shaderSource(vertex, VERTEX_WITH_A);
        GlShaders.attachModule(vertex, new byte[] {1, 2, 3});
        assertTrue(GlShaders.shaderStatus(vertex, GlShaders.COMPILE_STATUS) == 1, "the module stands in for a compile");

        GlShaders.shaderSource(vertex, VERTEX_WITH_A);
        assertEquals(1, GlShaders.shaderStatus(vertex, GlShaders.COMPILE_STATUS),
                "the same text means the same module, so it is still compiled");

        GlShaders.shaderSource(vertex, VERTEX_WITH_B);
        assertEquals(0, GlShaders.shaderStatus(vertex, GlShaders.COMPILE_STATUS),
                "different text means the module is about nothing that is here now");
    }

    @Test
    void aLinkedProgramKeepsWhatItLinkedWith() {
        int vertex = shader(GlShaders.VERTEX_SHADER, VERTEX_WITH_A);
        int program = GlShaders.createProgram();
        GlShaders.attachShader(program, vertex);
        GlShaders.attachShader(program, shader(GlShaders.FRAGMENT_SHADER, FRAGMENT));
        GlShaders.linkProgram(program);

        GlShaders.Program linked = GlShaders.program(program);
        byte[] asLinked = linked.stageModule(GlShaders.VERTEX_SHADER);
        assertNotNull(linked.uniform("uA"));

        // The stage is recompiled from new text, and the program is not linked again - GL keeps drawing with what it
        // linked, so the module and the uniform table both have to stay as they were.
        GlShaders.shaderSource(vertex, VERTEX_WITH_B);
        GlShaders.compileShader(vertex);
        assertEquals(1, GlShaders.shaderStatus(vertex, GlShaders.COMPILE_STATUS));

        assertArrayEquals(asLinked, linked.stageModule(GlShaders.VERTEX_SHADER),
                "the program draws with the module it linked, not with a later recompile of the stage");
        assertNotNull(linked.uniform("uA"), "and its uniform table is the one that link produced");
        assertNull(linked.uniform("uB"));

        // Linking again is what makes the new text take effect.
        GlShaders.linkProgram(program);
        assertNotNull(linked.uniform("uB"), "a re-link picks the new stage up");
        assertNull(linked.uniform("uA"));
        assertFalse(Arrays.equals(asLinked, linked.stageModule(GlShaders.VERTEX_SHADER)),
                "and its module is the one compiled from the new text");
    }

    private static int shader(int type, String source) {
        int id = GlShaders.createShader(type);
        GlShaders.shaderSource(id, source);
        GlShaders.compileShader(id);
        return id;
    }
}
