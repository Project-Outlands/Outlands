package net.minecraftforge.client.render.shader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A program with only a fragment stage, which is how every one of Immersive Intelligence's post-processing stages is
 * built.
 *
 * <p>GL allows it - the fixed-function pipeline supplied the vertex processing - and the shader is written to read
 * {@code gl_TexCoord}/{@code gl_Color}, which is exactly what the fixed-function vertex stage writes. Without a
 * stand-in, the backend died with "program N is missing a stage: no vertex stage" and took the frame with it.</p>
 */
class FragmentOnlyProgramTest {

    private static final String ALPHA_FRAG = """
            #version 130
            uniform float alpha;
            uniform sampler2D texture;
            uniform sampler2D lightmap;
            void main() {
                vec4 tex = texture2D(texture, gl_TexCoord[0].st);
                vec4 light = texture2D(lightmap, gl_TexCoord[1].st);
                gl_FragColor = tex * gl_Color * light * vec4(1.0, 1.0, 1.0, alpha);
            }
            """;

    @Test
    void aFragmentOnlyProgramLinksAgainstTheFixedFunctionVertexStage() {
        GlShaders.reset();
        int program = GlShaders.createProgram();
        int fragment = GlShaders.createShader(GlShaders.FRAGMENT_SHADER);
        GlShaders.shaderSource(fragment, ALPHA_FRAG);
        GlShaders.compileShader(fragment);
        assertEquals(1, GlShaders.shaderStatus(fragment, GlShaders.COMPILE_STATUS),
                GlShaders.shaderLog(fragment));
        GlShaders.attachShader(program, fragment);
        GlShaders.linkProgram(program);

        assertEquals(1, GlShaders.programStatus(program, GlShaders.LINK_STATUS),
                "linked: " + GlShaders.programLog(program));
        assertNotNull(GlShaders.program(program).stageModule(GlShaders.VERTEX_SHADER),
                "and the program has a vertex stage to build a pipeline from");
        assertTrue(GlShaders.program(program).vertexInputLocations().length > 0,
                "the stand-in declares the canonical attributes, so the pipeline can feed it");
    }
}
