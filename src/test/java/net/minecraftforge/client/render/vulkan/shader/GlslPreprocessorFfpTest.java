package net.minecraftforge.client.render.vulkan.shader;

import net.minecraftforge.client.render.shader.GlShaders;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixed-function built-ins, against the shaders that actually use them.
 *
 * <p>The two sources below are copied verbatim from Immersive Intelligence 0.3.1, which is the mod whose gun model
 * failed to appear with its effects: eight of its post-processing stages were rejected with "gl_TexCoord: undeclared
 * identifier" and "gl_Color: undeclared identifier". The program is built here the way the mod builds it - through
 * {@code GlShaders}, which is what the ARB entry points reach - so a green test means the mod's own shaders compile
 * and link.</p>
 */
class GlslPreprocessorFfpTest {

    /** {@code assets/immersiveintelligence/shaders/standard.vert}, verbatim. */
    private static final String STANDARD_VERT = """
            #version 120

            // From TTFTCUTS, thanks TTFTCUTS!

            varying vec3 position;

            void main()
            {
                gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
                position = (gl_ModelViewMatrix * gl_Vertex).xyz;
            }
            """;

    /** {@code assets/immersiveintelligence/shaders/alpha.frag}, verbatim. */
    private static final String ALPHA_FRAG = """
            #version 130

            //Author: Pabilo8 (pabilo@iiteam.net)
            //Alpha fragment shader
            uniform float alpha;// Passed in by callback

            uniform sampler2D texture;
            uniform sampler2D lightmap;

            void main()
            {
                vec4 tex = texture2D(texture, gl_TexCoord[0].st);
                vec4 light = texture2D(lightmap, gl_TexCoord[1].st);
                gl_FragColor = tex * gl_Color * light * vec4(1.0, 1.0, 1.0, alpha);
            }
            """;

    @Test
    void theModsVertexShaderDeclaresTheBuiltinsItReads() {
        String rewritten = GlslPreprocessor.forVulkan(STANDARD_VERT, GlslCompiler.Stage.VERTEX);

        assertTrue(rewritten.contains("#define gl_Vertex outlandsVertex"), rewritten);
        assertTrue(rewritten.contains("#define gl_ModelViewProjectionMatrix outlandsMvp"), rewritten);
        assertTrue(rewritten.contains("layout(push_constant) uniform OutlandsFfpMatrices"), rewritten);
        assertTrue(rewritten.contains("void outlandsMain("), "the shader's main is wrapped, not replaced");
        assertTrue(rewritten.contains("outlandsFfpColor = outlandsVertexColor;"),
                "the wrapper fills the varyings before the shader runs");
        assertTrue(rewritten.contains("#define gl_ModelViewMatrix outlandsMvp"),
                "the model-view matrix has no room in the push constant block and is approximated");
    }

    @Test
    void theModsFragmentShaderGetsTheVaryingsItsVertexStageWrites() {
        String rewritten = GlslPreprocessor.forVulkan(ALPHA_FRAG, GlslCompiler.Stage.FRAGMENT);

        assertTrue(rewritten.contains("#define gl_TexCoord outlandsTexCoord"), rewritten);
        assertTrue(rewritten.contains("#define gl_Color outlandsFfpColor"), rewritten);
        assertTrue(rewritten.contains("in vec4 outlandsTexCoord[2];"), rewritten);
        // The redirect is a macro, so the source keeps the name and the compiler expands it - which is what makes
        // gl_TexCoord[1].st work without the rewrite having to parse swizzles.
        assertTrue(rewritten.contains("gl_TexCoord[0].st"), rewritten);
        // The mod names its sampler "texture", which GLSL 1.20 allowed and 1.50 does not: the name is now a built-in
        // function the rewrite calls. Renaming the variable is the only fix - working around it in the call would
        // still leave the variable shadowing the function.
        assertTrue(rewritten.contains("uniform sampler2D outlands_texture;"), rewritten);
        assertTrue(rewritten.contains("texture(outlands_texture, gl_TexCoord[0].st)"),
                "the call still reaches the built-in: " + rewritten);
    }

    @Test
    void theModsProgramCompilesAndLinks() {
        int program = GlShaders.createProgram();
        int vertex = shader(GlShaders.VERTEX_SHADER, STANDARD_VERT);
        int fragment = shader(GlShaders.FRAGMENT_SHADER, ALPHA_FRAG);
        GlShaders.attachShader(program, vertex);
        GlShaders.attachShader(program, fragment);
        GlShaders.linkProgram(program);

        assertEquals(1, GlShaders.programStatus(program, GlShaders.LINK_STATUS),
                "the two stages must agree on the varyings: " + GlShaders.programLog(program));
        assertTrue(GlShaders.program(program).vertexInputLocations().length > 0,
                "and the vertex stage declares the canonical attributes");
    }

    /** {@code assets/journeymap/shaders/core/jm_position_tex_color.vsh}, verbatim. */
    private static final String JM_VERT = """
            #version 120

            varying vec2 texCoord0;
            varying vec4 vertexColor;

            void main() {
                gl_Position = ftransform();

                texCoord0 = gl_MultiTexCoord0.st;
                vertexColor = gl_Color;
            }
            """;

    @Test
    void ftransformAndTheTexCoordBuiltinAreTranslated() {
        String rewritten = GlslPreprocessor.forVulkan(JM_VERT, GlslCompiler.Stage.VERTEX);

        assertTrue(rewritten.contains("#define ftransform() (outlandsMvp * outlandsVertex)"), rewritten);
        assertTrue(rewritten.contains("#define gl_MultiTexCoord0 outlandsUv"), rewritten);
        assertTrue(rewritten.contains("#define gl_Color outlandsVertexColor"), rewritten);
        shader(GlShaders.VERTEX_SHADER, JM_VERT);
    }

    @Test
    void aOneHundredFiftyShaderGetsItsBlockWithoutABinding() {
        // An explicit binding is only legal from 4.20, and a source that declared 1.50 keeps its version. journeymap's
        // post-processing stages are exactly this shape and were rejected for it.
        String source = """
                #version 150
                uniform sampler2D Sampler0;
                uniform vec4 ColorModulator;
                in vec2 texCoord0;
                in vec4 vertexColor;
                out vec4 fragColor;
                void main() {
                    fragColor = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
                }
                """;

        String rewritten = GlslPreprocessor.forVulkan(source, GlslCompiler.Stage.FRAGMENT);

        assertTrue(rewritten.startsWith("#version 150"), rewritten);
        assertTrue(rewritten.contains("uniform OutlandsUniforms {"), rewritten);
        assertFalse(rewritten.contains("layout(binding"), "no explicit binding below 4.20: " + rewritten);
        shader(GlShaders.FRAGMENT_SHADER, source);
    }

    @Test
    void everyOneTwentySamplingFunctionBecomesTexture() {
        // GLSL 1.50 has one overloaded texture() and no dimension in the name, so an unmapped texture1D parses as a
        // constructor call. LibrarianLib's blur samples a 1D kernel.
        String source = """
                #version 120
                uniform sampler1D kernel;
                uniform sampler2D image;
                uniform vec2 displaySize;
                void main() {
                    float weight = texture1D(kernel, 0.5).r;
                    gl_FragColor = texture2D(image, gl_FragCoord.xy / displaySize) * weight;
                }
                """;

        String rewritten = GlslPreprocessor.forVulkan(source, GlslCompiler.Stage.FRAGMENT);

        assertTrue(rewritten.contains("texture(kernel, 0.5)"), rewritten);
        assertTrue(rewritten.contains("texture(image, gl_FragCoord.xy / outlandsUniforms.displaySize)"), rewritten);
        shader(GlShaders.FRAGMENT_SHADER, source);
    }

    private static int shader(int type, String source) {
        int shader = GlShaders.createShader(type);
        GlShaders.shaderSource(shader, source);
        GlShaders.compileShader(shader);
        assertEquals(1, GlShaders.shaderStatus(shader, GlShaders.COMPILE_STATUS),
                "stage " + type + " compiles: " + GlShaders.shaderLog(shader));
        return shader;
    }
}
