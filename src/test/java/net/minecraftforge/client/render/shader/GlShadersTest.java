package net.minecraftforge.client.render.shader;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.minecraftforge.client.render.vulkan.shader.GlslCompiler;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The GL object model, answered without a GL context.
 *
 * <p>What the game does with this - create, source, compile, attach, link, look up a name, write a value - is exactly
 * what these tests do, because the failures that matter are the ones where the object model quietly disagrees: a
 * uniform located at the wrong offset writes into a neighbouring member, and a status that says "linked" while the
 * program has no modules fails later as an empty draw.</p>
 */
class GlShadersTest {

    @AfterAll
    static void releaseCompiler() {
        GlslCompiler.release();
    }

    @BeforeEach
    void reset() {
        GlShaders.reset();
    }

    @Test
    void compilesAndLinksAProgramWithANamedUniform() {
        int vertex = shader(GlShaders.VERTEX_SHADER, """
                #version 120
                attribute vec4 Position;
                uniform mat4 ProjMat;
                void main() { gl_Position = ProjMat * Position; }
                """);
        int fragment = shader(GlShaders.FRAGMENT_SHADER, """
                #version 120
                uniform sampler2D DiffuseSampler;
                uniform vec4 ColorModulate;
                void main() { gl_FragColor = texture2D(DiffuseSampler, vec2(0.5)) * ColorModulate; }
                """);

        int program = GlShaders.createProgram();
        GlShaders.attachShader(program, vertex);
        GlShaders.attachShader(program, fragment);
        GlShaders.bindAttribLocation(program, 0, "Position");
        GlShaders.linkProgram(program);

        assertEquals(1, GlShaders.programStatus(program, GlShaders.LINK_STATUS),
                "link must succeed: " + GlShaders.programLog(program));
        assertEquals(0, GlShaders.program(program).attributeLocations().get("Position"));
        assertNotNull(GlShaders.program(program).uniform("DiffuseSampler"), "the sampler is addressable by name");
        assertNotNull(GlShaders.program(program).uniform("ColorModulate"), "and so is the vec4");
        assertNotNull(GlShaders.program(program).uniform("ProjMat"), "and the matrix, from the vertex stage");
    }

    @Test
    void uniformValuesLandAtTheDeclaredOffsets() {
        int fragment = shader(GlShaders.FRAGMENT_SHADER, """
                #version 120
                uniform float LumaRamp;
                uniform float LumaLevel;
                void main() { gl_FragColor = vec4(LumaRamp + LumaLevel); }
                """);
        int program = GlShaders.createProgram();
        GlShaders.attachShader(program, fragment);
        GlShaders.linkProgram(program);

        int ramp = GlShaders.uniformLocation(program, "LumaRamp");
        int level = GlShaders.uniformLocation(program, "LumaLevel");
        assertTrue(ramp > 0 && level > 0, "both are found");

        GlShaders.uniform1f(ramp, 2.5F);
        GlShaders.uniform1f(level, -1.0F);

        ByteBuffer bytes = ByteBuffer.wrap(GlShaders.program(program).blockBytes()).order(ByteOrder.nativeOrder());
        assertEquals(2.5F, bytes.getFloat(0), "LumaRamp is at offset 0");
        assertEquals(-1.0F, bytes.getFloat(4), "LumaLevel follows it");
    }

    @Test
    void aMatrixIsWrittenColumnMajor() {
        int vertex = shader(GlShaders.VERTEX_SHADER, """
                #version 120
                attribute vec4 Position;
                uniform mat4 ProjMat;
                void main() { gl_Position = ProjMat * Position; }
                """);
        int program = GlShaders.createProgram();
        GlShaders.attachShader(program, vertex);
        GlShaders.linkProgram(program);

        int location = GlShaders.uniformLocation(program, "ProjMat");
        float[] columnMajor = new float[16];
        for (int i = 0; i < 16; i++) {
            columnMajor[i] = i;
        }
        GlShaders.uniformMatrix4(location, false, FloatBuffer.wrap(columnMajor));

        ByteBuffer bytes = ByteBuffer.wrap(GlShaders.program(program).blockBytes()).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 16; i++) {
            assertEquals(i, bytes.getFloat(i * 4), "element " + i + " is where column-major puts it");
        }
    }

    @Test
    void aBrokenShaderFailsToLinkWithoutThrowing() {
        // The game checks the status and prints the log; a broken shader pack must not take the process down.
        int fragment = shader(GlShaders.FRAGMENT_SHADER, """
                #version 120
                void main() { this is not glsl }
                """);
        assertEquals(0, GlShaders.shaderStatus(fragment, GlShaders.COMPILE_STATUS));
        assertTrue(GlShaders.shaderLog(fragment).contains("error"), "the compiler's message is kept");
        assertFalse(GlShaders.shaderLog(fragment).isEmpty());

        int program = GlShaders.createProgram();
        GlShaders.attachShader(program, fragment);
        GlShaders.linkProgram(program);

        assertEquals(0, GlShaders.programStatus(program, GlShaders.LINK_STATUS));
        assertNull(GlShaders.program(program), "an unlinked program is not a drawable one");
    }

    @Test
    void unknownUniformNamesAnswerMinusOne() {
        int fragment = shader(GlShaders.FRAGMENT_SHADER, """
                #version 120
                uniform float Known;
                void main() { gl_FragColor = vec4(Known); }
                """);
        int program = GlShaders.createProgram();
        GlShaders.attachShader(program, fragment);
        GlShaders.linkProgram(program);

        assertEquals(-1, GlShaders.uniformLocation(program, "NotThere"),
                "GL answers -1, and the game checks for it");
        assertTrue(GlShaders.uniformLocation(program, "Known") > 0);
    }

    @Test
    void useProgramTracksTheCurrentProgram() {
        int fragment = shader(GlShaders.FRAGMENT_SHADER, """
                #version 120
                void main() { gl_FragColor = vec4(1.0); }
                """);
        int program = GlShaders.createProgram();
        GlShaders.attachShader(program, fragment);
        GlShaders.linkProgram(program);

        GlShaders.useProgram(program);
        assertEquals(program, GlShaders.currentProgram());
        GlShaders.useProgram(0);
        assertEquals(0, GlShaders.currentProgram(), "program 0 is \"none\", as in GL");
    }

    private static int shader(int type, String source) {
        int id = GlShaders.createShader(type);
        GlShaders.shaderSource(id, source);
        GlShaders.compileShader(id);
        return id;
    }
}
