package net.minecraftforge.client.render.shader;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.minecraftforge.client.render.vulkan.shader.GlslCompiler;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    @Test
    void integerVectorsAreWrittenAtTheDeclaredOffsets() {
        // ShaderUniform writes ivec uniforms through glUniform1..4(IntBuffer), which is a different call from the
        // scalar glUniform1i: the game uses both, and an int written as a float is a uniform that is silently wrong.
        int fragment = shader(GlShaders.FRAGMENT_SHADER, """
                #version 120
                uniform ivec2 BlockPos;
                uniform int Layer;
                void main() { gl_FragColor = vec4(float(BlockPos.x), float(BlockPos.y), float(Layer), 1.0); }
                """);
        int program = GlShaders.createProgram();
        GlShaders.attachShader(program, fragment);
        GlShaders.linkProgram(program);

        GlShaders.uniform2i(GlShaders.uniformLocation(program, "BlockPos"), IntBuffer.wrap(new int[] {7, -9}));
        GlShaders.uniform1i(GlShaders.uniformLocation(program, "Layer"), 3);

        ByteBuffer bytes = ByteBuffer.wrap(GlShaders.program(program).blockBytes()).order(ByteOrder.nativeOrder());
        assertEquals(7, bytes.getInt(0), "BlockPos.x");
        assertEquals(-9, bytes.getInt(4), "BlockPos.y");
        assertEquals(3, bytes.getInt(8), "Layer follows the vector");
    }

    @Test
    void aThreeByThreeMatrixHonoursTheTransposeFlag() {
        int fragment = shader(GlShaders.FRAGMENT_SHADER, """
                #version 120
                uniform mat3 Tint;
                void main() { gl_FragColor = vec4(Tint[0], 1.0); }
                """);
        int program = GlShaders.createProgram();
        GlShaders.attachShader(program, fragment);
        GlShaders.linkProgram(program);

        // Row-major input: 1 2 3 / 4 5 6 / 7 8 9. Transposed, the first column becomes 1 4 7.
        float[] rowMajor = {1, 2, 3, 4, 5, 6, 7, 8, 9};
        GlShaders.uniformMatrix3(GlShaders.uniformLocation(program, "Tint"), true, FloatBuffer.wrap(rowMajor));

        ByteBuffer bytes = ByteBuffer.wrap(GlShaders.program(program).blockBytes()).order(ByteOrder.nativeOrder());
        assertEquals(1.0F, bytes.getFloat(0));
        assertEquals(4.0F, bytes.getFloat(4), "a transposed matrix swaps row and column, it is not just copied");
        assertEquals(7.0F, bytes.getFloat(8));
    }

    @Test
    void anAttributeLocationComesFromTheModuleWhenNothingWasBound() {
        // The game binds its attributes by name, but a mod's shader usually does not, and glGetAttribLocation still has
        // to answer with the location the module actually declares.
        int vertex = shader(GlShaders.VERTEX_SHADER, """
                #version 120
                attribute vec4 Position;
                attribute vec2 UV;
                void main() { gl_Position = Position + vec4(UV, 0.0, 0.0); }
                """);
        int program = GlShaders.createProgram();
        GlShaders.attachShader(program, vertex);
        GlShaders.linkProgram(program);

        int position = GlShaders.attribLocation(program, "Position");
        int uv = GlShaders.attribLocation(program, "UV");
        assertTrue(position >= 0 && uv >= 0, "both attributes have a location, got " + position + " and " + uv);
        assertNotEquals(position, uv, "two attributes cannot share one");
        assertEquals(-1, GlShaders.attribLocation(program, "NotThere"), "GL answers -1 for an attribute it lacks");
    }

    @Test
    void pointingASamplerAtATextureUnitDoesNotOverwriteTheUniformBlock() {
        // glUniform1i on a sampler names a texture unit; a sampler has no place in the block. Writing it there anyway
        // would land on whichever member the compiler put at offset 0 - the first uniform the game ever set.
        int fragment = shader(GlShaders.FRAGMENT_SHADER, """
                #version 120
                uniform float First;
                uniform sampler2D DiffuseSampler;
                void main() { gl_FragColor = texture2D(DiffuseSampler, vec2(0.5)) * First; }
                """);
        int program = GlShaders.createProgram();
        GlShaders.attachShader(program, fragment);
        GlShaders.linkProgram(program);

        int first = GlShaders.uniformLocation(program, "First");
        GlShaders.uniform1f(first, 0.25F);
        GlShaders.uniform1i(GlShaders.uniformLocation(program, "DiffuseSampler"), 3);

        ByteBuffer bytes = ByteBuffer.wrap(GlShaders.program(program).blockBytes()).order(ByteOrder.nativeOrder());
        assertEquals(0.25F, bytes.getFloat(0), "the block still holds the value written before the sampler");
        assertEquals(3, GlShaders.program(program).samplerUnit("DiffuseSampler"),
                "and the unit the sampler reads is recorded for the draw");
    }

    private static int shader(int type, String source) {
        int id = GlShaders.createShader(type);
        GlShaders.shaderSource(id, source);
        GlShaders.compileShader(id);
        return id;
    }
}
