package net.minecraftforge.client.render.shader;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which programs read the push-constant matrix, because that decides the clip convention their draws need.
 *
 * <p>The block holds the stream's own matrix - already negated for Vulkan's clip space - so a program that reads it has
 * done the inversion the stream path does, and must not also be drawn with the program path's opposite viewport flip.
 * Getting this wrong is not subtle to look at but is invisible in code: the geometry comes out mirrored across the
 * middle of the screen. Measured: a submachinegun's magazine appeared as a belt of cartridges against the top-right
 * edge instead of under the gun.</p>
 */
class FixedFunctionMatrixFlagTest {

    @BeforeEach
    void resetTheFacade() {
        GlShaders.reset();
    }

    @Test
    void aShaderThatReadsTheModelViewProjectionMatrixIsFlagged() {
        assertTrue(link("""
                #version 120
                void main() {
                    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
                }
                """).usesFixedFunctionMatrix());
    }

    @Test
    void ftransformCountsToo() {
        assertTrue(link("""
                #version 120
                void main() {
                    gl_Position = ftransform();
                }
                """).usesFixedFunctionMatrix());
    }

    @Test
    void aShaderWithItsOwnMatrixIsNot() {
        assertFalse(link("""
                #version 130
                uniform mat4 ProjMat;
                in vec3 Position;
                void main() {
                    gl_Position = ProjMat * vec4(Position, 1.0);
                }
                """).usesFixedFunctionMatrix());
    }

    @Test
    void aFragmentOnlyProgramIsNot() {
        // It gets the fixed-function vertex stage, which writes the matrix - but the flag is about what the mod's own
        // source asked for, and this one asked for nothing. Its draws keep the program path's convention.
        GlShaders.reset();
        int program = GlShaders.createProgram();
        int fragment = GlShaders.createShader(GlShaders.FRAGMENT_SHADER);
        GlShaders.shaderSource(fragment, """
                #version 130
                void main() {
                    gl_FragColor = vec4(1.0);
                }
                """);
        GlShaders.compileShader(fragment);
        GlShaders.attachShader(program, fragment);
        GlShaders.linkProgram(program);
        assertEquals(1, GlShaders.programStatus(program, GlShaders.LINK_STATUS),
                GlShaders.programLog(program));
        assertFalse(GlShaders.program(program).usesFixedFunctionMatrix());
    }

    private static GlShaders.Program link(String vertexSource) {
        int program = GlShaders.createProgram();
        int vertex = GlShaders.createShader(GlShaders.VERTEX_SHADER);
        GlShaders.shaderSource(vertex, vertexSource);
        GlShaders.compileShader(vertex);
        assertEquals(1, GlShaders.shaderStatus(vertex, GlShaders.COMPILE_STATUS),
                GlShaders.shaderLog(vertex));
        int fragment = GlShaders.createShader(GlShaders.FRAGMENT_SHADER);
        GlShaders.shaderSource(fragment, """
                #version 130
                void main() {
                    gl_FragColor = vec4(1.0);
                }
                """);
        GlShaders.compileShader(fragment);
        GlShaders.attachShader(program, vertex);
        GlShaders.attachShader(program, fragment);
        GlShaders.linkProgram(program);
        assertEquals(1, GlShaders.programStatus(program, GlShaders.LINK_STATUS),
                GlShaders.programLog(program));
        return GlShaders.program(program);
    }
}
