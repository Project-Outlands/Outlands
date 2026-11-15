package net.minecraftforge.client.render.shader;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code glValidateProgramARB} and the two object queries the pack's shader loaders ask after linking.
 *
 * <p><b>Seven mods, one pattern.</b> CoFHCore, EerieEntities, Immersive Engineering, IndustrialWires, IvToolkit,
 * LibrarianLib and SanLib all carry the same {@code ShaderHelper}: create, compile, attach, link, validate, then read
 * the info-log length, allocate that many bytes and read the log. Answering 0 to the validate status made every one of
 * them print "validation failed" for a program that then drew fine, and answering 0 to the info-log length made the
 * log come back empty - so a genuinely broken shader reported "error: " and nothing else.</p>
 */
class ProgramValidationTest {

    @BeforeEach
    void resetTheFacade() {
        GlShaders.reset();
    }

    @Test
    void aLinkedProgramValidates() {
        int program = link(true);
        GlShaders.validateProgram(program);
        assertEquals(1, GlShaders.programStatus(program, GlShaders.VALIDATE_STATUS),
                "a program that links is a program this backend can draw with");
        assertEquals(1, GlShaders.programStatus(program, GlShaders.LINK_STATUS));
    }

    @Test
    void aProgramThatDidNotLinkDoesNotValidate() {
        int program = link(false);
        assertEquals(0, GlShaders.programStatus(program, GlShaders.LINK_STATUS));
        assertEquals(0, GlShaders.programStatus(program, GlShaders.VALIDATE_STATUS),
                "and it does not validate either - the answer is the link status, not a constant 1");
    }

    @Test
    void anEmptyLogReportsLengthZero() {
        int program = link(true);
        assertEquals(0, GlShaders.programStatus(program, GlShaders.INFO_LOG_LENGTH),
                "GL reports 0 when there is no log, and a caller then reads none");
    }

    @Test
    void aFailedLinkReportsTheLogsLength() {
        int program = link(false);
        int length = GlShaders.programStatus(program, GlShaders.INFO_LOG_LENGTH);
        assertTrue(length > 0, "a failed link has something to say");
        assertEquals(GlShaders.programLog(program).length() + 1, length,
                "the characters plus the terminator, which is what GL counts");
    }

    @Test
    void theLengthQueryWorksForEitherKindOfObject() {
        // ARB names shaders and programs from one namespace, so the query cannot tell them apart and has to ask both.
        int program = link(true);
        int shader = GlShaders.createShader(GlShaders.FRAGMENT_SHADER);
        GlShaders.shaderSource(shader, "#version 130\nvoid main() { gl_FragColor = vec4(1.0); }\n");
        GlShaders.compileShader(shader);

        assertEquals(0, GlShaders.objectLogLength(shader), "a shader that compiled has no log");
        assertEquals(0, GlShaders.objectLogLength(program), "and neither has a program that linked");
        assertEquals(0, GlShaders.objectLogLength(9999), "an unknown name has none either");
    }

    private static int link(boolean valid) {
        int program = GlShaders.createProgram();
        int vertex = GlShaders.createShader(GlShaders.VERTEX_SHADER);
        GlShaders.shaderSource(vertex, valid
                ? "#version 130\nvoid main() { gl_Position = vec4(0.0); }\n"
                : "#version 130\nvoid main() { gl_Position = undefinedThing; }\n");
        GlShaders.compileShader(vertex);
        int fragment = GlShaders.createShader(GlShaders.FRAGMENT_SHADER);
        GlShaders.shaderSource(fragment, "#version 130\nvoid main() { gl_FragColor = vec4(1.0); }\n");
        GlShaders.compileShader(fragment);
        GlShaders.attachShader(program, vertex);
        GlShaders.attachShader(program, fragment);
        GlShaders.linkProgram(program);
        return program;
    }
}
