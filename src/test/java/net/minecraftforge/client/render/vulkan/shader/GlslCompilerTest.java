package net.minecraftforge.client.render.vulkan.shader;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The runtime GLSL to SPIR-V path, driven the way a mod would drive it.
 *
 * <p>What matters here is not that shaderc works - it is the same compiler the build uses - but that this wrapper
 * answers the two things a GL implementation needs from it: a module for source that compiles, and the compiler's
 * own message for source that does not. A failed compile must not be a crash, because GL reports it as state on the
 * shader object and the game carries on.</p>
 */
class GlslCompilerTest {

    @AfterAll
    static void releaseCompiler() {
        GlslCompiler.release();
    }

    @Test
    void compilesAVertexShader() {
        byte[] spirv = GlslCompiler.compile("""
                #version 450
                layout(location = 0) in vec3 position;
                void main() {
                    gl_Position = vec4(position, 1.0);
                }
                """, GlslCompiler.Stage.VERTEX, "test.vert");

        assertTrue(spirv.length >= 20, "a module is at least its five-word header, got " + spirv.length);
        assertEquals(0, spirv.length % 4, "SPIR-V is a stream of 32-bit words");
        // Magic number 0x07230203, little-endian, which is the only byte order this runs on.
        assertEquals(0x03, spirv[0] & 0xFF);
        assertEquals(0x02, spirv[1] & 0xFF);
        assertEquals(0x23, spirv[2] & 0xFF);
        assertEquals(0x07, spirv[3] & 0xFF);
    }

    @Test
    void compilesAFragmentShader() {
        byte[] spirv = GlslCompiler.compile("""
                #version 450
                layout(location = 0) out vec4 colour;
                void main() {
                    colour = vec4(1.0);
                }
                """, GlslCompiler.Stage.FRAGMENT, "test.frag");

        assertTrue(spirv.length >= 20);
    }

    @Test
    void reportsTheCompilersMessageInsteadOfThrowingBlindly() {
        GlslCompiler.GlslCompileException failure = assertThrows(GlslCompiler.GlslCompileException.class,
                () -> GlslCompiler.compile("""
                        #version 450
                        void main() {
                            this is not glsl
                        }
                        """, GlslCompiler.Stage.FRAGMENT, "broken.frag"));

        assertEquals("broken.frag", failure.shaderName());
        assertTrue(failure.log().contains("broken.frag") || failure.log().contains("error"),
                "the compiler's message names the problem, got: " + failure.log());
    }

    @Test
    void glsl150Compiles() {
        // 1.50 is the oldest desktop GLSL shaderc accepts for Vulkan SPIR-V, and it is what a mod targeting GL 3.2
        // writes. This is the floor the runtime path works from.
        byte[] spirv = GlslCompiler.compile("""
                #version 150
                out vec4 colour;
                void main() {
                    colour = vec4(1.0, 0.0, 0.0, 1.0);
                }
                """, GlslCompiler.Stage.FRAGMENT, "modern.frag");

        assertTrue(spirv.length >= 20);
    }

    @Test
    void legacyGlsl120IsRejectedAndThatIsWhyAPreprocessorIsNeeded() {
        // Measured, not assumed: every one of the game's 34 post-processing shaders - the entity outline included - is
        // GLSL 1.20, and shaderc refuses anything below 1.40 for Vulkan. So "compile the GLSL we are handed" is not
        // sufficient; the source has to be brought forward first (attribute/varying, gl_FragColor, texture2D, and
        // explicit attribute and sampler locations, since Vulkan has no name binding). This test exists to keep that
        // requirement visible: when the preprocessor lands, this becomes the case that proves it works, not a
        // documented limitation.
        GlslCompiler.GlslCompileException failure = assertThrows(GlslCompiler.GlslCompileException.class,
                () -> GlslCompiler.compile("""
                        #version 120
                        varying vec2 texCoord;
                        uniform sampler2D DiffuseSampler;
                        void main() {
                            gl_FragColor = texture2D(DiffuseSampler, texCoord);
                        }
                        """, GlslCompiler.Stage.FRAGMENT, "legacy120.frag"));

        assertTrue(failure.log().contains("version"),
                "the reason must be the version, got: " + failure.log());
    }
}
